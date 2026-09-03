package kr.slz.photolog.core

import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 중복·연사·이벤트. `test_pipeline.py` 4·4b·6·7·8번을 옮겼다.
 * 이 프로젝트에서 제일 틀리기 쉬운 곳이고, 틀리면 사진을 지우자고 제안한다.
 */
class GroupingTest {

    private val copyMarkers = listOf("복사본", "사본", "copy", "복사", "duplicate")
    private val seoul = ZoneId.of("Asia/Seoul")

    private fun vec(seed: Int, jitter: Float = 0f): FloatArray {
        val r = Random(seed)
        return Embeddings.unit(FloatArray(512) { r.nextFloat() - 0.5f + jitter * (it % 7) })
    }

    // ---------- 원본 판별 ----------

    @Test fun `사본은 이름이 길어진다 — 접미사로 원본을 가른다`() {
        val cases = listOf(
            "cafe_01.jpg" to "cafe_01 - 복사본.jpg",
            "trip_01.jpg" to "trip_01(1).jpg",
            "IMG_0042.JPG" to "IMG_0042 - Copy.JPG",
            "a.png" to "a (2).png",
        )
        val cmp = compareBy<Triple<Int, Int, String>>({ it.first }, { it.second }, { it.third })
        for ((orig, copy) in cases) {
            assertTrue(cmp.compare(originRank(orig), originRank(copy)) < 0,
                "'$orig'가 '$copy'보다 원본다워야 한다")
        }
    }

    private fun originRank(n: String) = Grouping.originRank(n, copyMarkers)

    @Test fun `완전중복은 해시로 묶고 원본을 남긴다`() {
        val items = listOf(
            Grouping.Item(1, "cafe_01 - 복사본.jpg", sha256 = "aa"),
            Grouping.Item(2, "cafe_01.jpg", sha256 = "aa"),
            Grouping.Item(3, "other.jpg", sha256 = "bb"),
        )
        val g = Grouping.exactGroups(items, copyMarkers)
        assertEquals(1, g.size, "해시가 같은 2장만 묶여야 한다")
        assertEquals(setOf(1L, 2L), g[0].ids.toSet())
        assertEquals(2L, g[0].bestId, "스캔 순서가 아니라 이름으로 원본을 골라야 한다")
    }

    // ---------- 연사 (이 프로젝트에서 제일 틀리기 쉬운 곳) ----------

    @Test fun `연사는 유사도 AND 시간이다 — 하루 뒤 비슷한 사진은 안 묶인다`() {
        val t = 1_700_000_000L
        val base = vec(1)
        val items = listOf(
            Grouping.Item(1, "a.jpg", takenAt = t, takenAtSource = TimeSource.EXIF,
                blurScore = 100.0, embedding = base),
            Grouping.Item(2, "b.jpg", takenAt = t + 5, takenAtSource = TimeSource.EXIF,
                blurScore = 200.0, embedding = base),
            Grouping.Item(3, "c.jpg", takenAt = t + 86400, takenAtSource = TimeSource.EXIF,
                blurScore = 150.0, embedding = base),
        )
        val g = Grouping.burstGroups(items, similarity = 0.95f, gapSec = 30)
        assertEquals(1, g.size)
        assertEquals(setOf(1L, 2L), g[0].ids.toSet(), "3번(하루 뒤)이 잘못 묶였다")
        assertEquals(2L, g[0].bestId, "가장 선명한 것을 남겨야 한다")
    }

    @Test fun `파일 시각만 있는 사진은 연사 판정에서 아예 빠진다`() {
        // 한 번에 내려받은 사진들: 파일 시각이 초 단위로 붙어 있고 서로 비슷하다.
        // 시간 조건이 무력해지므로 유사도만으로 묶이면 안 된다 (ANDROID.md §12.2).
        val t = 1_700_000_000L
        val base = vec(2)
        val items = (1..4).map {
            Grouping.Item(it.toLong(), "kakao_$it.jpg", takenAt = t + it,
                takenAtSource = TimeSource.FILE, blurScore = 100.0, embedding = base)
        }
        assertTrue(Grouping.burstGroups(items, 0.95f, 30).isEmpty(),
            "촬영시각을 모르는 사진이 연사로 묶였다 — 정리 탭이 삭제를 제안하게 된다")
    }

    @Test fun `연사는 첫 장 기준으로 잰다 — 사슬처럼 이어붙지 않는다`() {
        // 서로 조금씩 다른 사진이 사슬로 이어지면 처음과 끝은 전혀 다른 사진이 된다.
        val t = 1_700_000_000L
        val items = (0..5).map {
            Grouping.Item(it.toLong(), "s$it.jpg", takenAt = t + it * 5,
                takenAtSource = TimeSource.EXIF, blurScore = 100.0,
                embedding = vec(7, jitter = it * 0.02f))
        }
        val g = Grouping.burstGroups(items, 0.99f, 30)
        g.forEach { grp ->
            val vecs = grp.ids.map { id -> items.first { it.id == id }.embedding!! }
            for (v in vecs) assertTrue(Embeddings.dot(vecs[0], v) >= 0.99f,
                "그룹 안에 기준(첫 장)과 0.99 미만인 사진이 있다")
        }
    }

    @Test fun `완전중복으로 이미 묶인 사진은 연사로 또 묶지 않는다`() {
        val t = 1_700_000_000L
        val base = vec(3)
        val items = (1..3).map {
            Grouping.Item(it.toLong(), "x$it.jpg", sha256 = "same", takenAt = t + it,
                takenAtSource = TimeSource.EXIF, blurScore = 100.0, embedding = base)
        }
        val all = Grouping.all(items, copyMarkers, 0.95f, 30)
        assertEquals(1, all.size, "한 사진이 두 그룹에 들어갔다: $all")
        assertEquals(Grouping.Kind.EXACT, all[0].kind)
    }

    // ---------- 삭제 가드 ----------

    @Test fun `그룹 전원을 지우려 하면 1장은 남긴다`() {
        val g = listOf(Grouping.Group(Grouping.Kind.BURST, listOf(1, 2), bestId = 2))
        val (del, kept) = Grouping.keepOnePerGroup(setOf(1, 2), g)
        assertEquals(listOf(1L), del)
        assertEquals(listOf(2L), kept, "남길 것으로 표시된 쪽을 남겨야 한다")
    }

    @Test fun `일부만 지우는 건 그대로 통과한다`() {
        val g = listOf(Grouping.Group(Grouping.Kind.BURST, listOf(1, 2), bestId = 2))
        val (del, kept) = Grouping.keepOnePerGroup(setOf(1), g)
        assertEquals(listOf(1L), del)
        assertTrue(kept.isEmpty())
    }

    // ---------- 이벤트 ----------

    private fun p(id: Long, t: Long, lat: Double? = null, lon: Double? = null,
                  blur: Double = 100.0, tags: List<String> = emptyList()) =
        Events.Photo(id, t, TimeSource.EXIF, lat, lon, blur, null, tags)

    @Test fun `시간 간격이 벌어지면 이벤트가 나뉜다`() {
        val t = 1_700_000_000L
        val gap = 3 * 3600L
        val ev = Events.build(listOf(p(1, t), p(2, t + 600), p(3, t + 600 + gap + 60)), 3, 0.0, seoul)
        assertEquals(2, ev.size)
        assertEquals(listOf(2, 1), ev.map { it.photoIds.size })
    }

    @Test fun `멀리 이동하면 같은 시간대라도 나뉜다`() {
        val t = 1_700_000_000L
        // 서울 → 강릉 ~165km, 10분 차이 (기차)
        val ev = Events.build(listOf(
            p(1, t, 37.5665, 126.9780),
            p(2, t + 600, 37.7519, 128.8761),
        ), 3, 30.0, seoul)
        assertEquals(2, ev.size, "위치 점프로 나뉘어야 한다")
    }

    @Test fun `파일 시각 사진은 이벤트에 들어가지 않는다`() {
        val t = 1_700_000_000L
        val mixed = listOf(p(1, t), Events.Photo(2, t + 60, TimeSource.FILE), p(3, t + 120))
        val ev = Events.build(mixed, 3, 0.0, seoul)
        assertEquals(1, ev.size)
        assertEquals(listOf(1L, 3L), ev[0].photoIds, "스크린샷·카톡 사진이 여행 로그에 끼었다")
    }

    @Test fun `커버는 서로 비슷한 사진을 피한다`() {
        val t = 1_700_000_000L
        val same = vec(9)
        val g = listOf(
            Events.Photo(1, t, TimeSource.EXIF, blurScore = 300.0, embedding = same),
            Events.Photo(2, t + 1, TimeSource.EXIF, blurScore = 290.0, embedding = same),
            Events.Photo(3, t + 2, TimeSource.EXIF, blurScore = 280.0, embedding = same),
            Events.Photo(4, t + 3, TimeSource.EXIF, blurScore = 10.0, embedding = vec(10)),
        )
        val covers = Events.covers(g)
        assertEquals(3, covers.size)
        assertTrue(4L in covers, "연사 3장으로 커버를 채웠다 — 같은 사진 셋이 뜬다: $covers")
    }

    @Test fun `제목에 날짜와 장소와 상위 태그가 들어간다`() {
        val t = java.time.LocalDateTime.of(2026, 3, 2, 14, 0).atZone(seoul).toEpochSecond()
        val g = listOf(
            p(1, t, tags = listOf("풍경/여행")), p(2, t + 60, tags = listOf("풍경/여행")),
            p(3, t + 120, tags = listOf("음식")),
        )
        val ev = Events.build(g, 3, 0.0, seoul).single()
        ev.placeName = "강릉시"
        val title = Events.title(ev, g, seoul)
        assertTrue(title.startsWith("3월 2일 · 강릉시 · "), title)
        assertTrue(title.contains("풍경/여행 2장"), title)
    }

    @Test fun `태그가 없으면 장수로 제목을 만든다`() {
        val t = java.time.LocalDateTime.of(2026, 3, 2, 14, 0).atZone(seoul).toEpochSecond()
        val g = listOf(p(1, t), p(2, t + 60))
        val ev = Events.build(g, 3, 0.0, seoul).single()
        assertEquals("3월 2일 · 사진 2장", ev.title)
    }
}
