package com.moalog.personal.core

import java.util.PriorityQueue

/**
 * 검색 인덱스. 임베딩 전체를 **한 번** 메모리에 올려 두고 내적으로 훑는다.
 *
 * 데스크톱은 질의마다 임베딩을 전량 SELECT했다(400장 0.8MB라 티가 안 났다). 폰에서
 * 1만 장이면 질의당 20MB 읽기라 그대로는 못 간다. 대신 시작할 때 한 번 로드한다 —
 * 1만 장 × 512 × 4B = 20MB, 로드 ~100ms, 질의당 5M MAC ≈ 5~10ms. 벡터DB는 필요 없다.
 *
 * 쓰는 쪽(워커)과 읽는 쪽(검색)이 다른 스레드에서 돈다. 모든 공개 함수는 한 자물쇠를 잡는다.
 * 그러지 않으면 [grow]가 배열을 바꾸는 순간을 검색이 읽을 수 있다(계획서 MLG-102).
 *
 * `ponytail:` fp32 완전탐색이다. 5만 장을 넘으면 fp16(절반) → int8 + 청크 스캔으로
 *            올릴 것. 그 전엔 손대지 않는다.
 */
class SearchIndex(capacity: Int = 1024, private val dim: Int = Embeddings.DIM) {

    private var ids = LongArray(capacity)
    private var mat = FloatArray(capacity * dim)

    /** id → 행 번호. 예전에는 행을 선형으로 훑었다(1만 장이면 매 갱신마다 1만 번 비교). */
    private val rowOf = HashMap<Long, Int>(capacity)

    val size: Int get() = synchronized(this) { rowOf.size }

    /** 이미 있는 id면 벡터만 갈아 끼운다 (재인덱싱). 없으면 뒤에 붙인다. */
    @Synchronized
    fun put(id: Long, vec: FloatArray) {
        require(vec.size == dim) { "차원이 다르다: ${vec.size} ≠ $dim" }
        val at = rowOf[id] ?: run {
            if (rowOf.size == ids.size) grow()
            val row = rowOf.size
            ids[row] = id
            rowOf[id] = row
            row
        }
        vec.copyInto(mat, at * dim)
    }

    /** 마지막 행을 빈자리로 옮긴다(swap-remove). 행 순서는 의미가 없다. */
    @Synchronized
    fun remove(id: Long) {
        val at = rowOf.remove(id) ?: return
        val last = rowOf.size          // 지운 뒤의 개수 = 마지막 행의 번호
        if (at != last) {
            val movedId = ids[last]
            ids[at] = movedId
            mat.copyInto(mat, at * dim, last * dim, (last + 1) * dim)
            rowOf[movedId] = at
        }
    }

    @Synchronized
    fun contains(id: Long) = rowOf.containsKey(id)

    private fun grow() {
        ids = ids.copyOf(maxOf(16, ids.size * 2))
        mat = mat.copyOf(ids.size * dim)
    }

    /**
     * (id, 유사도) 상위 k개. [allow]가 주어지면 그 안에서만 찾는다(질의 파서의 필터).
     *
     * 전체를 정렬하지 않고 크기 k짜리 최소 힙으로 고른다 — O(n log k). 결과 순서는 점수 내림차순이다.
     */
    @Synchronized
    fun topK(query: FloatArray, k: Int, allow: Set<Long>? = null): List<Pair<Long, Float>> {
        require(query.size == dim) { "차원이 다르다: ${query.size} ≠ $dim" }
        if (k <= 0) return emptyList()
        val heap = PriorityQueue<Pair<Long, Float>>(k + 1, compareBy<Pair<Long, Float>> { it.second })
        for (i in 0 until rowOf.size) {
            if (allow != null && ids[i] !in allow) continue
            var s = 0f
            val off = i * dim
            for (d in 0 until dim) s += mat[off + d] * query[d]
            if (heap.size < k) heap.add(ids[i] to s)
            else if (s > heap.peek().second) { heap.poll(); heap.add(ids[i] to s) }
        }
        // 정규화된 벡터끼리의 내적이므로 이 값이 곧 코사인이다.
        return heap.sortedByDescending { it.second }
    }

    /** 이 사진과 비슷한 것들 (자기 자신 제외). 유사중복 후보를 찾는 데도 쓴다. */
    @Synchronized
    fun similarTo(id: Long, k: Int, minScore: Float = 0f): List<Pair<Long, Float>> {
        val at = rowOf[id] ?: return emptyList()
        val v = FloatArray(dim)
        mat.copyInto(v, 0, at * dim, (at + 1) * dim)
        return topK(v, k + 1).filter { it.first != id && it.second >= minScore }.take(k)
    }

    companion object {
        fun of(entries: List<Pair<Long, FloatArray>>, dim: Int = Embeddings.DIM) =
            SearchIndex(maxOf(16, entries.size), dim).apply { entries.forEach { put(it.first, it.second) } }
    }
}
