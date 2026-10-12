package com.moalog.personal.core

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchIndexTest {

    private val dim = 16

    private fun vec(seed: Long): FloatArray {
        val r = Random(seed)
        return Embeddings.unit(FloatArray(dim) { r.nextFloat() - .5f })
    }

    /** 기준 답: 전부 점수로 정렬해 앞에서 k개. 힙 결과와 비교한다. */
    private fun brute(vectors: Map<Long, FloatArray>, q: FloatArray, k: Int, allow: Set<Long>? = null) =
        vectors.filterKeys { allow == null || it in allow }
            .map { (id, v) -> id to Embeddings.dot(v, q) }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }

    @Test fun `질의와 같은 벡터가 1순위다`() {
        val idx = SearchIndex(dim = dim)
        for (i in 1L..50L) idx.put(i, vec(i))
        val top = idx.topK(vec(7), 3)
        assertEquals(7L, top.first().first)
        assertTrue(top.first().second > 0.999f, "같은 벡터의 점수가 1이 아니다: ${top.first().second}")
    }

    @Test fun `같은 id를 다시 넣으면 벡터만 바뀌고 개수는 그대로다`() {
        val idx = SearchIndex(dim = dim)
        idx.put(1L, vec(1))
        idx.put(1L, vec(2))
        assertEquals(1, idx.size)
        assertEquals(1L, idx.topK(vec(2), 1).single().first)
    }

    @Test fun `중간 id를 지워도 나머지 벡터가 엉키지 않는다`() {
        val idx = SearchIndex(dim = dim)
        val vectors = (1L..10L).associateWith { vec(it) }
        vectors.forEach { (id, v) -> idx.put(id, v) }

        idx.remove(3L)

        assertEquals(9, idx.size)
        assertFalse(idx.contains(3L))
        // 마지막 행이 빈자리로 옮겨졌으므로, 옮겨진 id도 자기 벡터로 1순위에 나와야 한다.
        for ((id, v) in vectors) if (id != 3L) {
            assertEquals(id, idx.topK(v, 1).single().first, "id $id 의 벡터가 옮긴 뒤 엉켰다")
        }
    }

    @Test fun `없는 id를 지워도 아무 일도 없다`() {
        val idx = SearchIndex(dim = dim)
        idx.put(1L, vec(1))
        idx.remove(99L)
        assertEquals(1, idx.size)
    }

    @Test fun `상위 k개는 전수 정렬 결과와 같다`() {
        val idx = SearchIndex(dim = dim)
        val vectors = (1L..300L).associateWith { vec(it) }
        vectors.forEach { (id, v) -> idx.put(id, v) }
        for (qs in 1L..20L) {
            val q = vec(10_000L + qs)
            assertEquals(brute(vectors, q, 5), idx.topK(q, 5).map { it.first }, "질의 $qs")
        }
    }

    @Test fun `허용 목록 안에서만 찾는다`() {
        val idx = SearchIndex(dim = dim)
        val vectors = (1L..200L).associateWith { vec(it) }
        vectors.forEach { (id, v) -> idx.put(id, v) }
        val allow = (1L..200L).filter { it % 3 == 0L }.toSet()
        val q = vec(555)
        val got = idx.topK(q, 7, allow)
        assertTrue(got.all { it.first in allow })
        assertEquals(brute(vectors, q, 7, allow), got.map { it.first })
    }

    @Test fun `자기 자신을 뺀 비슷한 것들을 준다`() {
        val idx = SearchIndex(dim = dim)
        val vectors = (1L..100L).associateWith { vec(it) }
        vectors.forEach { (id, v) -> idx.put(id, v) }
        val sims = idx.similarTo(42L, 4)
        assertEquals(4, sims.size)
        assertFalse(sims.any { it.first == 42L })
        val expected = brute(vectors, vectors.getValue(42L), 5).filter { it != 42L }.take(4)
        assertEquals(expected, sims.map { it.first })
    }

    @Test fun `없는 id의 비슷한 것은 빈 목록이다`() {
        val idx = SearchIndex(dim = dim)
        idx.put(1L, vec(1))
        assertTrue(idx.similarTo(2L, 3).isEmpty())
    }

    @Test fun `k가 개수보다 크거나 0이면 있는 만큼만, 0이면 아무것도 안 준다`() {
        val idx = SearchIndex(dim = dim)
        for (i in 1L..4L) idx.put(i, vec(i))
        assertEquals(4, idx.topK(vec(1), 10).size)
        assertTrue(idx.topK(vec(1), 0).isEmpty())
    }

    @Test fun `쓰는 중에 읽어도 예외가 나지 않고 개수가 맞는다`() {
        val idx = SearchIndex(capacity = 4, dim = dim)   // 일부러 작게 시작해 grow가 여러 번 일어나게 한다
        val errors = ConcurrentLinkedQueue<Throwable>()
        val writers = (0 until 4).map { t ->
            thread {
                try {
                    for (i in 0 until 300) idx.put(t * 10_000L + i, vec(t * 10_000L + i))
                } catch (e: Throwable) { errors.add(e) }
            }
        }
        val reader = thread {
            try {
                val q = vec(1)
                repeat(300) { idx.topK(q, 5) }
            } catch (e: Throwable) { errors.add(e) }
        }
        (writers + reader).forEach { it.join() }
        assertTrue(errors.isEmpty(), "동시 접근에서 예외: ${errors.firstOrNull()}")
        assertEquals(4 * 300, idx.size)
    }
}
