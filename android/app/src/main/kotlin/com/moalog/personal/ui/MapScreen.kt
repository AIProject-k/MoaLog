package com.moalog.personal.ui

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Point
import android.net.Uri
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moalog.personal.core.GeoCluster
import com.moalog.personal.core.GeoClusters
import com.moalog.personal.core.GeoPhoto
import com.moalog.personal.data.MediaKind
import com.moalog.personal.data.PhotoCard
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 지도 탭. 위치가 있는 사진·영상을 지도에 모아 보여 준다.
 *
 * **이 앱에서 네트워크를 쓰는 유일한 화면이다.** 지도 조각(타일)을 OpenStreetMap에서 받는다. 사진·영상·위치는
 * 보내지 않지만, 보고 있는 지도 영역은 타일 요청으로 지도 서버에 드러난다 — 설정 화면과 README가 그렇게 적는다.
 * 다른 탭은 이 화면을 열지 않으면 네트워크에 닿지 않는다.
 *
 * 덩어리는 [GeoClusters]가 확대 수준마다 묶는다. 덩어리를 누르면: 더 갈라질 수 있으면 그 사진들이 다 보이게
 * 확대하고, 더는 갈라지지 않으면(같은 장소·최대 확대) 아래 띠에 사진을 올린다. 사진을 누르면 뷰어가 열리고,
 * 닫으면 이 화면의 보던 자리로 돌아온다.
 */
@Composable
fun MapScreen(vm: AppViewModel, ui: AppViewModel.Ui) {
    val t = theme
    val ctx = LocalContext.current
    val points = ui.mapPoints
    Column(Modifier.fillMaxSize()) {
        TopRow(height = 56.dp) {
            TitleBlock(
                "지도",
                if (ui.mapLoaded && points.isNotEmpty()) "위치가 있는 사진·영상 ${points.size}개" else null,
                Modifier.weight(1f).padding(start = 16.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !ui.mapLoaded -> Box(Modifier.fillMaxSize(), Alignment.Center) { Spinner() }
                points.isEmpty() -> EmptyState(
                    icon = I.Pin,
                    title = "위치가 있는 사진이 없습니다",
                    description = if (ui.limitedAccess) "일부 사진만 허용한 상태에서는 시스템이 위치 정보를 지워서 줍니다. 전체 허용으로 바꾸면 보입니다."
                    else "카메라에서 위치 저장을 켜고 찍은 사진·영상이 여기에 모입니다. 위치 정보 접근을 허용하지 않았다면 설정에서 허용하세요.",
                )
                else -> {
                    GeoMap(
                        points = points,
                        selected = remember(ui.mapSelection) { ui.mapSelection.mapTo(HashSet()) { it.id } },
                        onPick = vm::selectMapPhotos,
                        onEmptyTap = vm::clearMapSelection,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Text(
                        "© OpenStreetMap contributors",
                        style = fonts.micro, color = t.text2,
                        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                            .clip(RoundedCornerShape(6.dp)).background(t.scrim)
                            .tap({ ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/copyright"))) },
                                "지도 저작권 안내 열기")
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    if (ui.mapSelection.isNotEmpty()) PhotoStrip(
                        cards = ui.mapSelection,
                        onOpen = { id -> vm.openViewer(ui.mapSelection.map { it.id }, id) },
                        onClose = vm::clearMapSelection,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 지도

private const val MIN_ZOOM = 1.0
private const val MAX_ZOOM = 19.0

/** 이 확대 수준부터는 덩어리를 더 갈라 보려고 확대하지 않고 바로 사진을 올린다. */
private const val STOP_SPLITTING_ZOOM = 18.5

@Composable
private fun GeoMap(
    points: List<GeoPhoto>,
    selected: Set<Long>,
    onPick: (List<Long>) -> Unit,
    onEmptyTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val t = theme

    // 화면을 떠났다 돌아와도 보던 곳을 지킨다(MainActivity의 keepPlace가 저장해 준다).
    var camLat by rememberSaveable { mutableDoubleStateOf(Double.NaN) }
    var camLon by rememberSaveable { mutableDoubleStateOf(Double.NaN) }
    var camZoom by rememberSaveable { mutableDoubleStateOf(Double.NaN) }

    val mapView = remember { newMapView(ctx) }
    /** 카메라를 처음 맞춘 뒤부터 true. 그 전의 지도 이벤트로는 카메라를 저장하지 않는다. */
    val ready = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val overlay = remember { ClusterOverlay(density, t.accent.toArgb(), t.onAccent.toArgb(), t.text1.toArgb()) }
    val pointsNow by rememberUpdatedState(points)
    val selectedNow by rememberUpdatedState(selected)
    val pick by rememberUpdatedState(onPick)
    val emptyTap by rememberUpdatedState(onEmptyTap)

    fun recluster() {
        val world = TileSystem.MapSize(mapView.zoomLevelDouble)
        overlay.show(GeoClusters.cluster(pointsNow, world, (CELL_DP * density).toDouble()), selectedNow)
        mapView.invalidate()
    }

    DisposableEffect(mapView) {
        overlay.onTap = { c ->
            if (c == null) emptyTap()
            else if (c.size > 1 && !c.atOnePlace && mapView.zoomLevelDouble < STOP_SPLITTING_ZOOM) {
                // 갈라질 수 있다 — 이 사진들이 한 화면에 다 보이게 확대한다. 선택 띠는 닫는다.
                emptyTap()
                fitBox(mapView, BoundingBox(c.latMax, c.lonMax, c.latMin, c.lonMin), (BORDER_DP * density).toInt(), animated = true, maxZoom = MAX_ZOOM)
            } else {
                // 아래 띠가 올라와도 눌린 덩어리가 가려지지 않게 지도 한가운데로 옮긴다.
                mapView.controller.animateTo(GeoPoint(c.lat, c.lon))
                pick(c.ids)
            }
        }
        mapView.overlays.add(overlay)
        var bucket = Int.MIN_VALUE
        mapView.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { saveCamera(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean {
                saveCamera()
                // 덩어리는 확대 수준이 반 단계 이상 바뀔 때만 다시 만든다 — 손가락으로 모으는 동안 매 프레임 계산하지 않는다.
                val b = (mapView.zoomLevelDouble * 2).toInt()
                if (b != bucket) { bucket = b; recluster() }
                return false
            }
            private fun saveCamera() {
                // 처음 카메라를 맞추는 중에는 중심이 아직 (0,0)이라 그 값을 저장하면 다음에 엉뚱한 곳으로 돌아간다.
                if (!ready.get()) return
                val c = mapView.mapCenter
                camLat = c.latitude; camLon = c.longitude; camZoom = mapView.zoomLevelDouble
            }
        })
        onDispose { mapView.onDetach() }
    }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, mapView) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    // 처음 한 번 카메라를 맞춘다: 저장해 둔 자리가 있으면 거기, 없으면 모든 점이 보이게.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(points) {
        if (placed) { recluster(); return@LaunchedEffect }
        placed = true
        val place = {
            if (!camZoom.isNaN()) {
                mapView.controller.setZoom(camZoom)
                mapView.controller.setCenter(GeoPoint(camLat, camLon))
            } else fitAll(mapView, points, (BORDER_DP * density).toInt())
            ready.set(true)
            recluster()
        }
        // 레이아웃이 끝난 다음 프레임에 맞춘다. 레이아웃 도중에 중심을 정하면 (0,0)에 머무는 일이 있다.
        val placeLater = { mapView.post { place() } }
        if (mapView.width > 0) placeLater() else mapView.addOnFirstLayoutListener { _, _, _, _, _ -> placeLater() }
    }
    // 선택이 바뀌면 덩어리의 강조만 다시 그린다.
    LaunchedEffect(selected) { if (placed && mapView.width > 0) recluster() }

    // 지도 뷰는 자기 영역 밖(머리글)까지 칠하는 일이 있어서 잘라 둔다.
    AndroidView(factory = { mapView }, modifier = modifier.clipToBounds())
}

private const val CELL_DP = 56f
private const val BORDER_DP = 64f

/** 상자가 여백을 두고 보이게 카메라를 옮긴다. `animated`면 부드럽게 간다. */
private fun fitBox(map: MapView, box: BoundingBox, borderPx: Int, animated: Boolean, maxZoom: Double = 17.0) {
    val zoom = GeoClusters.fitZoom(
        box.latSouth, box.latNorth, box.lonWest, box.lonEast,
        map.width.toDouble(), map.height.toDouble(), borderPx.toDouble(),
        TileSystem.MapSize(0.0), MIN_ZOOM, maxZoom,
    )
    val center = GeoPoint(box.centerLatitude, box.centerLongitude)
    if (animated) map.controller.animateTo(center, zoom, 450L)
    else { map.controller.setZoom(zoom); map.controller.setCenter(center) }
}

private fun fitAll(map: MapView, points: List<GeoPhoto>, border: Int) = fitBox(
    map,
    BoundingBox(points.maxOf { it.lat }, points.maxOf { it.lon }, points.minOf { it.lat }, points.minOf { it.lon }),
    border, animated = false, maxZoom = 15.0,
)

private fun newMapView(ctx: Context): MapView {
    // 타일 캐시는 앱 캐시 폴더에 둔다. 기본값은 외부 저장소를 건드리려 한다.
    Configuration.getInstance().apply {
        val base = File(ctx.cacheDir, "osmdroid")
        osmdroidBasePath = base
        osmdroidTileCache = File(base, "tiles")
        // OSM 타일 사용 정책: 앱을 알아볼 수 있는 User-Agent가 있어야 한다.
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        userAgentValue = "MoaLog/${version ?: "0"} (${ctx.packageName})"
        tileFileSystemCacheMaxBytes = 120L * 1024 * 1024
        tileFileSystemCacheTrimBytes = 90L * 1024 * 1024
    }
    return MapView(ctx).apply {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        minZoomLevel = MIN_ZOOM
        maxZoomLevel = MAX_ZOOM
        isTilesScaledToDpi = true
        // 앱이 어두운 화면이라 밝은 지도가 눈부시다. 색을 뒤집고 색상만 되돌려 물은 파랗게, 땅은 어둡게 둔다.
        overlayManager.tilesOverlay.setColorFilter(darkTiles())
    }
}

/** CSS의 `invert(1) hue-rotate(180deg)`와 같다. 밝기를 조금 눌러 앱 배경과 어울리게 한다. */
private fun darkTiles(): ColorMatrixColorFilter {
    val invert = ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    ))
    val hue180 = ColorMatrix(floatArrayOf(
        -0.574f, 1.430f, 0.144f, 0f, 0f,
        0.426f, 0.430f, 0.144f, 0f, 0f,
        0.426f, 1.430f, -0.856f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    ))
    val dim = ColorMatrix().apply { setScale(0.85f, 0.85f, 0.85f, 1f) }
    // 뒤집으면 초록·분홍이 너무 쨍해서 채도를 낮춘다. 마커(accent)가 지도 위에서 가장 눈에 띄어야 한다.
    val calm = ColorMatrix().apply { setSaturation(0.55f) }
    // 적용 순서: 뒤집기 → 색상 되돌리기 → 채도 → 어둡게. postConcat은 뒤에 이어 붙인다.
    return ColorMatrixColorFilter(ColorMatrix(invert).apply { postConcat(hue180); postConcat(calm); postConcat(dim) })
}

/** 덩어리를 원으로 그리는 오버레이. 한 장이면 점, 여럿이면 장수가 적힌 원이다. */
private class ClusterOverlay(
    private val density: Float,
    accent: Int,
    private val onAccent: Int,
    private val ringColor: Int,
) : Overlay() {
    private class Drawn(val cluster: GeoCluster, val selected: Boolean)

    private var drawn: List<Drawn> = emptyList()
    var onTap: (GeoCluster?) -> Unit = {}

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ringColor }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onAccent; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val scratch = Point()

    fun show(clusters: List<GeoCluster>, selected: Set<Long>) {
        // 큰 덩어리를 먼저 그려 작은 점이 가려지지 않게 한다.
        drawn = clusters.sortedByDescending { it.size }.map { c -> Drawn(c, c.ids.any { it in selected }) }
    }

    private fun radius(n: Int): Float = (if (n == 1) 7f else min(14f + sqrt(n.toFloat()) * 1.2f, 26f)) * density

    override fun draw(c: Canvas, mapView: MapView, shadow: Boolean) {
        if (shadow) return
        val proj = mapView.projection
        for (d in drawn) {
            proj.toPixels(GeoPoint(d.cluster.lat, d.cluster.lon), scratch)
            val r = radius(d.cluster.size)
            ring.strokeWidth = (if (d.selected) 3.5f else 2f) * density
            c.drawCircle(scratch.x.toFloat(), scratch.y.toFloat(), r, fill)
            c.drawCircle(scratch.x.toFloat(), scratch.y.toFloat(), r, ring)
            if (d.cluster.size > 1) {
                text.textSize = (if (d.cluster.size >= 1000) 11f else 13f) * density
                c.drawText(countLabel(d.cluster.size), scratch.x.toFloat(), scratch.y + text.textSize * 0.35f, text)
            }
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val proj = mapView.projection
        var best: Drawn? = null
        var bestDist = Float.MAX_VALUE
        for (d in drawn) {
            proj.toPixels(GeoPoint(d.cluster.lat, d.cluster.lon), scratch)
            val dist = hypot(e.x - scratch.x, e.y - scratch.y)
            // 한 장짜리 점은 작아서 손가락이 빗나가기 쉽다 — 눌림 반경을 넉넉히 준다.
            if (dist <= max(radius(d.cluster.size), 22f * density) && dist < bestDist) { best = d; bestDist = dist }
        }
        onTap(best?.cluster)
        return best != null
    }

    private fun countLabel(n: Int) = if (n >= 1000) "${n / 1000}.${n % 1000 / 100}k" else n.toString()
}

// ---------------------------------------------------------------- 아래 띠

@Composable
private fun PhotoStrip(
    cards: List<PhotoCard>,
    onOpen: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = theme
    val videos = cards.count { it.mediaKind == MediaKind.VIDEO }
    val photos = cards.size - videos
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(t.surface1),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    buildString {
                        if (photos > 0) append("사진 ${photos}장")
                        if (photos > 0 && videos > 0) append(" · ")
                        if (videos > 0) append("영상 ${videos}개")
                    },
                    style = fonts.item, color = t.text1,
                )
                dateRange(cards)?.let { Text(it, style = fonts.meta, color = t.text2) }
            }
            IconBtn(I.Close, "닫기", onClose, tint = t.text2, size = 20.dp)
        }
        LazyRow(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(cards, key = { it.id }) { c ->
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).tap({ onOpen(c.id) }, if (c.mediaKind == MediaKind.VIDEO) "영상 열기" else "사진 열기")) {
                    PlainThumb(c.uri, Modifier.fillMaxSize(), px = 288)
                    if (c.mediaKind == MediaKind.VIDEO) Ic(
                        I.Play, Modifier.align(Alignment.BottomStart).padding(6.dp), size = 16.dp, tint = t.text1,
                    )
                }
            }
        }
    }
}

/** "2024.05.03 – 2025.01.10". 날짜가 없으면 null, 하루면 한 날짜만. */
private fun dateRange(cards: List<PhotoCard>): String? {
    val times = cards.mapNotNull { it.takenAt }
    if (times.isEmpty()) return null
    val zone = ZoneId.systemDefault()
    fun fmt(sec: Long) = Instant.ofEpochSecond(sec).atZone(zone).toLocalDate().let { "%d.%02d.%02d".format(it.year, it.monthValue, it.dayOfMonth) }
    val a = fmt(times.min()); val b = fmt(times.max())
    return if (a == b) a else "$a – $b"
}
