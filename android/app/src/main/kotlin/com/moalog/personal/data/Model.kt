package com.moalog.personal.data

import com.moalog.personal.core.PhotoMeta
import com.moalog.personal.core.Tag
import com.moalog.personal.core.TimeSource

enum class MediaKind(val value: String) {
    PHOTO("photo"), VIDEO("video");

    companion object {
        fun from(value: String?, id: Long) = entries.firstOrNull { it.value == value }
            ?: if (id < 0) VIDEO else PHOTO
    }
}

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
    val mediaKind: MediaKind = if (id < 0) MediaKind.VIDEO else MediaKind.PHOTO,
    val durationMs: Long? = null,
    /** MediaStore가 이 사진을 처음 본 시각(초). 추가일순 정렬에 쓴다. */
    val dateAdded: Long? = null,
)

/**
 * 사진 탭 정렬. 촬영일순만 날짜 머리글과 월 이동을 쓴다. 추가일·용량순은 날짜와 무관하게 한 덩어리로 보인다.
 * 어느 순서든 같은 값끼리는 `media_id`로 고정해, 쪽을 넘길 때 사진이 두 번 나오거나 빠지지 않게 한다.
 */
enum class PhotoSort(val label: String, val orderSql: String) {
    DATE("촬영일순", "taken_at IS NULL, taken_at DESC, media_id DESC"),
    ADDED("추가일순", "date_added IS NULL, date_added DESC, media_id DESC"),
    SIZE("용량순", "bytes IS NULL, bytes DESC, media_id DESC"),
}

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
    val mediaKind: MediaKind = if (id < 0) MediaKind.VIDEO else MediaKind.PHOTO,
    val durationMs: Long? = null,
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

/** @param videos 그 가운데 영상 수. 앨범 카드에 "영상 N"을 보이려고 함께 센다. */
data class TagCount(val label: String, val source: String, val count: Int, val videos: Int = 0)

data class PhotoCard(
    val id: Long,
    val uri: String,
    val takenAt: Long?,
    val takenAtSource: TimeSource,
    val mediaKind: MediaKind = if (id < 0) MediaKind.VIDEO else MediaKind.PHOTO,
    val durationMs: Long? = null,
    val sceneTimeMs: Long? = null,
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
    val mediaKind: MediaKind = if (id < 0) MediaKind.VIDEO else MediaKind.PHOTO,
    val durationMs: Long? = null,
)

data class EventRow(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long,
    val placeName: String?,
    val title: String,
    val count: Int,
    val coverIds: List<Long>,
    val videos: Int = 0,
)

/** 기기 폴더 하나. 카메라·스크린샷처럼 **실제 저장 위치**를 뜻한다. */
data class BucketRow(val name: String, val count: Int, val coverIds: List<Long>, val videos: Int = 0)

/** 사용자가 직접 만든 앨범. */
data class UserAlbumRow(val id: Long, val name: String, val count: Int, val coverIds: List<Long>, val videos: Int = 0)

/** 태그 한 행(점수·출처 포함). 되돌리기가 지운 태그를 **원래 모습 그대로** 복원하려고 쓴다. */
data class TagRow(val id: Long, val label: String, val score: Float?, val source: String)

/** 파일 하나의 저장 위치(기기 폴더)와 크기. 정리 화면의 "Camera · 18MB"가 쓴다. */
data class FileInfo(val bucket: String?, val bytes: Long)

/** 개수와 그 가운데 영상 수. 즐겨찾기처럼 표가 따로인 묶음이 쓴다. */
data class KindCount(val count: Int, val videos: Int)

/** 중복 묶음에 대한 사용자의 결정. `keepId`는 보관 추천을 바꾼 것, `dismissed`는 "이 묶음은 유지". */
data class DupeChoice(val keepId: Long?, val dismissed: Boolean)

/** 월 하나. `offset`은 날짜순 전체 목록에서 이 달이 시작하는 자리다. */
data class MonthRow(val ym: String, val count: Int, val offset: Int)
