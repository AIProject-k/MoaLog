package kr.slz.photolog.core

import org.json.JSONArray
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 패리티 계약 대조. 데스크톱(`eval/make_fixtures.py`)이 만든 305문장의 토큰 id 열과
 * 정확히 같아야 한다 — 토큰은 정수라 '거의 같다'가 없고, 하나만 달라도 다른 문장이 된다.
 *
 * 기기가 필요 없다. 포팅에서 가장 위험한 조각을 JVM에서 먼저 끝내기 위한 배치다.
 */
class BpeTokenizerTest {

    private val fixtures = File("../../eval/fixtures").canonicalFile
    private val tok by lazy { BpeTokenizer.fromDir(File(fixtures, "tokenizer")) }

    private fun cases(): List<Pair<String, List<Int>>> {
        val arr = JSONArray(File(fixtures, "tokens_ko.json").readText())
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val ids = o.getJSONArray("ids")
            o.getString("text") to List(ids.length()) { ids.getInt(it) }
        }
    }

    @Test
    fun `계약 파일이 있다`() {
        assertTrue(fixtures.isDirectory,
            "eval/fixtures가 없다. python eval/make_fixtures.py 를 먼저 실행할 것 ($fixtures)")
    }

    @Test
    fun `305문장이 데스크톱과 정확히 일치한다`() {
        val all = cases()
        assertTrue(all.size >= 300, "계약 문장이 ${all.size}개뿐이다")
        val bad = all.filter { (text, want) -> tok.encode(text) != want }
        if (bad.isNotEmpty()) {
            val report = bad.take(5).joinToString("\n") { (text, want) ->
                "  ${text.take(40)}\n    기대 $want\n    실제 ${tok.encode(text)}"
            }
            error("${bad.size}/${all.size} 불일치:\n$report")
        }
    }

    /** ORT-extensions op이 깨졌던 바로 그 지점. 여기가 통과해야 대체한 의미가 있다. */
    @Test
    fun `연속된 숫자를 한 자씩 끊는다`() {
        val byText = cases().toMap()
        for (t in listOf("12월", "2026년", "iPhone 15 Pro", "2026년 1월 영수증")) {
            val want = byText[t] ?: error("계약에 '$t'가 없다")
            assertEquals(want, tok.encode(t), "'$t'에서 갈렸다 — 정규식이 [\\p{N}]+로 묶고 있지 않은지 볼 것")
        }
    }

    @Test
    fun `패딩은 77이고 언제나 eos로 끝난다`() {
        val short = tok.encodePadded("고양이")
        assertEquals(77, short.size)
        assertEquals(49407L, short.last())
        val long = tok.encodePadded("긴 문장 ".repeat(60))
        assertEquals(77, long.size)
        assertEquals(49407L, long.last(), "잘려도 eos로 끝나야 한다")
    }

    @Test
    fun `빈 문자열은 bos eos만 낸다`() {
        assertEquals(listOf(49406, 49407), tok.encode(""))
        assertEquals(listOf(49406, 49407), tok.encode("   "))
    }
}
