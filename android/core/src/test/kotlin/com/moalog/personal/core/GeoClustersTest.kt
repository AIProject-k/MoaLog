package com.moalog.personal.core

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoClustersTest {

    private fun world(zoom: Int) = 256.0 * 2.0.pow(zoom)

    // 서울 시청 근처 세 곳과 부산
    private val seoul1 = GeoPhoto(1, 37.5663, 126.9779)
    private val seoul2 = GeoPhoto(2, 37.5665, 126.9781)
    private val seoul3 = GeoPhoto(3, 37.5700, 126.9820)
    private val busan = GeoPhoto(4, 35.1796, 129.0756)

    @Test fun `멀리서 보면 가까운 사진이 한 덩어리가 된다`() {
        val out = GeoClusters.cluster(listOf(seoul1, seoul2, seoul3, busan), world(5), 64.0)
        assertEquals(2, out.size, "서울 셋과 부산 하나")
        assertEquals(setOf(1L, 2L, 3L), out.first { it.size == 3 }.ids.toSet())
    }

    @Test fun `확대하면 덩어리가 갈라진다`() {
        val far = GeoClusters.cluster(listOf(seoul1, seoul2, seoul3), world(10), 64.0).size
        val near = GeoClusters.cluster(listOf(seoul1, seoul2, seoul3), world(17), 64.0).size
        assertTrue(near > far, "확대했는데 덩어리가 그대로다 ($far → $near)")
        assertEquals(3, near)
    }

    @Test fun `모든 사진이 정확히 한 덩어리에 들어간다`() {
        val pts = List(500) { GeoPhoto(it.toLong(), 33.0 + it % 50 * 0.1, 126.0 + it % 37 * 0.1) }
        val out = GeoClusters.cluster(pts, world(7), 64.0)
        assertEquals(pts.map { it.id }.sorted(), out.flatMap { it.ids }.sorted())
    }

    @Test fun `덩어리의 상자가 안의 사진을 모두 감싼다`() {
        val c = GeoClusters.cluster(listOf(seoul1, seoul2, seoul3), world(4), 64.0).single()
        assertEquals(37.5663, c.latMin, 1e-9); assertEquals(37.5700, c.latMax, 1e-9)
        assertEquals(126.9779, c.lonMin, 1e-9); assertEquals(126.9820, c.lonMax, 1e-9)
    }

    @Test fun `같은 자리에서 찍은 사진은 끝까지 한 덩어리이고 그렇다고 알려 준다`() {
        val here = List(5) { GeoPhoto(it.toLong(), 37.5, 127.0) }
        val c = GeoClusters.cluster(here, world(19), 64.0).single()
        assertEquals(5, c.size)
        assertTrue(c.atOnePlace)
    }

    @Test fun `극지방과 날짜변경선 좌표도 터지지 않는다`() {
        val out = GeoClusters.cluster(listOf(GeoPhoto(1, 90.0, 180.0), GeoPhoto(2, -90.0, -180.0)), world(3), 64.0)
        assertEquals(2, out.size)
    }

    @Test fun `빈 목록은 빈 목록이다`() {
        assertTrue(GeoClusters.cluster(emptyList(), world(3), 64.0).isEmpty())
    }

    @Test fun `맞춤 확대 수준에서 상자가 여백을 두고 화면에 들어온다`() {
        // 서울~부산: 경도 2.1°, 위도 2.4°. 1080×1900 화면, 여백 168px, 타일 256px 기준.
        val z = GeoClusters.fitZoom(35.18, 37.57, 126.98, 129.08, 1080.0, 1900.0, 168.0, 256.0, 2.0, 19.0)
        val worldPx = 256.0 * 2.0.pow(z)
        val widthPx = (129.08 - 126.98) / 360.0 * worldPx
        assertTrue(widthPx <= 1080.0 - 2 * 168.0 + 1.0, "너비가 여백을 넘는다: $widthPx")
        assertTrue(widthPx > (1080.0 - 2 * 168.0) * 0.45 || z >= 19.0, "너무 작게 맞췄다: $widthPx")
        // 한 칸 더 확대하면 넘쳐야 한다 — 가장 큰 수준이다.
        assertTrue(widthPx * 2 > 1080.0 - 2 * 168.0 || (z + 1) > 19.0)
    }

    @Test fun `점이 하나면 최대 확대 수준이 나온다`() {
        assertEquals(17.0, GeoClusters.fitZoom(37.5, 37.5, 127.0, 127.0, 1080.0, 1900.0, 168.0, 256.0, 2.0, 17.0))
    }

    @Test fun `세계 전체를 덮는 상자는 최소 확대 수준을 넘지 않는다`() {
        assertEquals(2.0, GeoClusters.fitZoom(-60.0, 70.0, -179.0, 179.0, 1080.0, 1900.0, 168.0, 256.0, 2.0, 19.0))
    }
}
