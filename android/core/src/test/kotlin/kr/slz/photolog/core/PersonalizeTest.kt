package kr.slz.photolog.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 개인화는 학습이 아니라 기준점 이동이다. 그 성질을 못박는다. */
class PersonalizeTest {

    private fun vec(seed: Int) = Embeddings.unit(FloatArray(512) { Random(seed).let { r -> repeat(it) { r.nextFloat() }; r.nextFloat() - .5f } })
    private fun near(base: FloatArray, seed: Int, eps: Float = .15f): FloatArray {
        val r = Random(seed)
        return Embeddings.unit(FloatArray(512) { base[it] + eps * (r.nextFloat() - .5f) })
    }

    @Test fun `확정이 부족하면 프로토타입을 건드리지 않는다`() {
        val text = vec(1)
        val out = Personalize.blend(text, listOf(vec(2), vec(3)), alpha = .5f, minConfirmed = 5)
        assertTrue(out === text, "2장으로 프로토타입을 끌었다 — 그 사진들만 맞고 나머지가 틀린다")
    }

    @Test fun `확정 사진 쪽으로 기준점이 움직인다`() {
        val text = vec(1)
        val user = vec(7)                                   // 이 사용자의 '음식'은 텍스트 기준과 다르다
        val confirmed = (0 until 6).map { near(user, 100 + it) }
        val out = Personalize.blend(text, confirmed, alpha = .5f, minConfirmed = 5)

        assertTrue(Embeddings.dot(out, user) > Embeddings.dot(text, user),
            "확정 사진과 더 비슷해져야 한다")
        assertTrue(Embeddings.dot(out, text) > 0.5f,
            "원래 텍스트 기준을 완전히 버리면 안 된다 — α는 섞는 비율이지 대체가 아니다")
        assertTrue(kotlin.math.abs(Embeddings.dot(out, out) - 1f) < 1e-4f, "결과는 단위벡터여야 한다")
    }

    @Test fun `이동한 기준점은 비슷한 새 사진을 더 잘 잡는다`() {
        val text = vec(1)
        val user = vec(7)
        val confirmed = (0 until 6).map { near(user, 100 + it) }
        val heldOut = near(user, 999)                       // 확정에 쓰지 않은 같은 부류의 사진
        val before = Embeddings.dot(text, heldOut)
        val after = Embeddings.dot(Personalize.blend(text, confirmed, .5f, 5), heldOut)
        assertTrue(after > before, "개인화가 같은 부류의 새 사진 점수를 올리지 못했다 ($before → $after)")
    }

    @Test fun `alpha 0이면 아무것도 바꾸지 않는다`() {
        val text = vec(1)
        val out = Personalize.blend(text, (0 until 6).map { vec(it) }, alpha = 0f, minConfirmed = 5)
        assertTrue(out === text)
    }

    @Test fun `행렬 적용은 확정이 있는 라벨만 바꾼다`() {
        val m = PromptMatrix(listOf("음식", "인물"), arrayOf(vec(1), vec(2)))
        val out = Personalize.apply(m, { if (it == "음식") (0 until 6).map { vec(50 + it) } else emptyList() }, .5f, 5)
        assertEquals(m.labels, out.labels)
        assertTrue(out.rows[1] === m.rows[1], "확정이 없는 '인물'은 그대로여야 한다")
        assertTrue(out.rows[0] !== m.rows[0], "'음식'은 바뀌어야 한다")
    }
}
