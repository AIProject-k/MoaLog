package kr.slz.photolog.core

import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * CLIP 바이트 레벨 BPE 토크나이저.
 *
 * 왜 직접 짜는가: ONNX Runtime Extensions의 `CLIPTokenizer` op은 사전 분할 정규식이
 * C++에 박혀 있어(`[\p{N}]+`, 숫자를 묶음) 이 한국어 모델의 tokenizer.json이 선언한
 * 정규식(`[\p{N}]`, 한 자씩)을 재현하지 못한다. "2026년"·"iPhone 15"에서 갈리고,
 * op에 정규식 속성이 없어 재export로도 못 고친다. 자세한 근거는 ANDROID.md §7.3.
 *
 * **규칙을 코드에 박지 않는다.** 정규식·특수토큰·접미사는 전부 config.json에서 읽는다.
 * 교과서 CLIP 정규식을 하드코딩하는 순간 위와 똑같은 함정에 빠진다.
 *
 * 자산은 데스크톱의 `eval/make_fixtures.py`가 만든다:
 *   vocab.txt    한 줄에 토큰 하나, 줄 번호 = id
 *   merges.txt   한 줄에 "a b"
 *   config.json  정규식 · 정규화 순서 · 접미사 · bos/eos · ctx
 *
 * 검증은 `eval/fixtures/tokens_ko.json`(305문장)과 정확히 일치하는지로 한다.
 * 토큰은 정수라 '거의 같다'가 없다 — 하나만 달라도 다른 문장이 된다.
 */
class BpeTokenizer(
    private val vocab: Map<String, Int>,
    merges: List<String>,
    splitRegex: String,
    private val normalize: List<String>,
    private val endOfWordSuffix: String,
    private val bosId: Int,
    private val eosId: Int,
    private val unkId: Int,
    val ctx: Int,
) {
    /** 병합 규칙 → 순위. 낮을수록 먼저 합친다. */
    private val ranks: Map<Pair<String, String>, Int> = HashMap<Pair<String, String>, Int>(merges.size * 2).apply {
        merges.forEachIndexed { i, line ->
            val sp = line.indexOf(' ')
            if (sp > 0) put(line.substring(0, sp) to line.substring(sp + 1), i)
        }
    }

    // 정규식 플래그를 쓰지 않는다. UNICODE_CHARACTER_CLASS는 JVM에는 있지만
    // **안드로이드(ICU)에서는 IllegalArgumentException으로 터진다** — JVM 테스트만으로는
    // 안 잡히고 기기에서야 드러났다. 대신 유니코드 공백을 문자로 직접 나열해 양쪽을 맞춘다.
    //
    // splitRegex 안의 \s는 ASCII 공백만 잡아도 된다: 정규화가 먼저 돌면서 모든 유니코드
    // 공백이 U+0020 하나로 바뀌어 있기 때문이다. 그 정규화가 아래 whitespace 패턴이다.
    private val splitter: Pattern = Pattern.compile(splitRegex)
    private val whitespace: Pattern = Pattern.compile("[$UNICODE_WS]+")

    private val cache = HashMap<String, List<Int>>()

    /** 문장 → 토큰 id 열 (bos·eos 포함, 패딩 없음). */
    fun encode(text: String): List<Int> {
        val out = ArrayList<Int>(16)
        out.add(bosId)
        val m = splitter.matcher(normalizeText(text))
        while (m.find()) out.addAll(bpe(m.group()))
        out.add(eosId)
        return out
    }

    /** 모델 입력용. ctx(77)로 자르고 eos로 채운다. eos가 패딩이라 마스크가 필요 없다. */
    fun encodePadded(text: String): LongArray {
        val ids = encode(text)
        val n = minOf(ids.size, ctx)
        return LongArray(ctx) { i -> (if (i < n) ids[i] else eosId).toLong() }
                .also { if (ids.size > ctx) it[ctx - 1] = eosId.toLong() }  // 잘려도 eos로 끝낸다
    }

    private fun normalizeText(s: String): String {
        var t = s
        for (step in normalize) t = when (step) {
            "NFC" -> Normalizer.normalize(t, Normalizer.Form.NFC)
            "Replace" -> whitespace.matcher(t).replaceAll(" ")
            "Lowercase" -> t.lowercase()
            else -> t                                   // 모르는 단계는 건너뛴다 (config가 앞선다)
        }
        return t
    }

    private fun bpe(piece: String): List<Int> = cache.getOrPut(piece) {
        // 바이트 레벨: UTF-8 바이트를 눈에 보이는 문자로 옮긴다. 마지막 심볼에만 </w>를 붙인다.
        val bytes = piece.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return@getOrPut emptyList()
        var syms = ArrayList<String>(bytes.size)
        for (b in bytes) syms.add(BYTE_ENCODER[b.toInt() and 0xFF])
        syms[syms.size - 1] = syms[syms.size - 1] + endOfWordSuffix

        while (syms.size > 1) {
            var bestRank = Int.MAX_VALUE
            var bestAt = -1
            for (i in 0 until syms.size - 1) {
                val r = ranks[syms[i] to syms[i + 1]] ?: continue
                if (r < bestRank) { bestRank = r; bestAt = i }
            }
            if (bestAt < 0) break
            val merged = ArrayList<String>(syms.size - 1)
            var i = 0
            while (i < syms.size) {
                // 같은 규칙이 여러 곳에 걸리면 왼쪽부터 전부 합친다 (원본 BPE와 같은 동작).
                if (i < syms.size - 1 && ranks[syms[i] to syms[i + 1]] == bestRank) {
                    merged.add(syms[i] + syms[i + 1]); i += 2
                } else {
                    merged.add(syms[i]); i++
                }
            }
            syms = merged
        }
        syms.map { vocab[it] ?: unkId }
    }

    companion object {
        /**
         * 유니코드 White_Space. 자바의 `\s`는 ASCII만 잡고 ICU는 더 잡아서, 그대로 두면
         * 데스크톱(Rust regex, 유니코드)과 전각 공백·NBSP에서 갈린다. 직접 적어 못박는다.
         */
        private const val UNICODE_WS = "\\t\\n\\u000B\\f\\r \\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000"

        /**
         * GPT-2/CLIP 바이트 인코더. 256개 바이트를 제어문자가 아닌 문자로 1:1 대응시킨다.
         * 이 표가 있어야 임의의 바이트열을 문자열 BPE로 다룰 수 있다.
         */
        private val BYTE_ENCODER: Array<String> = buildByteEncoder()

        private fun buildByteEncoder(): Array<String> {
            val bs = ArrayList<Int>()
            (0x21..0x7E).forEach { bs.add(it) }        // '!'..'~'
            (0xA1..0xAC).forEach { bs.add(it) }        // '¡'..'¬'
            (0xAE..0xFF).forEach { bs.add(it) }        // '®'..'ÿ'
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0..255) if (b !in bs) { bs.add(b); cs.add(256 + n); n++ }
            val out = arrayOfNulls<String>(256)
            for (i in bs.indices) out[bs[i]] = cs[i].toChar().toString()
            @Suppress("UNCHECKED_CAST")
            return out as Array<String>
        }

        /** `eval/fixtures/tokenizer/` 또는 안드로이드 assets에서 푼 같은 구조의 디렉터리에서 만든다. */
        fun fromDir(dir: File): BpeTokenizer = build(
            File(dir, "config.json").readText(),
            File(dir, "vocab.txt").readLines(),
            File(dir, "merges.txt").readLines(),
        )

        /**
         * 원문 3개로 만든다. 안드로이드는 assets 스트림을, 테스트는 파일을 넘긴다.
         * org.json은 안드로이드 플랫폼에 들어 있어 의존성이 늘지 않는다.
         */
        fun build(configJson: String, vocabLines: List<String>, merges: List<String>): BpeTokenizer {
            val cfg = JSONObject(configJson)
            val vocab = HashMap<String, Int>(vocabLines.size * 2)
            vocabLines.forEachIndexed { i, t -> vocab[t] = i }
            val norm = cfg.getJSONArray("normalize").let { a -> List(a.length()) { a.getString(it) } }
            val eos = cfg.getInt("eos_id")
            return BpeTokenizer(
                vocab = vocab,
                merges = merges,
                splitRegex = cfg.getString("split_regex"),
                normalize = norm,
                endOfWordSuffix = cfg.getString("end_of_word_suffix"),
                bosId = cfg.getInt("bos_id"),
                eosId = eos,
                unkId = vocab[cfg.getString("unk_token")] ?: eos,
                ctx = cfg.getInt("ctx"),
            )
        }
    }
}
