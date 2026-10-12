package com.moalog.personal.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 앱에 실제로 든 장소 표를 읽어서 대표 도시가 제대로 잡히는지 본다. 표를 다시 만들면 여기서 깨진다. */
class PlacesAssetTest {

    private val index = PlaceIndex.parse(File("../app/src/main/assets/places.tsv").canonicalFile.readText())

    @Test fun `표는 충분히 크고 한국 도시가 들어 있다`() {
        assertTrue(index.names.size > 5_000, "이름이 ${index.names.size}개뿐이다")
        assertNotNull(index.find("강릉"))
        assertNotNull(index.find("속초"))
    }

    @Test fun `다른 이름으로 불러도 같은 도시다`() {
        assertEquals("강릉", index.find("강릉시")?.name)
        assertEquals("서울", index.find("서울특별시")?.name)
        assertEquals("서울", index.find("경성")?.name)
    }

    @Test fun `좌표가 강릉 시내면 강릉이다`() {
        assertEquals("강릉", index.nearest(37.75, 128.88)?.name)
    }
}
