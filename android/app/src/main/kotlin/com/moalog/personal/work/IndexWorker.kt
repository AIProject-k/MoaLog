package com.moalog.personal.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.moalog.personal.AppGraph
import com.moalog.personal.R
import com.moalog.personal.core.Embeddings
import com.moalog.personal.core.Events
import com.moalog.personal.core.Grouping
import com.moalog.personal.data.Store
import com.moalog.personal.data.GROUPING_COMPLETE
import com.moalog.personal.data.KEY_VIDEO_CAPTURE_BACKFILL
import com.moalog.personal.data.KEY_VIDEO_SAMPLING
import com.moalog.personal.data.KEY_VIDEO_TAGS
import com.moalog.personal.core.VideoTags
import com.moalog.personal.core.VideoSampling
import kotlinx.coroutines.delay
import java.security.MessageDigest

enum class IndexPhase(val label: String) { PHOTOS("사진"), VIDEOS("영상") }

/** 사진 메타 → 사진 CLIP → 영상 메타 → 영상 프레임 순서로 실행한다. 각 항목의 state는 재시작 후에도 남는다. */
class IndexWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val store get() = AppGraph.store
    private val media get() = AppGraph.media

    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        setForeground(notice("사진을 읽고 있습니다", 0, 0))

        return runCatching {
            // 실행 중 죽으면 다음 실행에서 그룹을 복구한다. 변경 없는 재실행만 전체 재계산을 건너뛴다.
            val groupingWasComplete = store.metaGet(GROUPING_COMPLETE) == "1"
            store.metaPut(GROUPING_COMPLETE, "0")
            // Finish the photo scan and analysis before touching the video collection.
            var stageStart = android.os.SystemClock.elapsedRealtimeNanos()
            val photoSync = media.syncPhotos()
            logPerf("photo_sync", stageStart, "rows=${photoSync.total}")

            stageStart = android.os.SystemClock.elapsedRealtimeNanos()
            val photoMetaChanged = pass1(IndexPhase.PHOTOS)
            logPerf("photo_metadata", stageStart)
            if (isStopped) return@runCatching Result.retry()
            if (photoMetaChanged) {
                stageStart = android.os.SystemClock.elapsedRealtimeNanos()
                regroup(withEmbeddings = false, phase = IndexPhase.PHOTOS)
                logPerf("photo_grouping_metadata", stageStart)
            }

            val withClip = AppGraph.modelsReady && inputData.getBoolean(KEY_WITH_CLIP, true)
            // 모델이 깨졌으면 CLIP을 건너뛰는 이유를 상태에 남긴다. 조용히 건너뛰면 원인을 알 수 없다.
            if (!AppGraph.modelsReady) AppGraph.modelError?.let { report(IndexPhase.PHOTOS, it) }
            val modelId = if (withClip) AppGraph.modelId else null
            val modelChanged = modelId != null && store.checkModel(modelId) > 0
            stageStart = android.os.SystemClock.elapsedRealtimeNanos()
            val photoClipChanged = if (modelId != null) pass2Photos(modelId) ?: return@runCatching Result.retry() else false
            logPerf("photo_clip", stageStart)
            if (photoSync.discovered > 0 || photoSync.changed > 0 || photoSync.removed > 0 || photoSync.accessChanged ||
                photoMetaChanged || photoClipChanged || modelChanged || !groupingWasComplete) {
                stageStart = android.os.SystemClock.elapsedRealtimeNanos()
                regroup(withEmbeddings = true, phase = IndexPhase.PHOTOS)
                logPerf("photo_grouping_clip", stageStart)
            }
            report(IndexPhase.PHOTOS, "사진 처리 완료")
            if (isStopped) return@runCatching Result.retry()

            report(IndexPhase.VIDEOS, "영상 처리 준비 중")
            stageStart = android.os.SystemClock.elapsedRealtimeNanos()
            val videoSync = media.syncVideos()
            logPerf("video_sync", stageStart, "rows=${videoSync.total}")
            // 샘플링 방식이 바뀌었으면 예전 방식으로 분석된 영상을 한 번 다시 돌린다. 도장은 큐에
            // 올린 직후 찍는다 — 중간에 죽어도 남은 영상은 state로 이어지지, 처음부터 다시 오르지 않는다.
            // 모델이 없어 어차피 Pass 2를 못 돌릴 때는 건드리지 않는다(태그만 남기고 멈추면 안 된다).
            val resample = modelId != null && store.metaGet(KEY_VIDEO_SAMPLING) != VideoSampling.STAMP
            if (resample) {
                val n = store.requeueVideos()
                store.metaPut(KEY_VIDEO_SAMPLING, VideoSampling.STAMP)
                logPerf("video_requeue", android.os.SystemClock.elapsedRealtimeNanos(), "videos=$n stamp=${VideoSampling.STAMP}")
            }
            val videoMetaChanged = pass1(IndexPhase.VIDEOS) or backfillVideoCapture()
            if (isStopped) return@runCatching Result.retry()
            stageStart = android.os.SystemClock.elapsedRealtimeNanos()
            val videoClipChanged = if (modelId != null) pass2Videos(modelId) ?: return@runCatching Result.retry() else false
            logPerf("video_clip", stageStart)
            // 태그 규칙이 바뀌었으면 저장된 프레임으로 태그만 다시 만든다(프레임은 다시 뽑지 않는다)
            val videoRetagged = modelId != null && retagVideos()
            if (videoSync.discovered > 0 || videoSync.changed > 0 || videoSync.removed > 0 ||
                videoSync.accessChanged || videoMetaChanged || videoClipChanged || resample || videoRetagged)
                regroup(withEmbeddings = true, phase = IndexPhase.VIDEOS)
            store.metaPut(GROUPING_COMPLETE, "1")

            Result.success()
        }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("IndexWorker", "미디어 분석 실패", e)
            // 재시도가 도움이 되는 실패(일시적 I/O)와 아닌 것(모델 없음)을 구분할 방법이
            // 마땅치 않다. state가 남아 있으므로 다음 실행이 이어 가고, 무한 재시도는 피한다.
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    // ---------- Pass 1 ----------

    private suspend fun pass1(phase: IndexPhase): Boolean {
        val tagger = AppGraph.ruleTagger
        var processed = false
        while (true) {
            val batch = if (phase == IndexPhase.PHOTOS) store.pendingPhotos(Store.State.NEW, BATCH)
                else store.pendingVideos(Store.State.NEW, BATCH)
            if (batch.isEmpty()) break
            for (p in batch) {
                processed = true
                runCatching {
                    // 촬영시각이 없으면 파일 수정시각으로 떨어진다. 그 사실을
                    // taken_at_source에 남겨야 연사·이벤트가 이 값을 믿지 않는다(§4.5).
                    val meta = media.readMeta(p, fallbackSeconds = p.dateModified)
                    val pm = p.toMeta(meta.takenAt, meta.takenAtSource, meta.cameraModel, meta.lat != null)
                    store.saveMeta(p.id, meta, tagger.tags(pm))
                }.onFailure { store.markFailed(p.id) }
            }
            report(phase, "촬영 정보를 정리하고 있습니다")
            if (isStopped) return processed
        }
        return processed
    }

    /**
     * 예전 빌드는 영상 촬영 시각을 읽지 않고 파일 시각으로 저장했다. 한 번만, 시각·위치만
     * 다시 읽는다 — 프레임 분석을 다시 돌리면 영상 수백 개에 몇 분이 들기 때문이다.
     */
    private suspend fun backfillVideoCapture(): Boolean {
        if (store.metaGet(KEY_VIDEO_CAPTURE_BACKFILL) != "1") return false
        var changed = false
        for ((id, uri) in store.videosWithFileTime()) {
            if (isStopped) return changed
            val m = media.readVideoMeta(uri, fallbackSeconds = 0)
            if (m.takenAtSource == com.moalog.personal.core.TimeSource.EXIF) {
                store.updateCapture(id, m); changed = true
            }
        }
        store.metaPut(KEY_VIDEO_CAPTURE_BACKFILL, "0")
        if (changed) report(IndexPhase.VIDEOS, "영상 촬영 시각을 정리했습니다")
        return changed
    }

    /**
     * 영상 태그 규칙([VideoTags.RULE])이 바뀌었으면 **저장된 프레임 벡터로** 태그를 다시 만든다.
     * 프레임을 다시 열지 않으므로 영상 수백 개도 몇 초면 끝난다. 규칙을 고칠 때마다 영상을 처음부터
     * 다시 분석(수 분)하지 않으려고 프레임 추출 도장과 따로 둔다.
     * @return 태그를 다시 만든 영상이 있는가
     */
    private fun retagVideos(): Boolean {
        if (store.metaGet(KEY_VIDEO_TAGS) == VideoTags.RULE) return false
        val t0 = android.os.SystemClock.elapsedRealtimeNanos()
        val clf = AppGraph.classifier()
        var n = 0
        for (id in store.videoIdsWithFrames()) {
            val vectors = store.frames(id).values
            if (vectors.isEmpty()) continue
            store.setClipTags(id, VideoTags.aggregate(vectors.map(clf::classify)))
            n++
        }
        store.metaPut(KEY_VIDEO_TAGS, VideoTags.RULE)
        logPerf("video_retag", t0, "videos=$n rule=${VideoTags.RULE}")
        return n > 0
    }

    // ---------- 사진 Pass 2 ----------

    private suspend fun pass2Photos(modelId: String): Boolean? {
        if (store.pendingPhotos(Store.State.META, 1).isEmpty()) return false
        val enc = AppGraph.encoder
        val clf = AppGraph.classifier()
        val thumbPx = AppGraph.config.optInt("thumb_px", 320)
        var processed = false
        var lastReport = android.os.SystemClock.elapsedRealtime()

        try {
            while (true) {
                if (pauseIfHotOrLow(IndexPhase.PHOTOS)) return null
                val batch = store.pendingPhotos(Store.State.META, BATCH)
                if (batch.isEmpty()) break
                var thumbnailNs = 0L
                var qualityNs = 0L
                var encodeNs = 0L
                var classifyNs = 0L
                var persistNs = 0L
                val batchStart = android.os.SystemClock.elapsedRealtimeNanos()
                for (p in batch) {
                    if (isStopped) return null
                    processed = true
                    runCatching {
                        var stageStart = android.os.SystemClock.elapsedRealtimeNanos()
                        val bmp = media.thumbnail(p.uri, thumbPx) ?: error("썸네일 실패")
                        thumbnailNs += android.os.SystemClock.elapsedRealtimeNanos() - stageStart
                        // 흐림·밝기는 CLIP과 같은 비트맵에서 뽑는다 — 디코드를 두 번 하지 않는다
                        val quality: Quality.Result
                        val v: FloatArray
                        try {
                            stageStart = android.os.SystemClock.elapsedRealtimeNanos()
                            quality = Quality.of(bmp, AppGraph.config.optInt("analysis_px", 256))
                            qualityNs += android.os.SystemClock.elapsedRealtimeNanos() - stageStart
                            stageStart = android.os.SystemClock.elapsedRealtimeNanos()
                            v = enc.encodeImage(bmp)
                            encodeNs += android.os.SystemClock.elapsedRealtimeNanos() - stageStart
                        } finally { bmp.recycle() }
                        stageStart = android.os.SystemClock.elapsedRealtimeNanos()
                        val tags = clf.classify(v)
                        classifyNs += android.os.SystemClock.elapsedRealtimeNanos() - stageStart
                        stageStart = android.os.SystemClock.elapsedRealtimeNanos()
                        store.saveClip(p.id, v, quality.blur, quality.brightness, tags, modelId)
                        AppGraph.searchIndex.put(p.id, v)
                        persistNs += android.os.SystemClock.elapsedRealtimeNanos() - stageStart
                    }.onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        android.util.Log.e("IndexWorker", "사진 ${p.id} 분석 실패", it)
                        store.markFailed(p.id)
                    }
                    // 32장이 다 끝나길 기다리지 않고 1초마다 알린다. 느린 기기에서 분석 화면의 링이 멈춰 보이지 않게.
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastReport >= 1_000) {
                        report(IndexPhase.PHOTOS, "장면을 분류하고 있습니다")
                        lastReport = now
                    }
                }
                logPerf("photo_batch", batchStart,
                    "count=${batch.size} thumbnail_ms=${thumbnailNs / 1e6} quality_ms=${qualityNs / 1e6} " +
                        "encode_ms=${encodeNs / 1e6} classify_ms=${classifyNs / 1e6} persist_ms=${persistNs / 1e6}")
                report(IndexPhase.PHOTOS, "장면을 분류하고 있습니다")
            }
            return processed
        } finally {
            enc.closeVision()
        }
    }

    // ---------- 영상 Pass 2 ----------

    private suspend fun pass2Videos(modelId: String): Boolean? {
        if (store.pendingVideos(Store.State.META, 1).isEmpty()) return false
        val enc = AppGraph.encoder
        val clf = AppGraph.classifier()
        var processed = false
        // 실측용(디버그 로그). 프레임 수·디코드·인코딩 시간과 열·배터리 변화를 남긴다.
        var videos = 0; var frameCount = 0; var frameFail = 0
        var decodeNs = 0L; var encodeNs = 0L
        val battery0 = applicationContext.getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val t0 = android.os.SystemClock.elapsedRealtimeNanos()

        try {
            while (true) {
                if (pauseIfHotOrLow(IndexPhase.VIDEOS)) return null
                val batch = store.pendingVideos(Store.State.META, BATCH)
                if (batch.isEmpty()) break
                for (p in batch) {
                    if (isStopped) return null
                    processed = true
                    runCatching {
                        val retriever = android.media.MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(applicationContext, android.net.Uri.parse(p.uri))
                            val duration = p.durationMs?.takeIf { it > 0 }
                                ?: retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                                ?: 0L
                            // Some Samsung/provider videos expose no duration even though frame 0
                            // is decodable. Keep them searchable instead of failing the whole item.
                            val positions = if (duration > 0) com.moalog.personal.core.VideoSampling.positions(duration)
                                else listOf(0L)
                            val cached = store.frames(p.id).toMutableMap()
                            var unreportedFrames = 0
                            var failedFrames = 0
                            for (time in positions) {
                                if (isStopped || pauseIfHotOrLow(IndexPhase.VIDEOS)) return null
                                if (time !in cached) {
                                    val decodeStart = android.os.SystemClock.elapsedRealtimeNanos()
                                    val frame = try {
                                        retriever.getScaledFrameAtTime(time * 1000,
                                            android.media.MediaMetadataRetriever.OPTION_PREVIOUS_SYNC, 320, 320)
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (_: Exception) { null }
                                    decodeNs += android.os.SystemClock.elapsedRealtimeNanos() - decodeStart
                                    if (frame == null) {
                                        failedFrames++; frameFail++
                                    } else {
                                        val encodeStart = android.os.SystemClock.elapsedRealtimeNanos()
                                        val vector = try { enc.encodeImage(frame) }
                                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                                            catch (_: Exception) { null }
                                            finally { frame.recycle() }
                                        encodeNs += android.os.SystemClock.elapsedRealtimeNanos() - encodeStart
                                        if (vector == null) { failedFrames++; frameFail++ } else {
                                            store.saveFrame(p.id, time, vector)
                                            cached[time] = vector
                                            unreportedFrames++; frameCount++
                                        }
                                    }
                                }
                                if (unreportedFrames >= VIDEO_PROGRESS_FRAME_INTERVAL || time == positions.last()) {
                                    report(IndexPhase.VIDEOS, "장면 분석 ${cached.size}/${positions.size}")
                                    unreportedFrames = 0
                                }
                            }
                            val vectors = positions.mapNotNull { cached[it] }
                            require(vectors.isNotEmpty()) { "동영상에서 분석 가능한 장면을 읽지 못했습니다" }
                            val mean = Embeddings.unit(FloatArray(Embeddings.DIM) { d -> vectors.sumOf { it[d].toDouble() }.toFloat() / vectors.size })
                            // 프레임 중 하나라도 나온 라벨을 다 붙이면 영상당 9개가 된다. 투표로 합친다.
                            val tags = VideoTags.aggregate(vectors.map(clf::classify))
                            store.saveClip(p.id, mean, null, null, tags, modelId)
                            AppGraph.searchIndex.put(p.id, mean)
                            if (failedFrames > 0) android.util.Log.w("IndexWorker",
                                "영상 ${p.id}: ${positions.size - failedFrames}/${positions.size} 프레임 분석 완료")
                            videos++
                        } finally { retriever.release() }
                    }.onFailure {
                        if (it is kotlinx.coroutines.CancellationException) throw it
                        android.util.Log.e("IndexWorker", "영상 ${p.id} 분석 실패", it)
                        store.markFailed(p.id)
                    }
                }
                report(IndexPhase.VIDEOS, "영상을 분류하고 있습니다")
            }
            if (videos + frameCount > 0) {
                val battery1 = applicationContext.getSystemService(BatteryManager::class.java)
                    ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val thermal = if (Build.VERSION.SDK_INT >= 29)
                    applicationContext.getSystemService(PowerManager::class.java).currentThermalStatus else -1
                logPerf("video_clip_summary", t0,
                    "videos=$videos frames=$frameCount failed_frames=$frameFail " +
                        "decode_ms=${decodeNs / 1e6} encode_ms=${encodeNs / 1e6} " +
                        "battery=$battery0->$battery1 thermal=$thermal")
            }
            return processed
        } finally {
            enc.closeVision()
        }
    }

    // ---------- 그룹핑 ----------

    private fun regroup(withEmbeddings: Boolean, phase: IndexPhase) {
        val cfg = AppGraph.config
        val markers = cfg.getJSONArray("copy_markers").let { a -> List(a.length()) { a.getString(it) } }

        // 완전중복 후보만 해시한다. 1만 장 × 3MB를 다 읽으면 30GB다(§12.1).
        hashDupeCandidates(phase)

        val items = store.groupingItems()
        val exact = Grouping.exactGroups(items, markers)
        val groups = if (!withEmbeddings) exact else {
            // 연사 규칙은 사진에만 건다. 영상의 임베딩은 프레임 평균이고 선명도도 재지 않아서,
            // 같은 날 찍은 영상 두 개가 "비슷한 장면"으로 묶여 하나를 지우라고 제안하게 된다.
            exact + Grouping.burstGroups(
                items.filter { it.id > 0 },
                similarity = cfg.getDouble("dupe_similarity").toFloat(),
                gapSec = cfg.getLong("burst_gap_sec"),
                excluded = exact.flatMap { it.ids }.toSet(),
            )
        }
        store.replaceDupes(groups)

        store.replaceEvents(Events.build(
            store.eventItems(),
            gapHours = cfg.getInt("event_gap_hours"),
            splitKm = cfg.optDouble("location_split_km", 30.0),
        ))
    }

    /** 크기·해상도가 같은 것들만 해시한다. 보통 전체의 1~2%. */
    private fun hashDupeCandidates(phase: IndexPhase) {
        val db = store.readableDatabase
        val candidates = ArrayList<Pair<Long, String>>()
        val idFilter = if (phase == IndexPhase.PHOTOS) "media_id>0" else "media_id<0"
        db.rawQuery(
            "SELECT media_id, uri FROM photos WHERE sha256 IS NULL AND is_accessible=1 AND $idFilter AND (bytes, width, height) IN" +
                " (SELECT bytes, width, height FROM photos WHERE $idFilter AND is_accessible=1 GROUP BY bytes, width, height HAVING COUNT(*) > 1)",
            null
        ).use { c -> while (c.moveToNext()) candidates.add(c.getLong(0) to c.getString(1)) }

        for ((id, uri) in candidates) {
            runCatching {
                val md = MessageDigest.getInstance("SHA-256")
                applicationContext.contentResolver.openInputStream(android.net.Uri.parse(uri))!!.use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        md.update(buf, 0, n)
                    }
                }
                store.setSha(id, md.digest().joinToString("") { "%02x".format(it) })
            }
        }
    }

    // ---------- 제어 ----------

    /** 뜨겁거나 배터리가 낮으면 Pass 2를 멈춘다. Pass 1은 싸서 계속한다(§5.3). */
    private suspend fun pauseIfHotOrLow(phase: IndexPhase): Boolean {
        val pm = applicationContext.getSystemService(PowerManager::class.java)
        val bm = applicationContext.getSystemService(BatteryManager::class.java)
        // 사용자가 설정의 "이어서"를 눌렀으면 이번 한 번은 상태를 보지 않고 계속한다.
        if (IndexControl.consumeResume()) return false
        repeat(5) {
            val hot = Build.VERSION.SDK_INT >= 29 &&
                pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            val charging = bm?.isCharging ?: true
            if (!hot && (level >= 15 || charging)) return false
            report(phase, if (hot) "기기가 뜨거워 잠시 멈췄습니다" else "배터리가 낮아 잠시 멈췄습니다", paused = true)
            // 1분을 한 번에 자지 않는다 — "이어서"가 눌리면 바로 깬다.
            repeat(60) {
                delay(1_000)
                if (isStopped) return true
                if (IndexControl.consumeResume()) return false
            }
        }
        return true
    }

    private suspend fun report(phase: IndexPhase, msg: String, paused: Boolean = false) {
        val c = if (phase == IndexPhase.PHOTOS) store.countsPhotos() else store.countsVideos()
        val status = "${phase.label} ${c.done}/${c.total}개 · $msg"
        setProgress(
            androidx.work.workDataOf(
                KEY_TOTAL to c.total, KEY_META to c.meta, KEY_DONE to c.done,
                KEY_FAILED to c.failed, KEY_MSG to status, KEY_PHASE to phase.name, KEY_PAUSED to paused,
            )
        )
        setForeground(notice(status, c.progress, c.total))
    }

    private fun logPerf(stage: String, startNs: Long, details: String = "") {
        val isDebuggable = applicationContext.applicationInfo.flags and
            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (isDebuggable) android.util.Log.i(
            "IndexPerf", "$stage ms=${(android.os.SystemClock.elapsedRealtimeNanos() - startNs) / 1e6} $details"
        )
    }

    private fun notice(msg: String, progress: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL, "사진·영상 분석", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setContentTitle("사진 정리함")
            .setContentText(if (total > 0) "$msg · $progress%" else msg)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setProgress(100, progress, total == 0)
            .build()
        return if (Build.VERSION.SDK_INT >= 29)
            ForegroundInfo(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTIF_ID, n)
    }

    companion object {
        const val NAME = "index"
        const val CHANNEL = "index"
        const val NOTIF_ID = 42
        const val BATCH = 32                 // DB 커밋 단위. 추론 배치는 1이다(§7.5)
        const val VIDEO_PROGRESS_FRAME_INTERVAL = 4
        const val KEY_WITH_CLIP = "with_clip"
        const val KEY_TOTAL = "total"
        const val KEY_META = "meta"
        const val KEY_DONE = "done"
        const val KEY_FAILED = "failed"
        const val KEY_MSG = "msg"
        const val KEY_PHASE = "phase"
        /** 뜨겁거나 배터리가 낮아 잠시 멈춘 중인가. 설정·분석 화면이 "이어서"를 보여 준다. */
        const val KEY_PAUSED = "paused"

        /** 사용자가 시작한 첫 인덱싱. 고유 이름이라 두 번 눌러도 하나만 돈다. */
        fun start(ctx: Context, withClip: Boolean = true) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<IndexWorker>()
                    .setInputData(androidx.work.workDataOf(KEY_WITH_CLIP to withClip))
                    .build()
            )
        }

        /** 사진 몇 장이 늘었을 때. 바로 돌아야 사용자가 기다리지 않는다. */
        fun nudge(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                NAME, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<IndexWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(Constraints.NONE)
                    .build()
            )
        }

        fun restart(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(NAME)
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                NAME, ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<IndexWorker>().build()
            )
        }
    }
}

/**
 * 워커와 화면 사이의 신호 하나: 사용자가 "이어서"를 눌렀다. 워커는 잠깐 멈춘 동안 이 값을 초마다 본다.
 * 프로세스 안에서만 쓰는 값이라 WorkManager 데이터로 보내지 않는다.
 */
object IndexControl {
    @Volatile private var resume = false
    fun requestResume() { resume = true }

    /** 눌렸으면 true를 한 번만 돌려주고 지운다. */
    fun consumeResume(): Boolean = resume.also { resume = false }
}

/** 흐림·밝기. `pipeline.py`의 라플라시안 분산 + 평균을 옮긴 것. */
object Quality {
    data class Result(val blur: Double, val brightness: Double)

    /**
     * 흐림 점수는 **해상도에 의존**하므로 긴 변을 [analysisPx]로 맞춘 뒤 잰다.
     * 그러지 않으면 큰 사진이 무조건 선명하게 나와 임계값이 의미를 잃는다.
     */
    fun of(bmp: android.graphics.Bitmap, analysisPx: Int): Result {
        val s = analysisPx.toFloat() / maxOf(bmp.width, bmp.height)
        val w = if (s < 1f) (bmp.width * s).toInt().coerceAtLeast(3) else bmp.width
        val h = if (s < 1f) (bmp.height * s).toInt().coerceAtLeast(3) else bmp.height
        val small = if (w != bmp.width || h != bmp.height)
            android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true) else bmp

        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bmp) small.recycle()

        // 회색조 한 채널로 본다. 사람 눈의 가중치(0.299/0.587/0.114)를 쓴다.
        val g = FloatArray(w * h)
        var sum = 0.0
        for (i in px.indices) {
            val p = px[i]
            val v = 0.299f * (p shr 16 and 0xFF) + 0.587f * (p shr 8 and 0xFF) + 0.114f * (p and 0xFF)
            g[i] = v; sum += v
        }

        // 라플라시안 4방향. 분산이 낮으면 경계가 없다는 뜻 = 흐리다.
        var lsum = 0.0
        var lsq = 0.0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = g[i - w] + g[i + w] + g[i - 1] + g[i + 1] - 4 * g[i]
            lsum += l; lsq += l.toDouble() * l; n++
        }
        val mean = if (n > 0) lsum / n else 0.0
        return Result(
            blur = if (n > 0) lsq / n - mean * mean else 0.0,
            brightness = sum / px.size,
        )
    }
}

