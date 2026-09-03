package kr.slz.photolog.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 한국어 검색어에서 **CLIP이 모르는 것**을 먼저 떼어 낸다.
 *
 * 사용자는 "작년 여름 강릉에서 먹은 회"라고 친다. CLIP은 '회'는 알지만 '작년'은 모른다 —
 * 시간·장소는 메타데이터로 걸러야 하고, 남은 말만 임베딩으로 랭킹한다.
 *
 * 규칙 몇 줄이다. 파서가 소비한 말은 [Parsed.chips]로 돌려주므로 화면에 칩으로 보여
 * 주고, 잘못 해석했으면 사용자가 칩을 끌 수 있다 — 조용히 틀리는 것보다 낫다.
 *
 * `ponytail:` 형태소 분석기를 넣지 않는다. 갤러리 검색어는 짧고 패턴이 뻔하다.
 *            여기서 못 잡는 말이 실제로 쌓이면 그때 다시 본다.
 */
class QueryParser(
    private val knownTags: Set<String> = emptySet(),
    private val knownPlaces: Set<String> = emptySet(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /**
     * @param text CLIP에 넘길 남은 말. 비면 필터만으로 찾는다.
     * @param from,until epoch seconds 범위 (null이면 제한 없음)
     * @param hours 하루 중 시간대 제한 (야간 등). 비면 제한 없음
     * @param chips 파서가 소비한 말 — UI가 "이렇게 해석했다"고 보여 주는 데 쓴다
     */
    data class Parsed(
        val text: String,
        val from: Long? = null,
        val until: Long? = null,
        val hours: IntRange? = null,
        val tags: Set<String> = emptySet(),
        val places: Set<String> = emptySet(),
        val chips: List<String> = emptyList(),
    ) {
        val hasFilter get() = from != null || until != null || hours != null ||
            tags.isNotEmpty() || places.isNotEmpty()
    }

    fun parse(query: String, now: LocalDate = LocalDate.now(zone)): Parsed {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val left = ArrayList<String>()
        val chips = ArrayList<String>()
        val tags = LinkedHashSet<String>()
        val places = LinkedHashSet<String>()
        var from: LocalDate? = null
        var until: LocalDate? = null
        var hours: IntRange? = null

        for (w in words) {
            val bare = stripParticle(w)
            // 1) 태그·장소 이름과 그대로 맞는 말
            val tag = knownTags.firstOrNull { it == w || it == bare }
            if (tag != null) { tags.add(tag); chips.add("태그: $tag"); continue }
            val place = knownPlaces.firstOrNull { it == w || it == bare || w.startsWith(it) }
            if (place != null) { places.add(place); chips.add("장소: $place"); continue }

            // 2) 시간
            val t = timeOf(w, now)
            if (t != null) {
                t.range?.let { (a, b) ->
                    from = maxOfNullable(from, a)
                    until = minOfNullable(until, b)
                }
                t.hours?.let { hours = it }
                chips.add("시간: ${t.label}")
                continue
            }
            left.add(w)
        }

        return Parsed(
            text = left.joinToString(" "),
            from = from?.atStartOfDay(zone)?.toEpochSecond(),
            until = until?.plusDays(1)?.atStartOfDay(zone)?.toEpochSecond()?.minus(1),
            hours = hours,
            tags = tags, places = places, chips = chips,
        )
    }

    private class Time(val label: String, val range: Pair<LocalDate, LocalDate>? = null,
                       val hours: IntRange? = null)

    private fun timeOf(w: String, now: LocalDate): Time? {
        fun year(y: Int) = LocalDate.of(y, 1, 1) to LocalDate.of(y, 12, 31)
        fun month(y: Int, m: Int) = LocalDate.of(y, m, 1).let { it to it.withDayOfMonth(it.lengthOfMonth()) }

        // 연/월 상대 표현
        when {
            w.startsWith("올해") || w.startsWith("금년") -> return Time("올해", year(now.year))
            w.startsWith("작년") || w.startsWith("지난해") -> return Time("작년", year(now.year - 1))
            w.startsWith("재작년") -> return Time("재작년", year(now.year - 2))
            w.startsWith("이번달") || w.startsWith("이달") -> return Time("이번 달", month(now.year, now.monthValue))
            w.startsWith("지난달") -> return now.minusMonths(1).let { Time("지난 달", month(it.year, it.monthValue)) }
            w.startsWith("오늘") -> return Time("오늘", now to now)
            w.startsWith("어제") -> return now.minusDays(1).let { Time("어제", it to it) }
            w.startsWith("밤") || w.startsWith("야간") || w.startsWith("새벽") ->
                return Time("밤", hours = 20..29)      // 20~23시 + 0~5시. 아래에서 감싼다
            w.startsWith("낮") || w.startsWith("오후") -> return Time("낮", hours = 9..18)
        }

        // "3월", "12월"
        Regex("^(\\d{1,2})월").find(w)?.let {
            val m = it.groupValues[1].toInt()
            if (m in 1..12) return Time("${m}월", month(now.year, m))
        }
        // "2026년"
        Regex("^(\\d{4})년").find(w)?.let {
            val y = it.groupValues[1].toInt()
            if (y in 1900..2200) return Time("${y}년", year(y))
        }
        // 계절 — 북반구 기준. 남반구 사용자가 생기면 config로 뺀다.
        SEASONS[w.take(2)]?.let { (a, b) ->
            return Time(w.take(2), LocalDate.of(now.year, a, 1)
                .let { s -> s to LocalDate.of(now.year, b, 1).withDayOfMonth(
                    LocalDate.of(now.year, b, 1).lengthOfMonth()) })
        }
        return null
    }

    /** 야간은 자정을 넘는다. 20..29를 20~23시와 0~5시로 풀어 준다. */
    fun matchesHour(p: Parsed, epochSeconds: Long): Boolean {
        val h = p.hours ?: return true
        val hour = LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(epochSeconds), zone).hour
        return h.any { (it % 24) == hour }
    }

    /**
     * 뒤에 붙은 조사를 뗀다. 긴 것부터 봐야 한다 — '에서'를 '서'로 먼저 자르면 안 된다.
     * 형태소 분석이 아니라 목록 매칭이다(ANDROID.md §10.2). 남은 말은 그대로 CLIP에 간다.
     */
    private fun stripParticle(w: String): String {
        for (j in PARTICLES) if (w.length > j.length && w.endsWith(j)) return w.dropLast(j.length)
        return w
    }

    private fun maxOfNullable(a: LocalDate?, b: LocalDate) = if (a == null || b.isAfter(a)) b else a
    private fun minOfNullable(a: LocalDate?, b: LocalDate) = if (a == null || b.isBefore(a)) b else a

    private companion object {
        val SEASONS = mapOf("봄" to (3 to 5), "여름" to (6 to 8), "가을" to (9 to 11), "겨울" to (12 to 12))

        /** 긴 것부터. 앞에서 자르면 짧은 조사가 먼저 걸려 말을 망친다. */
        val PARTICLES = listOf("에서는", "에서", "으로", "이랑", "까지", "부터", "에게",
                               "에", "의", "을", "를", "은", "는", "이", "가", "도", "만")
    }
}
