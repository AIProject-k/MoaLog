package kr.slz.photolog.data

import kr.slz.photolog.core.PhotoMeta
import kr.slz.photolog.core.Tag
import kr.slz.photolog.core.TimeSource

/** MediaStore에서 읽은 한 행. CLIP 이전에 알 수 있는 것 전부(§4.1). */
data class MediaRow(
    val id: Long,
    val uri: String,
    val displayName: String?,
    val bucket: String?,
    val relativePath: String?,
    val ownerPackage: String?,
    val mime: String?,
    val size: Long,
    val width: Int,
    val height: Int,
    val orientation: Int,
    val generation: Long,
    val dateModified: Long,
)

/** 파이프라인이 다음에 처리할 사진. */
data class Pending(
    val id: Long,
    val uri: String,
    val displayName: String?,
    val relativePath: String?,
    val ownerPackage: String?,
    val mime: String?,
    val bytes: Long,
    val width: Int,
    val height: Int,
    /** 파일 수정시각(초). EXIF 촬영시각이 없을 때의 폴백 — 데스크톱의 mtime과 같다. */
    val dateModified: Long,
) {
    /** 규칙 태그·출처 판정에 넘길 형태. `:core`는 안드로이드를 모른다. */
    fun toMeta(takenAt: Long?, source: TimeSource, cameraModel: String?, hasGps: Boolean) = PhotoMeta(
        width = width, height = height, cameraModel = cameraModel,
        takenAt = takenAt, takenAtSource = source, hasGps = hasGps,
        relativePath = relativePath, ownerPackage = ownerPackage,
        isDownload = relativePath?.lowercase()?.startsWith("download") == true,
    )
}

/** Pass 1이 뽑아낸 것. */
data class MetaResult(
    val takenAt: Long?,
    val takenAtSource: TimeSource,
    val cameraModel: String?,
    val lat: Double?,
    val lon: Double?,
)

data class Counts(val total: Int, val meta: Int, val done: Int, val failed: Int) {
    /**
     * 진행률. Pass 1은 싸고 Pass 2가 대부분이라 가중치를 준다 — 균등하게 세면
     * 처음에 훅 올라갔다가 멈춘 것처럼 보인다. 실제 비용 비율(2ms : 50~100ms)에 맞췄다.
     */
    val progress: Int get() = if (total == 0) 0 else
        ((meta * 0.15 + done * 0.85) / total * 100).toInt().coerceIn(0, 100)
}

data class TagCount(val label: String, val source: String, val count: Int)

data class PhotoCard(
    val id: Long,
    val uri: String,
    val takenAt: Long?,
    val takenAtSource: TimeSource,
)

data class PhotoDetail(
    val id: Long,
    val uri: String,
    val displayName: String?,
    val takenAt: Long?,
    val takenAtSource: TimeSource,
    val lat: Double?,
    val lon: Double?,
    val cameraModel: String?,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val blurScore: Double?,
    val brightness: Double?,
    val mime: String?,
    val relativePath: String?,
    val ownerPackage: String?,
    val tags: List<Tag>,
)

data class EventRow(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long,
    val placeName: String?,
    val title: String,
    val count: Int,
    val coverIds: List<Long>,
)
