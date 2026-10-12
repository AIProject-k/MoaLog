package com.moalog.personal.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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

    /**
     * 실제 픽스처 64장으로 본 회귀 방지. 한 라벨에서 확정 사진을 모아 개인화를 적용해도
     * 다른 라벨 사진의 1순위는 그대로여야 한다. α=0.5였을 때는 이게 깨졌다
     * (음식 5장 확정 → 비음식 사진까지 '음식' — 글/사진 벡터의 모달리티 갭 때문).
     */
    @Test fun `확정을 쌓아도 다른 라벨 사진의 1순위는 바뀌지 않는다`() {
        val fixtures = File("../../eval/fixtures").canonicalFile
        val config = JSONObject(File("../../config.json").canonicalFile.readText())
        val manifest = JSONObject(File(fixtures, "manifest.json").readText())
        val meta = manifest.getJSONObject("prompts")
        fun matrix(kind: String) = PromptMatrix(
            meta.getJSONObject(kind).getJSONArray("labels").let { a -> List(a.length()) { a.getString(it) } },
            Embeddings.read(File(fixtures, "prompts_$kind.bin")))

        val vecs = Embeddings.read(File(fixtures, "images_ko.bin"))
        val arr = JSONArray(File(fixtures, "images_ko.json").readText())
        val top1 = List(arr.length()) { arr.getJSONObject(it).getString("top1") }
        val cats = matrix("categories")
        val alpha = config.optDouble("personalize_alpha", 0.5).toFloat()
        val minN = config.optInt("personalize_min_confirmed", 5)

        // 확정이 가장 많이 쌓일 수 있는 라벨 = 1순위가 가장 흔한 라벨
        val label = top1.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        val confirmed = vecs.filterIndexed { i, _ -> top1[i] == label }
        assertTrue(confirmed.size >= minN, "'$label' 확정 후보가 ${confirmed.size}장뿐이라 시험이 안 된다 — 픽스처가 바뀌었나")

        val tuned = Personalize.apply(cats, { if (it == label) confirmed else emptyList() }, alpha, minN)
        fun top(m: PromptMatrix, v: FloatArray) = m.labels[m.scores(v).withIndex().maxByOrNull { it.value }!!.index]
        val flipped = vecs.indices.filter { top1[it] != label && top(tuned, vecs[it]) == label }
        assertTrue(flipped.isEmpty(),
            "'$label' ${confirmed.size}장 확정 뒤 다른 라벨 사진 ${flipped.size}장이 '$label'로 쏠렸다 (α=$alpha)")
    }
}
