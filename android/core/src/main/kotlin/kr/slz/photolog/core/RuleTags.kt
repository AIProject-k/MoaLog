package kr.slz.photolog.core

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** 촬영시각의 출처. **이 구분이 연사 판정과 로그 품질을 지킨다**(ANDROID.md §4.5). */
enum class TimeSource { EXIF, FILE, NONE;
    companion object {
        fun parse(s: String?) = when (s) {
            "exif" -> EXIF; "mtime", "file" -> FILE; else -> NONE
        }
    }
}

/**
 * 사진 한 장의 메타데이터. CLIP 이전에 알 수 있는 것 전부 —
 * Pass 1은 이것만으로 로그와 규칙 태그를 만든다(§3).
 */
data class PhotoMeta(
    val width: Int = 0,
    val height: Int = 0,
    val cameraModel: String? = null,
    val takenAt: Long? = null,              // epoch seconds
    val takenAtSource: TimeSource = TimeSource.NONE,
    val hasGps: Boolean = false,
    val relativePath: String? = null,       // MediaStore RELATIVE_PATH, 예: "Pictures/Screenshots/"
    val ownerPackage: String? = null,       // MediaStore OWNER_PACKAGE_NAME
    val isDownload: Boolean = false,
)

/**
 * 규칙 태그 (Tier 0, 비용 0). `pipeline.py`의 `rule_tags()` + 안드로이드 출처 판정.
 *
 * 데스크톱은 EXIF 카메라 유무와 화면비만으로 갈랐다. 안드로이드는 **누가 이 파일을
 * 넣었는지**를 알려 주므로 훨씬 강한 신호를 쓴다 — 스크린샷 폴더에서 온 파일은
 * CLIP이 뭐라고 하든 화면캡처다. README의 '되돌린 시도'(문서 vs 화면캡처를 문장으로
 * 못 가른다)가 여기서 풀린다.
 *
 * 규칙은 코드가 아니라 `config.provenance_rules`에 있다. USB로 복사한 파일은
 * ownerPackage가 비어 오므로 화면비 휴리스틱 폴백을 남긴다.
 */
class RuleTagger(
    private val screenRatios: List<Double>,
    private val ratioTolerance: Double,
    private val provenance: List<Rule>,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    data class Rule(val tag: String, val pathPrefixes: List<String>, val packages: List<String>)

    fun tags(m: PhotoMeta): List<Tag> {
        val out = ArrayList<Tag>(4)
        out.add(Tag(provenanceTag(m), null, "rule"))

        // 촬영시각을 실제로 아는 경우에만 야간 판정.
        // 파일 시각의 '시'는 찍은 시각이 아니다 — 새벽에 내려받은 낮 사진이 야간이 된다.
        if (m.takenAt != null && m.takenAtSource == TimeSource.EXIF) {
            val hour = Instant.ofEpochSecond(m.takenAt).atZone(zone).hour
            if (hour >= 20 || hour < 6) out.add(Tag("야간", null, "rule"))
        }
        if (m.hasGps) out.add(Tag("위치있음", null, "rule"))
        return out
    }

    /** 우선순위대로 본다: 설정에 적힌 규칙 → 카메라 EXIF → 화면비 → 기본값. */
    fun provenanceTag(m: PhotoMeta): String {
        val path = m.relativePath?.lowercase().orEmpty()
        val pkg = m.ownerPackage?.lowercase().orEmpty()
        for (r in provenance) {
            if (r.pathPrefixes.any { path.startsWith(it) || path.contains(it) }) return r.tag
            if (pkg.isNotEmpty() && r.packages.any { pkg == it || pkg.startsWith(it) }) return r.tag
        }
        if (!m.cameraModel.isNullOrBlank()) return "카메라촬영"
        if (looksLikeScreen(m)) return "스크린샷"
        if (m.isDownload) return "다운로드"
        return "저장/다운로드"
    }

    /** 화면비가 흔한 폰 화면 비율에 가까우면 스크린샷으로 본다 (출처를 모를 때의 폴백). */
    fun looksLikeScreen(m: PhotoMeta): Boolean {
        if (m.width <= 0 || m.height <= 0) return false
        val ratio = maxOf(m.width, m.height).toDouble() / minOf(m.width, m.height)
        return screenRatios.any { abs(ratio - it) / it <= ratioTolerance }
    }

    companion object {
        fun from(config: JSONObject, zone: ZoneId = ZoneId.systemDefault()): RuleTagger {
            val ratios = config.getJSONArray("screen_ratios")
                .let { a -> List(a.length()) { a.getDouble(it) } }
            val rules = config.optJSONArray("provenance_rules")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    Rule(
                        tag = o.getString("tag"),
                        pathPrefixes = o.optJSONArray("paths")
                            ?.let { a -> (0 until a.length()).map { a.getString(it).lowercase() } }
                            ?: emptyList(),
                        packages = o.optJSONArray("packages")
                            ?.let { a -> (0 until a.length()).map { a.getString(it).lowercase() } }
                            ?: emptyList(),
                    )
                }
            } ?: emptyList()
            return RuleTagger(ratios, config.getDouble("screen_ratio_tolerance"), rules, zone)
        }
    }
}
