package kr.slz.photolog.core

import java.time.Instant
import java.time.ZoneId
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 로그(타임라인 이벤트). `pipeline.py`의 `build_events` + 안드로이드에서 더한 것.
 *
 * 이 앱의 얼굴이다. 앨범은 갤러리 앱도 준다 — **시간과 장소로 묶인 기록**이 차이다.
 * AI를 쓰지 않는다: EXIF 시각과 GPS만 본다. 그래서 Pass 1에서 몇 초 만에 끝난다(§3).
 */
object Events {

    data class Photo(
        val id: Long,
        val takenAt: Long,                 // epoch seconds
        val takenAtSource: TimeSource,
        val lat: Double? = null,
        val lon: Double? = null,
        val blurScore: Double? = null,
        val embedding: FloatArray? = null,
        val tags: List<String> = emptyList(),
    ) {
        override fun equals(other: Any?) = other is Photo && other.id == id
        override fun hashCode() = id.hashCode()
    }

    data class Event(
        val photoIds: List<Long>,
        val startedAt: Long,
        val endedAt: Long,
        val lat: Double?,
        val lon: Double?,
        val coverIds: List<Long>,
        var placeName: String? = null,
        var title: String = "",
    )

    /**
     * 연속된 사진을 이벤트로 자른다. 두 조건 중 하나라도 걸리면 나눈다:
     *  - 직전 사진과의 시간차 > [gapHours]
     *  - 직전 사진과의 거리 > [splitKm] (양쪽에 GPS가 있을 때만)
     *
     * **촬영시각이 EXIF에서 온 사진만 넣는다.** 카톡 저장 사진·스크린샷은 촬영시각이
     * 없어 파일 시각으로 떨어지는데, 그게 여행 로그 사이에 끼면 "3월 2일 · 스크린샷 40장"
     * 같은 가짜 이벤트가 생긴다. 그 사진들은 날짜별로 접어서 따로 보여 준다(§11.1).
     */
    fun build(
        photos: List<Photo>,
        gapHours: Int,
        splitKm: Double,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Event> {
        val rows = photos.filter { it.takenAtSource == TimeSource.EXIF }.sortedBy { it.takenAt }
        if (rows.isEmpty()) return emptyList()
        val gap = gapHours * 3600L

        val groups = ArrayList<MutableList<Photo>>()
        var cur = mutableListOf(rows[0])
        for (i in 1 until rows.size) {
            val prev = cur.last()
            val r = rows[i]
            val farInTime = r.takenAt - prev.takenAt > gap
            val farInSpace = splitKm > 0 && distanceKm(prev, r)?.let { it > splitKm } == true
            if (farInTime || farInSpace) { groups.add(cur); cur = mutableListOf(r) } else cur.add(r)
        }
        groups.add(cur)

        return groups.map { g ->
            val pts = g.mapNotNull { p -> p.lat?.let { la -> p.lon?.let { lo -> la to lo } } }
            Event(
                photoIds = g.map { it.id },
                startedAt = g.first().takenAt,
                endedAt = g.last().takenAt,
                lat = pts.takeIf { it.isNotEmpty() }?.map { it.first }?.average(),
                lon = pts.takeIf { it.isNotEmpty() }?.map { it.second }?.average(),
                coverIds = covers(g),
            ).also { it.title = title(it, g, zone) }
        }
    }

    /**
     * 커버 3장. 선명한 순으로 고르되 **서로 너무 비슷한 것은 건너뛴다.**
     *
     * 선명도만 보면 연사 3장이 커버가 되어 같은 사진 셋이 뜬다 — 이벤트 카드가
     * 그 날을 보여 주지 못한다. 임베딩이 없으면(Pass 1 시점) 선명도만으로 고른다.
     */
    fun covers(g: List<Photo>, max: Int = 3, maxCos: Float = 0.9f): List<Long> {
        val picked = ArrayList<Photo>(max)
        for (p in g.sortedByDescending { it.blurScore ?: 0.0 }) {
            if (picked.size >= max) break
            val tooSimilar = p.embedding != null && picked.any {
                it.embedding != null && Embeddings.dot(p.embedding, it.embedding) >= maxCos
            }
            if (!tooSimilar) picked.add(p)
        }
        // 전부 비슷해서 못 채웠으면 선명도 순으로 메꾼다 — 커버가 비는 것보다 낫다.
        if (picked.size < max) {
            for (p in g.sortedByDescending { it.blurScore ?: 0.0 }) {
                if (picked.size >= max) break
                if (p !in picked) picked.add(p)
            }
        }
        return picked.map { it.id }
    }

    /** `3월 2일 · 강릉시 · 풍경 12, 음식 4`. 장소는 나중에 채워질 수 있어 다시 부를 수 있다. */
    fun title(e: Event, g: List<Photo>, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = Instant.ofEpochSecond(e.startedAt).atZone(zone)
        val parts = ArrayList<String>(3)
        parts.add("${d.monthValue}월 ${d.dayOfMonth}일")
        e.placeName?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        val top = g.flatMap { it.tags }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(2)
        parts.add(
            if (top.isEmpty()) "사진 ${g.size}장"
            else top.joinToString(", ") { "${it.key} ${it.value}장" }
        )
        return parts.joinToString(" · ")
    }

    /** 하버사인. 이벤트를 가를 만큼 멀리 움직였는지 보는 데만 쓴다 — 정밀도는 충분하다. */
    fun distanceKm(a: Photo, b: Photo): Double? {
        val la1 = a.lat ?: return null; val lo1 = a.lon ?: return null
        val la2 = b.lat ?: return null; val lo2 = b.lon ?: return null
        val r = 6371.0
        val dLa = Math.toRadians(la2 - la1)
        val dLo = Math.toRadians(lo2 - lo1)
        val h = sin(dLa / 2) * sin(dLa / 2) +
            cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLo / 2) * sin(dLo / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}
