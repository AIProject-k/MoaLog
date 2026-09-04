package kr.slz.photolog.core

/** 갤러리의 개별/전체 선택 규칙. */
object Selection {
    fun toggle(selected: Set<Long>, id: Long): Set<Long> =
        if (id in selected) selected - id else selected + id

    fun toggleAll(selected: Set<Long>, ids: Set<Long>): Set<Long> =
        if (ids.isNotEmpty() && selected.containsAll(ids)) emptySet() else ids
}
