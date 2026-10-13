package com.moalog.personal.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.moalog.personal.core.TimeSource
import com.moalog.personal.core.VideoMeta
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * MediaStore 동기화. 폴더를 훑는 대신 시스템이 이미 만들어 둔 색인을 읽는다(§4).
 *
 * 증분이 핵심이다. `GENERATION_MODIFIED`를 기억해 두면 1만 장 갤러리에 사진 3장이
 * 늘었을 때 3행만 조회한다 — 전체 재스캔은 배터리와 시간을 그냥 태운다.
 */
class MediaIndex(private val ctx: Context, private val store: Store) {

    data class SyncResult(
        val discovered: Int,
        val changed: Int,
        val removed: Int,
        val total: Int,
        val accessChanged: Boolean = false,
    )

    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private fun projection(video: Boolean = false): Array<String> = buildList {
        add(MediaStore.Images.Media._ID)
        add(MediaStore.Images.Media.DISPLAY_NAME)
        add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        add(MediaStore.Images.Media.RELATIVE_PATH)
        add(MediaStore.Images.Media.OWNER_PACKAGE_NAME)
        add(MediaStore.Images.Media.MIME_TYPE)
        add(MediaStore.Images.Media.SIZE)
        add(MediaStore.Images.Media.WIDTH)
        add(MediaStore.Images.Media.HEIGHT)
        if (!video) add(MediaStore.Images.Media.ORIENTATION)
        else add(MediaStore.Video.Media.DURATION)
        add(MediaStore.Images.Media.DATE_MODIFIED)
        add(MediaStore.Images.Media.DATE_ADDED)
        if (Build.VERSION.SDK_INT >= 30) add(MediaStore.Images.Media.GENERATION_MODIFIED)
    }.toTypedArray()

    /**
     * 전체 목록을 훑어 새 것/바뀐 것/사라진 것을 정리한다.
     *
     * `_ID`만 뽑는 가벼운 조회로 삭제를 감지하고(1만 개 정수, 수십 ms), 실제 메타는
     * generation이 오른 행만 읽는다. 첫 동기화는 전부 읽는다.
     */
    fun sync(): SyncResult {
        val photos = syncPhotos()
        val videos = syncVideos()
        return SyncResult(photos.discovered + videos.discovered, photos.changed + videos.changed,
            photos.removed + videos.removed, photos.total + videos.total,
            photos.accessChanged || videos.accessChanged)
    }

    fun syncPhotos(): SyncResult = sync(video = false)
    fun syncVideos(): SyncResult = sync(video = true)

    private fun sync(video: Boolean): SyncResult {
        val known = store.generations(video)
        val present = HashSet<Long>()
        val fresh = ArrayList<MediaRow>()
        val changed = ArrayList<Long>()

        // IS_TRASHED/IS_PENDING은 기본 선택에서 이미 빠진다(MediaStore가 걸러 준다).
        val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else this.collection
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            if (video) android.Manifest.permission.READ_MEDIA_VIDEO else android.Manifest.permission.READ_MEDIA_IMAGES
        } else android.Manifest.permission.READ_EXTERNAL_STORAGE
        val full = androidx.core.content.ContextCompat.checkSelfPermission(ctx, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val backfillVideoDuration = video && store.metaGet(KEY_VIDEO_DURATION_BACKFILL) == "1"
        val addedKey = if (video) KEY_ADDED_BACKFILL_VIDEOS else KEY_ADDED_BACKFILL_PHOTOS
        val backfillAdded = store.metaGet(addedKey) == "1"
        val addedBackfill = HashMap<Long, Long>()
        val partial = Build.VERSION.SDK_INT >= 34 && androidx.core.content.ContextCompat.checkSelfPermission(ctx,
            android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val scope = MediaAccessPolicy.scope(Build.VERSION.SDK_INT, full, partial)
        if (scope == MediaAccessPolicy.Scope.NONE) {
            val reconciliation = MediaAccessPolicy.reconcile(scope, emptySet())
            val accessChanged = store.setAccessible(reconciliation.visibleIds, video)
            return SyncResult(0, 0, 0, 0, accessChanged)
        }
        val columns = projection(video)
        val cursor = ctx.contentResolver.query(collection, columns, null, null, null)
            ?: error("미디어 목록을 읽지 못했습니다")
        cursor.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bucket = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val rel = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val owner = c.getColumnIndexOrThrow(MediaStore.Images.Media.OWNER_PACKAGE_NAME)
            val mime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val size = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val w = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val h = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val ori = c.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
            val duration = if (video) c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION) else -1
            val mod = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val gen = if (Build.VERSION.SDK_INT >= 30)
                c.getColumnIndex(MediaStore.Images.Media.GENERATION_MODIFIED) else -1
            val addedCol = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)

            while (c.moveToNext()) {
                val rawId = c.getLong(id)
                // Negative IDs reserve a distinct namespace for videos; existing photo IDs stay intact.
                val mid = if (video) -rawId else rawId
                present.add(mid)
                val g = if (gen >= 0 && !c.isNull(gen)) c.getLong(gen) else c.getLong(mod)
                val dateAdded = if (addedCol >= 0 && !c.isNull(addedCol)) c.getLong(addedCol) else null
                if (known[mid] == g && (!backfillVideoDuration || store.durationMs(mid) != null)) {
                    if (backfillAdded && dateAdded != null) addedBackfill[mid] = dateAdded
                    continue
                }
                if (mid in known && known[mid] != g) changed.add(mid)
                fresh.add(MediaRow(
                    id = mid,
                    uri = ContentUris.withAppendedId(collection, rawId).toString(),
                    displayName = c.getStringOrNull(name),
                    bucket = c.getStringOrNull(bucket),
                    relativePath = c.getStringOrNull(rel),
                    ownerPackage = c.getStringOrNull(owner),
                    mime = c.getStringOrNull(mime),
                    size = c.getLong(size),
                    width = c.getInt(w), height = c.getInt(h), orientation = if (ori >= 0) c.getInt(ori) else 0,
                    mediaKind = if (video) MediaKind.VIDEO else MediaKind.PHOTO,
                    durationMs = if (duration >= 0 && !c.isNull(duration)) c.getLong(duration) else if (video) 0L else null,
                    generation = g, dateModified = c.getLong(mod), dateAdded = dateAdded,
                ))
            }
        }
        val added = store.upsertDiscovered(fresh)
        // 새로 들어온 게 아니면서 generation이 오른 것 = 편집된 사진 → 처음부터 다시
        store.invalidateChanged(changed)
        if (video && full && backfillVideoDuration) store.metaPut(KEY_VIDEO_DURATION_BACKFILL, "0")
        // 추가일 채우기. 전체 권한일 때만 끝난 것으로 내린다 — 일부만 보이면 나머지 사진은 다음에 다시 봐야 한다.
        if (backfillAdded && full) {
            store.fillDateAdded(addedBackfill)
            store.metaPut(addedKey, "0")
        }
        val reconciliation = MediaAccessPolicy.reconcile(scope, present)
        val accessChanged = store.setAccessible(reconciliation.visibleIds, video)
        val removed = if (reconciliation.deleteMissing) store.deleteMissing(present, video) else 0
        fresh.maxOfOrNull { it.generation }?.let { store.metaPut(KEY_GENERATION, it.toString()) }

        return SyncResult(added, changed.size, removed, reconciliation.visibleIds.size, accessChanged)
    }

    /**
     * EXIF 헤더만 읽는다 — 전체 이미지는 디코드하지 않는다.
     *
     * **`DATE_TAKEN`을 믿지 않는다.** MediaStore 스캐너가 EXIF가 없을 때 무엇을 채우는지
     * 기기마다 다르고, 그 값을 촬영시각으로 쓰면 카톡 저장 사진이 여행 로그에 끼어든다.
     * 여기서 출처를 exif/file로 갈라 기록하고, 연사·이벤트는 exif만 쓴다(§4.5).
     */
    fun readMeta(p: Pending, fallbackSeconds: Long): MetaResult {
        if (p.mediaKind == MediaKind.VIDEO) return readVideoMeta(p.uri, fallbackSeconds)
        val uri = android.net.Uri.parse(p.uri)
        // 위치 권한이 없으면 requireOriginal URI 자체를 열 수 없다. 일반 URI의
        // 촬영시각·카메라 정보는 읽을 수 있으므로 그것까지 버리지 않는다.
        val hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(ctx,
            android.Manifest.permission.ACCESS_MEDIA_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val src = if (hasLocation) runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri) else uri
        var takenAt: Long? = null
        var camera: String? = null
        var lat: Double? = null
        var lon: Double? = null

        for (candidate in if (src == uri) listOf(uri) else listOf(src, uri)) {
            val read = runCatching {
                ctx.contentResolver.openInputStream(candidate)?.use { input ->
                    val ex = ExifInterface(input)
                    val make = ex.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                    val model = ex.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                    camera = listOfNotNull(make, model).filter { it.isNotEmpty() }
                        .joinToString(" ").ifBlank { null }

                    val raw = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: ex.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
                        ?: ex.getAttribute(ExifInterface.TAG_DATETIME)
                    if (raw != null) takenAt = parseExifTime(raw, ex.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL))

                    ex.latLong?.let { ll ->
                        // (0,0)은 GPS를 못 잡았을 때 흔히 채워지는 쓰레기값이다
                        if (ll[0] != 0.0 || ll[1] != 0.0) { lat = ll[0]; lon = ll[1] }
                    }
                    true
                } ?: false
            }.getOrDefault(false)
            if (read) break
        }

        return MetaResult(
            takenAt = takenAt ?: fallbackSeconds,
            takenAtSource = if (takenAt != null) TimeSource.EXIF else TimeSource.FILE,
            cameraModel = camera, lat = lat, lon = lon,
        )
    }

    /**
     * 영상의 촬영 시각·위치. 컨테이너 메타데이터라 사진의 EXIF와 같은 무게로 믿는다 —
     * 카메라가 녹화하면서 적은 값이기 때문이다. 없으면 파일 시각으로 떨어지고,
     * 그 사실을 `FILE`로 남겨 추억·기록 묶기가 이 값을 믿지 않게 한다.
     */
    fun readVideoMeta(uri: String, fallbackSeconds: Long): MetaResult {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, android.net.Uri.parse(uri))
            val taken = VideoMeta.parseDate(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DATE))
            val loc = VideoMeta.parseLocation(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_LOCATION))
            MetaResult(taken ?: fallbackSeconds, if (taken != null) TimeSource.EXIF else TimeSource.FILE,
                null, loc?.first, loc?.second)
        } catch (_: Exception) {
            MetaResult(fallbackSeconds, TimeSource.FILE, null, null, null)
        } finally { r.release() }
    }

    /**
     * `2026:08:24 18:12:03` + 선택적 오프셋 `+09:00`.
     * 오프셋이 있으면 정확한 시각, 없으면 기기 로컬 시간대로 해석한다 — 데스크톱과 같다.
     */
    private fun parseExifTime(raw: String, offset: String?): Long? = runCatching {
        val local = LocalDateTime.parse(raw.trim(), EXIF_FMT)
        if (offset != null) local.toEpochSecond(ZoneOffset.of(offset.trim()))
        else local.atZone(ZoneId.systemDefault()).toEpochSecond()
    }.getOrNull()

    /** 시스템 썸네일. 원본을 디코드하지 않는 이유는 §6.1. */
    /**
     * 썸네일. **열지 못하면 IOException**(일시적일 수 있다), **열었지만 디코드할 수 없으면 null**(영구)이다.
     * 예전에는 둘 다 null로 뭉쳐서, 일시적인 접근 실패가 영구히 깨진 사진으로 남았다(외부 검토 문제 5).
     */
    @Throws(java.io.IOException::class)
    fun thumbnail(uri: String, px: Int): android.graphics.Bitmap? {
        val u = android.net.Uri.parse(uri)
        // 시스템 썸네일이 있으면 그것을 쓴다. 실패 이유는 여기서 따지지 않고 아래 원본 읽기로 넘어간다.
        runCatching { ctx.contentResolver.loadThumbnail(u, android.util.Size(px, px), null) }
            .getOrNull()?.let { return it }
        // 원본을 축소 디코드한다. 스트림을 못 열면 접근 문제이므로 IOException을 그대로 올린다.
        val input = ctx.contentResolver.openInputStream(u) ?: throw java.io.IOException("열 수 없음: $uri")
        val opts = android.graphics.BitmapFactory.Options().apply {
            inPremultiplied = false
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        return input.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    }

    companion object {
        const val KEY_GENERATION = "last_generation"
        const val KEY_VIDEO_DURATION_BACKFILL = "video_duration_backfill"
        private val EXIF_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

        fun secondsOf(millis: Long) = millis / 1000
        fun nowSeconds() = Instant.now().epochSecond
    }
}

private fun android.database.Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
