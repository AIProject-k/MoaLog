package com.moalog.personal.ui

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.pxOrElse

/**
 * 영상 썸네일을 Coil로 읽는다.
 *
 * 예전엔 그리드 칸마다 `produceState`로 직접 `loadThumbnail`을 불렀다. 그러면 캐시가 없어서
 * 위아래로 스크롤할 때마다 같은 영상을 다시 디코드했고, 길이가 비면 칸마다 DB 대신
 * MediaStore를 또 조회했다. Coil에 맡기면 메모리 캐시·취소·크기 조절을 사진과 똑같이 받는다.
 *
 * `coil-video`를 쓰지 않는 이유: 빌드가 오프라인이고, 필요한 건 시스템 썸네일과
 * 지정 시점 프레임 두 가지뿐이라 이 파일 하나로 충분하다.
 */
fun videoImageLoader(ctx: Context): ImageLoader = ImageLoader.Builder(ctx)
    .components {
        add(VideoThumbFetcher.Factory())
        add(SceneFrameFetcher.Factory())
        add(SceneFrameKeyer())
    }
    .build()

/** 검색에서 맞은 장면의 한 프레임. 영상 첫 장면이 아니라 **맞은 그 장면**을 보여 준다. */
data class SceneFrame(val uri: String, val timeMs: Long)

private fun isVideoUri(uri: Uri) = uri.scheme == "content" && uri.pathSegments.contains("video")

/** MediaStore가 만들어 둔 영상 썸네일. 원본을 디코드하지 않는다. */
private class VideoThumbFetcher(private val uri: Uri, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val px = options.size.width.pxOrElse { 320 }
        val bmp = options.context.contentResolver.loadThumbnail(uri, Size(px, px), null)
        return DrawableResult(BitmapDrawable(options.context.resources, bmp), isSampled = true,
            dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? =
            if (isVideoUri(data)) VideoThumbFetcher(data, options) else null
    }
}

/**
 * 지정 시점 프레임. 색인 때와 **같은 방식**(직전 키프레임, [com.moalog.personal.core.VideoSampling.FRAME_MODE])으로
 * 뽑는다 — 결과 카드에 뜨는 그 프레임이 CLIP이 본 프레임이고, 누르면 재생이 시작되는 장면이다.
 */
private class SceneFrameFetcher(private val frame: SceneFrame, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val px = options.size.width.pxOrElse { 320 }
        val r = MediaMetadataRetriever()
        val bmp = try {
            r.setDataSource(options.context, Uri.parse(frame.uri))
            r.getScaledFrameAtTime(frame.timeMs * 1000, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC, px, px)
        } finally { r.release() } ?: error("프레임을 읽지 못했습니다")
        return DrawableResult(BitmapDrawable(options.context.resources, bmp), isSampled = true,
            dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<SceneFrame> {
        override fun create(data: SceneFrame, options: Options, imageLoader: ImageLoader): Fetcher =
            SceneFrameFetcher(data, options)
    }
}

/** 같은 영상의 다른 장면이 같은 캐시 칸을 쓰지 않도록 시각까지 열쇠에 넣는다. */
private class SceneFrameKeyer : Keyer<SceneFrame> {
    override fun key(data: SceneFrame, options: Options) = "${data.uri}#t=${data.timeMs}"
}
