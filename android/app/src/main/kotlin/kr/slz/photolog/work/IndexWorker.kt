package kr.slz.photolog.work

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
import kr.slz.photolog.AppGraph
import kr.slz.photolog.R
import kr.slz.photolog.core.Embeddings
import kr.slz.photolog.core.Events
import kr.slz.photolog.core.Grouping
import kr.slz.photolog.data.Store
import kotlinx.coroutines.delay
import java.security.MessageDigest

/**
 * 2단계 인덱싱 워커 (ANDROID.md §5).
 *
 * ```
 * SYNC → PASS1 → GROUP1(완전중복·이벤트) → PASS2 → GROUP2(연사·제목)
 * ```
 *
 * **왜 두 단계인가.** Pass 1은 장당 ~2ms(EXIF·출처·규칙태그)라 1만 장이 20초면 끝나고,
 * 그것만으로 로그와 앨범 골격이 생긴다. Pass 2는 CLIP이라 장당 50~100ms — 1만 장에
 * 15분이다. 그 15분 동안 빈 화면을 보여 줄 수 없으므로 값싼 것을 먼저 끝낸다.
 *
 * 배치마다 커밋한다. 어디서 죽어도 `state`가 남아 다음 실행이 이어 간다.
 */
class IndexWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    private val store get() = AppGraph.store
    private val media get() = AppGraph.media

    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        setForeground(notice("사진을 읽고 있습니다", 0, 0))

        return runCatching {
            // ---- SYNC ----
            media.sync()

            // ---- PASS 1: EXIF·출처·규칙태그 ----
            pass1()

            // ---- GROUP 1: 완전중복 + 이벤트 (CLIP 없이 되는 것) ----
            regroup(withEmbeddings = false)

            // ---- PASS 2: CLIP ----
            if (AppGraph.modelsReady && inputData.getBoolean(KEY_WITH_CLIP, true)) pass2()

            // ---- GROUP 2: 연사까지 포함해 다시 ----
            regroup(withEmbeddings = true)

            Result.success()
        }.getOrElse { e ->
            // 재시도가 도움이 되는 실패(일시적 I/O)와 아닌 것(모델 없음)을 구분할 방법이
            // 마땅치 않다. state가 남아 있으므로 다음 실행이 이어 가고, 무한 재시도는 피한다.
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    // ---------- Pass 1 ----------

    private suspend fun pass1() {
        val tagger = AppGraph.ruleTagger
        while (true) {
            val batch = store.pending(Store.State.NEW, BATCH)
            if (batch.isEmpty()) break
            for (p in batch) {
                runCatching {
                    // 촬영시각이 없으면 파일 수정시각으로 떨어진다. 그 사실을
                    // taken_at_source에 남겨야 연사·이벤트가 이 값을 믿지 않는다(§4.5).
                    val meta = media.readMeta(p, fallbackSeconds = p.dateModified)
                    val pm = p.toMeta(meta.takenAt, meta.takenAtSource, meta.cameraModel, meta.lat != null)
                    store.saveMeta(p.id, meta, tagger.tags(pm))
                }.onFailure { store.markFailed(p.id) }
            }
            report("촬영 정보를 정리하고 있습니다")
            if (isStopped) return
        }
    }

    // ---------- Pass 2 ----------

    private suspend fun pass2() {
        val enc = AppGraph.encoder
        val clf = AppGraph.classifier()
        val modelId = AppGraph.modelId
        store.checkModel(modelId)
        val thumbPx = AppGraph.config.optInt("thumb_px", 320)

        try {
            while (true) {
                if (pauseIfHotOrLow()) return
                val batch = store.pending(Store.State.META, BATCH)
                if (batch.isEmpty()) break
                for (p in batch) {
                    if (isStopped) return
                    runCatching {
                        val bmp = media.thumbnail(p.uri, thumbPx) ?: error("썸네일 실패")
                        // 흐림·밝기는 CLIP과 같은 비트맵에서 뽑는다 — 디코드를 두 번 하지 않는다
                        val quality = Quality.of(bmp, AppGraph.config.optInt("analysis_px", 256))
                        val v = enc.encodeImage(bmp)
                        bmp.recycle()
                        store.saveClip(p.id, v, quality.blur, quality.brightness, clf.classify(v), modelId)
                        AppGraph.searchIndex.put(p.id, v)
                    }.onFailure { store.markFailed(p.id) }
                }
                report("장면을 분류하고 있습니다")
            }
        } finally {
            // 96MB 세션을 들고 있을 이유가 없다. 검색은 텍스트 타워만 따로 연다.
            enc.closeVision()
        }
    }

    // ---------- 그룹핑 ----------

    private fun regroup(withEmbeddings: Boolean) {
        val cfg = AppGraph.config
        val markers = cfg.getJSONArray("copy_markers").let { a -> List(a.length()) { a.getString(it) } }

        // 완전중복 후보만 해시한다. 1만 장 × 3MB를 다 읽으면 30GB다(§12.1).
        hashDupeCandidates()

        val items = store.groupingItems()
        val exact = Grouping.exactGroups(items, markers)
        val groups = if (!withEmbeddings) exact else {
            exact + Grouping.burstGroups(
                items,
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
    private fun hashDupeCandidates() {
        val db = store.readableDatabase
        val candidates = ArrayList<Pair<Long, String>>()
        db.rawQuery(
            "SELECT media_id, uri FROM photos WHERE sha256 IS NULL AND (bytes, width, height) IN" +
                " (SELECT bytes, width, height FROM photos GROUP BY bytes, width, height HAVING COUNT(*) > 1)",
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
    private suspend fun pauseIfHotOrLow(): Boolean {
        val pm = applicationContext.getSystemService(PowerManager::class.java)
        val bm = applicationContext.getSystemService(BatteryManager::class.java)
        repeat(5) {
            val hot = Build.VERSION.SDK_INT >= 29 &&
                pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            val charging = bm?.isCharging ?: true
            if (!hot && (level >= 15 || charging)) return false
            report(if (hot) "기기가 뜨거워 잠시 멈췄습니다" else "배터리가 낮아 잠시 멈췄습니다")
            delay(60_000)
            if (isStopped) return true
        }
        return true
    }

    private suspend fun report(msg: String) {
        val c = store.counts()
        setProgress(
            androidx.work.workDataOf(
                KEY_TOTAL to c.total, KEY_META to c.meta, KEY_DONE to c.done,
                KEY_FAILED to c.failed, KEY_MSG to msg,
            )
        )
        setForeground(notice(msg, c.progress, c.total))
    }

    private fun notice(msg: String, progress: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL, "사진 분석", NotificationManager.IMPORTANCE_LOW))
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
        const val KEY_WITH_CLIP = "with_clip"
        const val KEY_TOTAL = "total"
        const val KEY_META = "meta"
        const val KEY_DONE = "done"
        const val KEY_FAILED = "failed"
        const val KEY_MSG = "msg"

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

