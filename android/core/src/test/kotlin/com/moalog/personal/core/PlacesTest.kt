package com.moalog.personal.core

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacesTest {

    private val tsv = listOf(
        "# 이름\t다른 이름\t위도\t경도\t반경(km)",
        "강릉시\t강릉|강릉시청\t37.7519\t128.8761\t25",
        "속초시\t속초\t38.2070\t128.5918\t15",
        "깨진줄\t\tabc\t1\t2",     // 숫자가 아니면 건너뛴다
        "",                       // 빈 줄도 건너뛴다
    ).joinToString("\n")

    private val index = PlaceIndex.parse(tsv)

    @Test fun `표를 읽고 주석과 깨진 줄은 건너뛴다`() {
        assertEquals(setOf("강릉시", "강릉", "강릉시청", "속초시", "속초"), index.names)
    }

    @Test fun `다른 이름으로도 장소를 찾는다`() {
        assertEquals("강릉시", index.find("강릉")?.name)
        assertEquals("속초시", index.find("속초")?.name)
        assertNull(index.find("없는곳"))
    }

    @Test fun `반경 안의 좌표는 그 장소이고 밖이면 없다`() {
        assertEquals("강릉시", index.nearest(37.75, 128.88)?.name)
        assertNull(index.nearest(37.0, 127.0))
    }

    @Test fun `반경 경계는 km로 정확히 자른다`() {
        val gangneung = index.find("강릉")!!
        // 위도 1도 ≈ 111.19km. 24km ≈ 0.216도, 26km ≈ 0.234도 북쪽.
        assertTrue(index.within(gangneung, 37.7519 + 0.216, 128.8761))
        assertFalse(index.within(gangneung, 37.7519 + 0.234, 128.8761))
    }

    @Test fun `거리 계산은 대칭이고 같은 점이면 0이다`() {
        val a = PlaceIndex.distanceKm(37.7519, 128.8761, 38.2070, 128.5918)
        val b = PlaceIndex.distanceKm(38.2070, 128.5918, 37.7519, 128.8761)
        assertEquals(a, b, 1e-9)
        assertEquals(0.0, PlaceIndex.distanceKm(37.0, 127.0, 37.0, 127.0), 1e-9)
    }

    @Test fun `이벤트에는 평균 좌표의 장소 이름이 붙고 제목에도 들어간다`() {
        val photos = listOf(
            Events.Photo(1, 1_700_000_000L, TimeSource.EXIF, 37.75, 128.88),
            Events.Photo(2, 1_700_000_600L, TimeSource.EXIF, 37.76, 128.87),
        )
        val events = Events.build(
            photos = photos, gapHours = 6, splitKm = 30.0,
            placeOf = { la, lo -> index.nearest(la, lo)?.name },
            zone = ZoneId.of("Asia/Seoul"),
        )
        assertEquals("강릉시", events.single().placeName)
        assertTrue(events.single().title.contains("강릉시"), "제목: ${events.single().title}")
    }

    @Test fun `장소 표가 없으면 이벤트 이름은 비어 있다`() {
        val photos = listOf(Events.Photo(1, 1_700_000_000L, TimeSource.EXIF, 37.75, 128.88))
        val e = Events.build(photos = photos, gapHours = 6, splitKm = 30.0, zone = ZoneId.of("Asia/Seoul")).single()
        assertNull(e.placeName)
    }

    @Test fun `검색어의 알려진 장소 이름만 장소 조건으로 뽑는다`() {
        val parser = QueryParser(knownPlaces = index.names)
        val parsed = parser.parse("강릉시 바다 사진")
        assertEquals(setOf("강릉시"), parsed.places)
        // 모르는 장소 이름은 장소 조건이 되지 않는다(텍스트로 남는다)
        assertTrue(parser.parse("서울 바다").places.isEmpty())
    }

    @Test fun `명사 끝의 이가 붙은 말은 장소가 아니다`() {
        // "고양이"는 고양시가 아니다. 장소 조사가 붙은 "고양에서"는 장소다.
        val parser = QueryParser(knownPlaces = setOf("고양"))
        assertTrue(parser.parse("고양이 사진").places.isEmpty())
        assertEquals(setOf("고양"), parser.parse("고양에서 사진").places)
    }
}
