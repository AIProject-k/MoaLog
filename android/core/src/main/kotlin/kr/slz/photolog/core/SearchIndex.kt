package kr.slz.photolog.core

/**
 * 검색 인덱스. 임베딩 전체를 **한 번** 메모리에 올려 두고 내적으로 훑는다.
 *
 * 데스크톱은 질의마다 임베딩을 전량 SELECT했다(400장 0.8MB라 티가 안 났다). 폰에서
 * 1만 장이면 질의당 20MB 읽기라 그대로는 못 간다. 대신 시작할 때 한 번 로드한다 —
 * 1만 장 × 512 × 4B = 20MB, 로드 ~100ms, 질의당 5M MAC ≈ 5~10ms. 벡터DB는 필요 없다.
 *
 * `ponytail:` fp32 완전탐색이다. 5만 장을 넘으면 fp16(절반) → int8 + 청크 스캔으로
 *            올릴 것. 그 전엔 손대지 않는다.
 */
class SearchIndex(capacity: Int = 1024, private val dim: Int = Embeddings.DIM) {

    private var ids = LongArray(capacity)
    private var mat = FloatArray(capacity * dim)
    var size: Int = 0
        private set

    /** 이미 있는 id면 벡터만 갈아 끼운다 (재인덱싱). 없으면 뒤에 붙인다. */
    fun put(id: Long, vec: FloatArray) {
        require(vec.size == dim) { "차원이 다르다: ${vec.size} ≠ $dim" }
        val at = indexOf(id).takeIf { it >= 0 } ?: run {
            if (size == ids.size) grow()
            ids[size] = id
            size++
            size - 1
        }
        vec.copyInto(mat, at * dim)
    }

    fun remove(id: Long) {
        val at = indexOf(id)
        if (at < 0) return
        val last = size - 1
        if (at != last) {
            ids[at] = ids[last]
            mat.copyInto(mat, at * dim, last * dim, (last + 1) * dim)
        }
        size--
    }

    fun contains(id: Long) = indexOf(id) >= 0

    private fun indexOf(id: Long): Int {
        for (i in 0 until size) if (ids[i] == id) return i
        return -1
    }

    private fun grow() {
        ids = ids.copyOf(maxOf(16, ids.size * 2))
        mat = mat.copyOf(ids.size * dim)
    }

    /** (id, 유사도) 상위 k개. [allow]가 주어지면 그 안에서만 찾는다(질의 파서의 필터). */
    fun topK(query: FloatArray, k: Int, allow: Set<Long>? = null): List<Pair<Long, Float>> {
        require(query.size == dim) { "차원이 다르다: ${query.size} ≠ $dim" }
        val hits = ArrayList<Pair<Long, Float>>(minOf(size, 256))
        for (i in 0 until size) {
            if (allow != null && ids[i] !in allow) continue
            var s = 0f
            val off = i * dim
            for (d in 0 until dim) s += mat[off + d] * query[d]
            hits.add(ids[i] to s)
        }
        // 정규화된 벡터끼리의 내적이므로 이 값이 곧 코사인이다.
        return hits.sortedByDescending { it.second }.take(k)
    }

    /** 이 사진과 비슷한 것들 (자기 자신 제외). 유사중복 후보를 찾는 데도 쓴다. */
    fun similarTo(id: Long, k: Int, minScore: Float = 0f): List<Pair<Long, Float>> {
        val at = indexOf(id)
        if (at < 0) return emptyList()
        val v = FloatArray(dim)
        mat.copyInto(v, 0, at * dim, (at + 1) * dim)
        return topK(v, k + 1).filter { it.first != id && it.second >= minScore }.take(k)
    }

    companion object {
        fun of(entries: List<Pair<Long, FloatArray>>, dim: Int = Embeddings.DIM) =
            SearchIndex(maxOf(16, entries.size), dim).apply { entries.forEach { put(it.first, it.second) } }
    }
}
