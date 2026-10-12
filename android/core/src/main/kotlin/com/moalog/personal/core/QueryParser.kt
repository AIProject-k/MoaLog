package com.moalog.personal.core

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
     * @param chips 파서가 소비한 말 — UI가 "이렇게 해석했다"고 보여 주는 데 쓴다. **꺼 둔 칩도 들어 있다**(화면이 취소선으로 보여 주고 다시 켤 수 있게).
     * @param media 종류 말("영상"·"사진")이 있으면 `"video"`/`"image"`. 없으면 null
     */
    data class Parsed(
        val text: String,
        val from: Long? = null,
        val until: Long? = null,
        val hours: IntRange? = null,
        val tags: Set<String> = emptySet(),
        val places: Set<String> = emptySet(),
        val chips: List<String> = emptyList(),
        val media: String? = null,
    ) {
        val hasFilter get() = from != null || until != null || hours != null ||
            tags.isNotEmpty() || places.isNotEmpty() || media != null
    }

    /**
     * @param disabled 사용자가 끈 칩의 글자(`"시간: 작년"`). 끈 칩은 조건으로 쓰지 않는다 —
     *  태그·장소 칩은 그 말이 **CLIP 검색어로 돌아가고**, 시간·종류 칩은 그냥 빠진다("작년"은 CLIP이 모른다).
     */
    fun parse(query: String, now: LocalDate = LocalDate.now(zone), disabled: Set<String> = emptySet()): Parsed {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val left = ArrayList<String>()
        val chips = ArrayList<String>()
        val tags = LinkedHashSet<String>()
        val places = LinkedHashSet<String>()
        var from: LocalDate? = null
        var until: LocalDate? = null
        var hours: IntRange? = null
        var media: String? = null
        // "작년 여름"의 '여름'은 작년 여름이다. 연도 말을 먼저 찾아 두고 월·계절에 물려준다 —
        // 따로 풀면 작년(2025) ∩ 올해 여름(2026) = 공집합이 되어 아무것도 안 나온다.
        // 끈 칩의 연도 말은 힌트로도 쓰지 않는다 — "작년"을 끄면 '여름'은 올해 여름이다.
        val yearHint = words.firstNotNullOfOrNull { w ->
            yearOf(w, now)?.takeIf { timeOf(w, now)?.let { t -> "시간: ${t.label}" in disabled } != true }
        }

        for (w in words) {
            val bare = stripParticle(w)
            // 1) 태그·장소 이름과 그대로 맞는 말
            val tag = knownTags.firstOrNull { it == w || it == bare }
            if (tag != null) {
                val chip = "태그: $tag"
                chips.add(chip)
                if (chip in disabled) left.add(w) else tags.add(tag)
                continue
            }
            // 여러 개가 맞으면 가장 긴 것을 고른다. "강릉시"가 별칭 "강릉"으로 잘리면 안 된다(집합 순서에 기대지 않는다).
            // 장소는 이름 그대로이거나 장소 조사("에서", "에", "으로"…)가 붙은 것만 본다.
            // "이"·"가"·"은" 같은 조사까지 떼면 "고양이"가 고양시가 된다. 여러 개면 가장 긴 것.
            val place = knownPlaces.filter { k -> w == k || PLACE_TAILS.any { t -> w == k + t } }.maxByOrNull { it.length }
            if (place != null) {
                val chip = "장소: $place"
                chips.add(chip)
                if (chip in disabled) left.add(w) else places.add(place)
                continue
            }

            // 2) 종류 — "영상", "사진". 영상만/사진만 보여 달라는 말이다.
            val kind = when (bare) {
                in VIDEO_WORDS -> "video" to "종류: 동영상"
                in PHOTO_WORDS -> "image" to "종류: 사진"
                else -> null
            }
            if (kind != null) {
                chips.add(kind.second)
                if (kind.second !in disabled) media = kind.first
                continue
            }

            // 3) 시간
            val t = timeOf(w, now, yearHint)
            if (t != null) {
                val chip = "시간: ${t.label}"
                chips.add(chip)
                if (chip !in disabled) {
                    t.range?.let { (a, b) ->
                        from = maxOfNullable(from, a)
                        until = minOfNullable(until, b)
                    }
                    t.hours?.let { hours = it }
                }
                continue
            }
            // 4) "나오는"·"찍은" 같은 군더더기는 CLIP에 보내지 않는다. 뜻이 없고 점수만 흐린다.
            if (w in FILLER || bare in FILLER) continue
            left.add(w)
        }

        return Parsed(
            text = left.joinToString(" "),
            from = from?.atStartOfDay(zone)?.toEpochSecond(),
            until = until?.plusDays(1)?.atStartOfDay(zone)?.toEpochSecond()?.minus(1),
            hours = hours,
            tags = tags, places = places, chips = chips, media = media,
        )
    }

    private class Time(val label: String, val range: Pair<LocalDate, LocalDate>? = null,
                       val hours: IntRange? = null)

    /** 연도를 가리키는 말이면 그 해. "작년", "2025년" 등. */
    private fun yearOf(w: String, now: LocalDate): Int? = when {
        w.startsWith("재작년") -> now.year - 2
        w.startsWith("작년") || w.startsWith("지난해") -> now.year - 1
        w.startsWith("올해") || w.startsWith("금년") -> now.year
        else -> Regex("^(\\d{4})년").find(w)?.groupValues?.get(1)?.toInt()?.takeIf { it in 1900..2200 }
    }

    private fun timeOf(w: String, now: LocalDate, yearHint: Int? = null): Time? {
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

        /**
         * 연도 말이 없으면 **가장 최근에 지나간** 그 달·계절이다. 9월에 "겨울"이라고 치면
         * 석 달 뒤 겨울이 아니라 지난 겨울을 찾는 것이다 — 아직 오지 않은 날의 사진은 없다.
         */
        fun resolve(start: (Int) -> LocalDate, end: (Int) -> LocalDate): Pair<LocalDate, LocalDate> {
            val y = yearHint ?: now.year.let { if (start(it).isAfter(now)) it - 1 else it }
            return start(y) to end(y)
        }

        // "3월", "12월"
        Regex("^(\\d{1,2})월").find(w)?.let {
            val m = it.groupValues[1].toInt()
            if (m in 1..12) return Time("${m}월", resolve({ y -> month(y, m).first }, { y -> month(y, m).second }))
        }
        // "2026년"
        Regex("^(\\d{4})년").find(w)?.let {
            val y = it.groupValues[1].toInt()
            if (y in 1900..2200) return Time("${y}년", year(y))
        }
        // 계절 — 북반구 기준. 남반구 사용자가 생기면 config로 뺀다.
        // 겨울은 해를 넘긴다: 그해 12월 ~ 다음 해 2월.
        SEASONS[w.take(2)]?.let { (a, b) ->
            val end = { y: Int ->
                (if (b < a) LocalDate.of(y + 1, b, 1) else LocalDate.of(y, b, 1)).let { it.withDayOfMonth(it.lengthOfMonth()) }
            }
            return Time(w.take(2), resolve({ y -> LocalDate.of(y, a, 1) }, end))
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
        /** 장소 이름 뒤에 붙어도 장소를 뜻하는 조사. "이"·"가"·"은"은 일부러 뺐다(명사 끝과 겹친다). */
        val PLACE_TAILS = listOf("에서는", "에서의", "에서", "으로", "까지", "부터", "근처", "쪽", "에", "의", "로")

        val VIDEO_WORDS = setOf("영상", "동영상", "비디오")
        val PHOTO_WORDS = setOf("사진")

        /** 검색어에 붙어 다니지만 장면과 상관없는 말. */
        val FILLER = setOf("나오는", "나온", "찍은", "찍힌", "있는", "보이는", "담긴", "촬영한", "중", "중에")

        val SEASONS = mapOf("봄" to (3 to 5), "여름" to (6 to 8), "가을" to (9 to 11), "겨울" to (12 to 2))

        /** 긴 것부터. 앞에서 자르면 짧은 조사가 먼저 걸려 말을 망친다. */
        val PARTICLES = listOf("에서는", "에서", "으로", "이랑", "까지", "부터", "에게",
                               "에", "의", "을", "를", "은", "는", "이", "가", "도", "만")
    }
}
