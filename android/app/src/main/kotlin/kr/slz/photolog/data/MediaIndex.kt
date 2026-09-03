package kr.slz.photolog.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kr.slz.photolog.core.TimeSource
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

    data class SyncResult(val discovered: Int, val changed: Int, val removed: Int, val total: Int)

    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private fun projection(): Array<String> = buildList {
        add(MediaStore.Images.Media._ID)
        add(MediaStore.Images.Media.DISPLAY_NAME)
        add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        add(MediaStore.Images.Media.RELATIVE_PATH)
        add(MediaStore.Images.Media.OWNER_PACKAGE_NAME)
        add(MediaStore.Images.Media.MIME_TYPE)
        add(MediaStore.Images.Media.SIZE)
        add(MediaStore.Images.Media.WIDTH)
        add(MediaStore.Images.Media.HEIGHT)
        add(MediaStore.Images.Media.ORIENTATION)
        add(MediaStore.Images.Media.DATE_MODIFIED)
        if (Build.VERSION.SDK_INT >= 30) add(MediaStore.Images.Media.GENERATION_MODIFIED)
    }.toTypedArray()

    /**
     * 전체 목록을 훑어 새 것/바뀐 것/사라진 것을 정리한다.
     *
     * `_ID`만 뽑는 가벼운 조회로 삭제를 감지하고(1만 개 정수, 수십 ms), 실제 메타는
     * generation이 오른 행만 읽는다. 첫 동기화는 전부 읽는다.
     */
    fun sync(): SyncResult {
        val lastGen = store.metaGet(KEY_GENERATION)?.toLongOrNull() ?: -1L
        val present = HashSet<Long>()
        val fresh = ArrayList<MediaRow>()
        val changed = ArrayList<Long>()

        // IS_TRASHED/IS_PENDING은 기본 선택에서 이미 빠진다(MediaStore가 걸러 준다).
        ctx.contentResolver.query(collection, projection(), null, null, null)?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val bucket = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val rel = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val owner = c.getColumnIndexOrThrow(MediaStore.Images.Media.OWNER_PACKAGE_NAME)
            val mime = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val size = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val w = c.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val h = c.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val ori = c.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
            val mod = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val gen = if (Build.VERSION.SDK_INT >= 30)
                c.getColumnIndex(MediaStore.Images.Media.GENERATION_MODIFIED) else -1

            while (c.moveToNext()) {
                val mid = c.getLong(id)
                present.add(mid)
                val g = if (gen >= 0 && !c.isNull(gen)) c.getLong(gen) else c.getLong(mod)
                if (g <= lastGen) continue          // 안 바뀐 사진은 건너뛴다
                fresh.add(MediaRow(
                    id = mid,
                    uri = ContentUris.withAppendedId(collection, mid).toString(),
                    displayName = c.getStringOrNull(name),
                    bucket = c.getStringOrNull(bucket),
                    relativePath = c.getStringOrNull(rel),
                    ownerPackage = c.getStringOrNull(owner),
                    mime = c.getStringOrNull(mime),
                    size = c.getLong(size),
                    width = c.getInt(w), height = c.getInt(h), orientation = c.getInt(ori),
                    generation = g, dateModified = c.getLong(mod),
                ))
            }
        }

        val added = store.upsertDiscovered(fresh)
        // 새로 들어온 게 아니면서 generation이 오른 것 = 편집된 사진 → 처음부터 다시
        if (fresh.size > added) {
            val addedIds = fresh.takeLast(added).map { it.id }.toSet()
            changed.addAll(fresh.map { it.id }.filter { it !in addedIds })
            store.invalidateChanged(changed)
        }
        val removed = store.deleteMissing(present)
        fresh.maxOfOrNull { it.generation }?.let { store.metaPut(KEY_GENERATION, it.toString()) }

        return SyncResult(added, changed.size, removed, present.size)
    }

    /**
     * EXIF 헤더만 읽는다 — 전체 디코드 없이 ~1ms.
     *
     * **`DATE_TAKEN`을 믿지 않는다.** MediaStore 스캐너가 EXIF가 없을 때 무엇을 채우는지
     * 기기마다 다르고, 그 값을 촬영시각으로 쓰면 카톡 저장 사진이 여행 로그에 끼어든다.
     * 여기서 출처를 exif/file로 갈라 기록하고, 연사·이벤트는 exif만 쓴다(§4.5).
     */
    fun readMeta(p: Pending, fallbackSeconds: Long): MetaResult {
        val uri = android.net.Uri.parse(p.uri)
        // ACCESS_MEDIA_LOCATION이 있어야 원본 EXIF의 GPS가 지워지지 않고 온다.
        val src = runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
        var takenAt: Long? = null
        var camera: String? = null
        var lat: Double? = null
        var lon: Double? = null

        runCatching {
            ctx.contentResolver.openInputStream(src)?.use { input ->
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
            }
        }

        return MetaResult(
            takenAt = takenAt ?: fallbackSeconds,
            takenAtSource = if (takenAt != null) TimeSource.EXIF else TimeSource.FILE,
            cameraModel = camera, lat = lat, lon = lon,
        )
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
    fun thumbnail(uri: String, px: Int): android.graphics.Bitmap? = runCatching {
        ctx.contentResolver.loadThumbnail(android.net.Uri.parse(uri), android.util.Size(px, px), null)
    }.recoverCatching {
        // 일부 포맷·손상 파일은 loadThumbnail이 실패한다. 축소 디코드로 폴백.
        ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { input ->
            val opts = android.graphics.BitmapFactory.Options().apply {
                inPremultiplied = false
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            android.graphics.BitmapFactory.decodeStream(input, null, opts)
        }
    }.getOrNull()

    companion object {
        const val KEY_GENERATION = "last_generation"
        private val EXIF_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

        fun secondsOf(millis: Long) = millis / 1000
        fun nowSeconds() = Instant.now().epochSecond
    }
}

private fun android.database.Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
