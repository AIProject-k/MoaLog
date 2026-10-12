package com.moalog.personal.core

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** 위치가 있는 사진·영상 하나. */
data class GeoPhoto(val id: Long, val lat: Double, val lon: Double)

/**
 * 지도에서 한 덩어리로 보이는 사진들. `lat`·`lon`은 덩어리의 평균 위치이고, 나머지 넷은 덩어리를
 * 감싸는 상자다 — 눌렀을 때 그 상자에 맞춰 확대한다.
 */
class GeoCluster(
    val lat: Double,
    val lon: Double,
    val ids: List<Long>,
    val latMin: Double,
    val latMax: Double,
    val lonMin: Double,
    val lonMax: Double,
) {
    val size get() = ids.size

    /** 더 확대해도 갈라지지 않을 만큼 한 점에 모여 있는가(약 10m 이내). */
    val atOnePlace get() = latMax - latMin < 1e-4 && lonMax - lonMin < 1e-4
}

/**
 * 지도 확대 수준에 맞춘 격자 묶기. 화면 픽셀 기준으로 `cellPx`짜리 칸에 든 사진을 한 덩어리로 본다.
 * 확대하면 칸이 실제 거리로는 작아지므로 덩어리가 저절로 갈라진다.
 *
 * 순수 함수다 — 지도 라이브러리를 몰라서 기기 없이 시험한다. 격자는 월드 픽셀(웹 메르카토르)에 걸리므로
 * 지도를 옮겨도 덩어리가 바뀌지 않고 **확대 수준이 바뀔 때만** 다시 계산하면 된다.
 *
 * @param worldPx 지금 확대 수준에서 세계 지도 한 변의 픽셀 수(256 × 2^zoom)
 */
object GeoClusters {

    fun cluster(points: List<GeoPhoto>, worldPx: Double, cellPx: Double): List<GeoCluster> {
        val cells = LinkedHashMap<Long, MutableList<GeoPhoto>>()
        for (p in points) {
            val cx = floor(x(p.lon, worldPx) / cellPx).toLong()
            val cy = floor(y(p.lat, worldPx) / cellPx).toLong()
            cells.getOrPut(cx * 1_000_003L + cy) { ArrayList() }.add(p)
        }
        return cells.values.map { g ->
            GeoCluster(
                lat = g.sumOf { it.lat } / g.size, lon = g.sumOf { it.lon } / g.size,
                ids = g.map { it.id },
                latMin = g.minOf { it.lat }, latMax = g.maxOf { it.lat },
                lonMin = g.minOf { it.lon }, lonMax = g.maxOf { it.lon },
            )
        }
    }

    /**
     * 이 상자가 화면(`viewW`×`viewH` px)에 여백 `padPx`를 두고 다 들어오는 가장 큰 확대 수준.
     * 지도 라이브러리의 "상자에 맞춤"은 여백을 믿기 어려워서(가장자리에 마커가 걸린다) 직접 계산한다.
     *
     * @param tileSize 확대 수준 0에서 세계 지도 한 변의 픽셀 수
     */
    fun fitZoom(
        latMin: Double, latMax: Double, lonMin: Double, lonMax: Double,
        viewW: Double, viewH: Double, padPx: Double, tileSize: Double,
        minZoom: Double, maxZoom: Double,
    ): Double {
        val fx = (lonMax - lonMin) / 360.0
        val fy = y(latMin, 1.0) - y(latMax, 1.0)
        fun zoomFor(avail: Double, fraction: Double) =
            if (fraction <= 0.0) maxZoom else ln(max(avail, 1.0) / (fraction * tileSize)) / LN2
        return min(zoomFor(viewW - 2 * padPx, fx), zoomFor(viewH - 2 * padPx, fy)).coerceIn(minZoom, maxZoom)
    }

    private fun x(lon: Double, world: Double) = (lon + 180.0) / 360.0 * world

    /** 위도는 메르카토르가 닿는 ±85.05°로 자른다. 그 밖은 지도에 없다. */
    private fun y(lat: Double, world: Double): Double {
        val s = sin(lat.coerceIn(-MAX_LAT, MAX_LAT) * PI / 180.0)
        return (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * world
    }

    private const val MAX_LAT = 85.0511
    private val LN2 = ln(2.0)
}
