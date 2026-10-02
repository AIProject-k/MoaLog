package com.moalog.personal.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 갤러리의 날짜 머리글 묶기. 항목이 한두 장뿐인 날마다 머리글이 붙으면 머리글이 사진보다 세로 공간을
 * 더 쓴다(현재 디자인의 문제 3). 그래서 **3장 이하인 이웃한 날은 한 머리글로 묶는다**: "9월 24일 – 25일".
 *
 * 규칙(디자인 `MoaLog App.dc.html`의 `groupDays`):
 *  - 같은 날은 항상 한 묶음이다. 날짜 없는 항목은 맨 뒤 "날짜 미상" 한 묶음이다.
 *  - 이전 묶음과 **이번 날 둘 다 3장 이하**이고, 같은 달이고, 사이가 3일 이내면 합친다.
 *  - 오늘·어제는 묶지 않는다("오늘"·"어제"는 그 자체로 읽히는 이름이다).
 *
 * 순수 함수다. 항목은 **최신순**으로 들어온다고 본다(갤러리가 읽는 순서).
 */
object DayGrouping {
    /** 합칠 수 있는 한 날의 최대 장수. */
    const val SMALL_DAY = 3

    /** 합칠 수 있는 날짜 간격(일). */
    const val MAX_GAP_DAYS = 3L

    /**
     * @param newest 묶음의 가장 최근 날짜. 날짜 미상이면 null
     * @param oldest 묶음의 가장 오래된 날짜. 한 날이면 [newest]와 같다
     */
    data class Span<T>(val newest: LocalDate?, val oldest: LocalDate?, val items: List<T>) {
        /** 여러 날을 묶었는가. */
        val merged: Boolean get() = newest != null && oldest != null && newest != oldest
    }

    fun <T> group(items: List<T>, dateOf: (T) -> LocalDate?, today: LocalDate): List<Span<T>> {
        // 1) 같은 날(연속 구간)로 먼저 모은다
        class Day(val date: LocalDate?, val items: MutableList<T>)
        val days = ArrayList<Day>()
        for (item in items) {
            val d = dateOf(item)
            val last = days.lastOrNull()
            if (last != null && last.date == d) last.items.add(item) else days.add(Day(d, mutableListOf(item)))
        }

        // 2) 작은 이웃 날을 합친다
        class Acc(var newest: LocalDate?, var oldest: LocalDate?, val items: MutableList<T>, var lastCount: Int)
        val out = ArrayList<Acc>()
        val yesterday = today.minusDays(1)
        fun relative(d: LocalDate?) = d == today || d == yesterday
        for (day in days) {
            val p = out.lastOrNull()
            val mergeable = p != null && day.date != null && p.newest != null && p.oldest != null &&
                !relative(p.newest) && !relative(day.date) &&
                p.newest!!.year == day.date.year && p.newest!!.monthValue == day.date.monthValue &&
                p.lastCount <= SMALL_DAY && day.items.size <= SMALL_DAY &&
                ChronoUnit.DAYS.between(day.date, p.oldest!!) <= MAX_GAP_DAYS
            if (mergeable) {
                p!!.items.addAll(day.items)
                p.oldest = day.date
                p.lastCount = day.items.size
            } else {
                out.add(Acc(day.date, day.date, day.items.toMutableList(), day.items.size))
            }
        }
        return out.map { Span(it.newest, it.oldest, it.items) }
    }
}
