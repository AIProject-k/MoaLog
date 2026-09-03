package kr.slz.photolog.core

/**
 * 중복·연사 그룹핑. `pipeline.py`의 `group_dupes` / `_group_bursts` / `origin_rank`를 옮긴 것.
 *
 * 전부 순수 함수다 — Room도 MediaStore도 모른다. 그래야 기기 없이 검증된다.
 */
object Grouping {

    /** 그룹 한 개. `bestId`는 **남길** 사진이다. */
    data class Group(val kind: Kind, val ids: List<Long>, val bestId: Long)

    enum class Kind { EXACT, BURST }

    /** 그룹핑 입력. 필요한 것만 받는다. */
    data class Item(
        val id: Long,
        val displayName: String,
        val sha256: String? = null,
        val takenAt: Long? = null,
        val takenAtSource: TimeSource = TimeSource.NONE,
        val blurScore: Double? = null,
        val embedding: FloatArray? = null,
    ) {
        // data class + FloatArray는 equals/hashCode가 참조 비교라 혼란을 준다.
        // 그룹핑에 필요한 것은 id 동일성뿐이므로 명시적으로 고정한다.
        override fun equals(other: Any?) = other is Item && other.id == id
        override fun hashCode() = id.hashCode()
    }

    /**
     * 완전중복 중 '원본일 가능성' 순위. 작을수록 원본.
     *
     * 스캔 순서(id)로 고르면 안 된다 — 'cafe_01 - 복사본.jpg'가 'cafe_01.jpg'보다
     * 사전순으로 앞서서 사본을 원본으로 골라 버린다. 사본은 이름에 접미사가 붙어
     * 길어진다는 성질을 쓴다.
     */
    fun originRank(displayName: String, copyMarkers: List<String>): Triple<Int, Int, String> {
        val stem = displayName.substringBeforeLast('.', displayName).lowercase()
        val marked = copyMarkers.any { stem.contains(it.lowercase()) } || COPY_SUFFIX.containsMatchIn(stem)
        return Triple(if (marked) 1 else 0, displayName.length, displayName)
    }

    private val COPY_SUFFIX = Regex("""[(\[]\d+[)\]]\s*$""")

    private val RANK = compareBy<Triple<Int, Int, String>>({ it.first }, { it.second }, { it.third })

    /** SHA-256이 같은 것들. 후보를 미리 좁혀 두고(크기·해상도) 해시한 결과를 넣는다(§12.1). */
    fun exactGroups(items: List<Item>, copyMarkers: List<String>): List<Group> =
        items.filter { it.sha256 != null }
            .groupBy { it.sha256!! }
            .values.filter { it.size > 1 }
            .map { members ->
                val best = members.minWithOrNull(compareBy(RANK) { originRank(it.displayName, copyMarkers) })!!
                Group(Kind.EXACT, members.map { it.id }, best.id)
            }

    /**
     * 연사. **세 조건을 모두 AND로 건다.**
     *
     * 1. 임베딩 코사인 ≥ [similarity] — 첫 장을 기준으로 잰다(사슬처럼 이어지면 서로 다른
     *    사진이 한 그룹에 들어온다)
     * 2. 직전 사진과의 시간차 ≤ [gapSec]
     * 3. **두 장 모두 촬영시각이 EXIF에서 온 것** — 이게 없으면 시간 조건이 무력해진다.
     *    파일 시각은 '파일이 생긴 시각'이라 한 번에 내려받은 사진들이 전부 초 단위로
     *    붙어 버리고, 그러면 유사도만으로 그룹이 정해진다. 데스크톱 400장 중 398장이
     *    그 상태였고 시간 조건이 한 번도 작동하지 않았다 (ANDROID.md §12.2).
     *
     * @param excluded 이미 완전중복으로 묶인 id. 한 사진이 두 그룹에 들어가지 않게 한다.
     */
    fun burstGroups(
        items: List<Item>,
        similarity: Float,
        gapSec: Long,
        excluded: Set<Long> = emptySet(),
    ): List<Group> {
        val rows = items
            .filter { it.embedding != null && it.takenAt != null && it.takenAtSource == TimeSource.EXIF }
            .sortedBy { it.takenAt }
        if (rows.size < 2) return emptyList()

        val out = ArrayList<Group>()
        var run = mutableListOf(0)
        for (i in 1..rows.size) {
            val cont = i < rows.size &&
                rows[i].takenAt!! - rows[i - 1].takenAt!! <= gapSec &&
                Embeddings.dot(rows[i].embedding!!, rows[run[0]].embedding!!) >= similarity
            if (cont) { run.add(i); continue }
            if (run.size > 1 && run.none { rows[it].id in excluded }) {
                // 가장 선명한 것을 남긴다. 연사는 같은 장면이니 흔들린 쪽을 버리면 된다.
                val best = run.maxByOrNull { rows[it].blurScore ?: 0.0 }!!
                out.add(Group(Kind.BURST, run.map { rows[it].id }, rows[best].id))
            }
            run = mutableListOf(i)
        }
        return out
    }

    /** 완전중복 먼저, 그 다음 연사. 순서가 중요하다 — 연사는 이미 묶인 것을 건너뛴다. */
    fun all(items: List<Item>, copyMarkers: List<String>, similarity: Float, gapSec: Long): List<Group> {
        val exact = exactGroups(items, copyMarkers)
        val taken = exact.flatMap { it.ids }.toSet()
        return exact + burstGroups(items, similarity, gapSec, taken)
    }

    /**
     * 한 중복 그룹의 사본을 **전부** 지우려 하면 1장은 남긴다.
     *
     * 중복 정리는 '한 장만 남기기'지 '사진을 없애기'가 아니다. 놓친 중복은 다음에 잡으면
     * 되지만 잘못 지운 사진은 돌아오지 않는다. UI가 아니라 여기서 강제한다 — 화면을
     * 다시 짜도 이 보호는 남아야 한다.
     *
     * @return (실제로 지울 id, 강제로 남긴 id)
     */
    fun keepOnePerGroup(wanted: Set<Long>, groups: List<Group>): Pair<List<Long>, List<Long>> {
        val remain = wanted.toMutableSet()
        val kept = ArrayList<Long>()
        for (g in groups) {
            val ids = g.ids.toSet()
            if (ids.isNotEmpty() && remain.containsAll(ids)) {
                val survivor = if (g.bestId in ids) g.bestId else ids.min()
                remain.remove(survivor)
                kept.add(survivor)
            }
        }
        return remain.toList() to kept
    }
}
