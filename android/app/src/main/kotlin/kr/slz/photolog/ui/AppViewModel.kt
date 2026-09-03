package kr.slz.photolog.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kr.slz.photolog.AppGraph
import kr.slz.photolog.core.Classifier
import kr.slz.photolog.core.Embeddings
import kr.slz.photolog.core.Grouping
import kr.slz.photolog.core.QueryParser
import kr.slz.photolog.data.Counts
import kr.slz.photolog.data.EventRow
import kr.slz.photolog.data.PhotoCard
import kr.slz.photolog.data.PhotoDetail
import kr.slz.photolog.data.TagCount
import kr.slz.photolog.work.IndexWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * 화면 상태 한 덩어리. 디자인의 `DCLogic.state`에 대응한다.
 *
 * DB 읽기는 전부 IO 디스패처로 넘긴다 — 1만 장 태그 집계가 메인 스레드에서 돌면
 * 스크롤이 끊긴다.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    data class Ui(
        val screen: Screen = Screen.Home,
        val previous: Screen = Screen.Home,
        val axis: Axis = Axis.All,
        val counts: Counts = Counts(0, 0, 0, 0),
        val workMessage: String = "",
        val indexing: Boolean = false,
        val albums: List<Album> = emptyList(),
        val events: List<EventRow> = emptyList(),
        val cleanupCount: Int = 0,
        // 앨범 상세
        val openAlbum: Album? = null,
        val albumDays: List<DayGroup> = emptyList(),
        // 사진 상세
        val openPhoto: PhotoDetail? = null,
        // 검색
        val query: String = "",
        val parsed: QueryParser.Parsed? = null,
        val results: List<PhotoCard> = emptyList(),
        val resultSummary: String = "",
        val searching: Boolean = false,
        // 분류 수정
        val fixPhotos: List<PhotoCard> = emptyList(),
        val selected: Set<Long> = emptySet(),
        val toast: String? = null,
    )

    /**
     * 앨범 하나. 디자인의 CATS 항목에 대응하지만 **출처가 둘**이다:
     *  - `Tag`  — CLIP 분류·규칙 태그 (디자인의 "자동 분류된 앨범")
     *  - `Event` — 시간·장소로 묶인 기록 (디자인의 "오늘의 이야기", 여행 앨범)
     *
     * 디자인의 "예린이 첫 걸음마"처럼 사람 이름이 붙은 앨범은 얼굴 신원 묶기가
     * 필요해서(§8.4, 3단계) 아직 만들 수 없다. 지금은 태그와 이벤트로만 만든다.
     */
    data class Album(
        val key: String,
        val title: String,
        val meta: String,
        val count: Int,
        val axis: Axis,
        val kind: Kind,
        val coverIds: List<Long>,
    ) { enum class Kind { Tag, Event } }

    data class DayGroup(val day: String, val note: String, val photos: List<PhotoCard>)

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val nf = NumberFormat.getIntegerInstance(Locale.KOREA)
    private val zone = ZoneId.systemDefault()

    init {
        AppGraph.init(app)
        observeWorker()
        refresh()
    }

    // ---------- 내비게이션 ----------

    fun go(screen: Screen) = _ui.update {
        it.copy(screen = screen, previous = if (screen.showsNav) screen else it.screen)
    }

    fun back() = _ui.update {
        val target = when (it.screen) {
            Screen.Photo -> if (it.openAlbum != null) Screen.Category else it.previous
            Screen.Fix -> if (it.openAlbum != null) Screen.Category else it.previous
            Screen.Category -> it.previous
            else -> Screen.Home
        }
        it.copy(screen = target, selected = emptySet())
    }

    fun setAxis(a: Axis) = _ui.update { it.copy(axis = a) }

    fun toast(msg: String) {
        _ui.update { it.copy(toast = msg) }
        viewModelScope.launch { kotlinx.coroutines.delay(2600); _ui.update { it.copy(toast = null) } }
    }

    // ---------- 인덱싱 ----------

    fun startIndexing() {
        IndexWorker.start(getApplication())
        _ui.update { it.copy(screen = Screen.Analyzing, indexing = true) }
    }

    fun reanalyze() {
        viewModelScope.launch(Dispatchers.IO) {
            AppGraph.store.requeueClip()
            IndexWorker.restart(getApplication())
            _ui.update { it.copy(screen = Screen.Analyzing, indexing = true) }
        }
    }

    fun clearAnalysis() {
        viewModelScope.launch(Dispatchers.IO) {
            AppGraph.store.clearAnalysis()
            refreshBlocking()
            toast("분류 결과를 삭제했습니다.")
        }
    }

    private fun observeWorker() {
        val wm = WorkManager.getInstance(getApplication())
        viewModelScope.launch {
            wm.getWorkInfosForUniqueWorkFlow(IndexWorker.NAME).collect { infos ->
                val info = infos.firstOrNull() ?: return@collect
                val running = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
                val msg = info.progress.getString(IndexWorker.KEY_MSG).orEmpty()
                _ui.update { it.copy(indexing = running, workMessage = msg) }
                refresh()
                // 인덱싱이 끝나면 첫 화면으로 넘긴다. 진행률 100%를 계속 보여 줄 이유가 없다.
                if (info.state == WorkInfo.State.SUCCEEDED && _ui.value.screen == Screen.Analyzing) go(Screen.Home)
            }
        }
    }

    // ---------- 데이터 ----------

    fun refresh() = viewModelScope.launch(Dispatchers.IO) { refreshBlocking() }

    private suspend fun refreshBlocking() {
        val store = AppGraph.store
        val counts = store.counts()
        val tags = store.tagCounts()
        val events = store.events(60)
        val dupes = store.dupeGroups()
        // 정리 제안 장수 = 중복 그룹에서 남길 것을 뺀 나머지. "지울 수 있는 장수"다.
        val dupeExtra = dupes.sumOf { it.ids.size - 1 }
        val albums = buildAlbums(tags, events)
        withContext(Dispatchers.Main) {
            _ui.update { it.copy(counts = counts, albums = albums, events = events, cleanupCount = dupeExtra) }
        }
    }

    /**
     * 태그 앨범 + 이벤트 앨범. 축(axis)은 config의 `axis_map`으로 정한다 —
     * 카테고리를 코드에 박으면 config.json에 카테고리를 추가해도 앨범이 안 생긴다.
     */
    private fun buildAlbums(tags: List<TagCount>, events: List<EventRow>): List<Album> {
        val store = AppGraph.store
        val axisOf = axisMap()
        val out = ArrayList<Album>()

        for (t in tags) {
            // 규칙 태그 중 출처 계열은 앨범으로 유용하지만 '위치있음'·'야간'은 속성이라 뺀다
            if (t.label in HIDDEN_TAGS) continue
            val covers = store.photosByTag(t.label, limit = 3).map { it.id }
            out.add(Album(
                key = "tag:${t.label}", title = t.label,
                meta = if (t.source == "rule") "출처" else "자동 분류",
                count = t.count,
                axis = axisOf[t.label] ?: taxonomy.parentOf[t.label]?.let { axisOf[it] } ?: Axis.Moment,
                kind = Album.Kind.Tag, coverIds = covers,
            ))
        }
        for (e in events) {
            out.add(Album(
                key = "event:${e.id}", title = e.title,
                meta = listOfNotNull(e.placeName, dayLabel(e.startedAt)).joinToString(" · "),
                count = e.count, axis = if (e.placeName != null) Axis.Travel else Axis.Moment,
                kind = Album.Kind.Event, coverIds = e.coverIds,
            ))
        }
        // 장수로만 정렬하면 '카메라촬영'(대부분의 사진)이 맨 위에 온다 — 정보량이
        // 가장 적은 앨범이다. 의미 분류(CLIP) → 이벤트 → 출처 순으로 두고 그 안에서
        // 장수를 본다. 디자인의 "자동 분류된 앨범"이 뜻하는 순서다.
        return out.sortedWith(
            compareBy<Album> { rank(it) }.thenByDescending { it.count }
        )
    }

    /**
     * 앨범 등급. 장수만 보면 '클로즈업'(속성, 사진 1/4에 붙음)이 '음식' 위에 온다 —
     * 정보량이 낮은 것이 먼저 보인다. 카테고리 → 세부종류 → 이벤트 → 속성 → 출처.
     * 어느 라벨이 무엇인지는 config가 안다. 코드에 라벨을 박지 않는다.
     */
    private fun rank(a: Album): Int = when {
        a.kind == Album.Kind.Event -> 2
        a.title in taxonomy.categories -> 0
        a.title in taxonomy.subcategories -> 1
        a.title in taxonomy.attributes -> 3
        else -> 4                                          // 출처 등 규칙 태그
    }

    /** config에서 뽑은 라벨 분류. 세부종류는 부모를 알아야 축을 물려받는다. */
    private class Taxonomy(cfg: org.json.JSONObject, suffix: String) {
        val categories: Set<String> = cfg.getJSONObject("categories$suffix").keys().asSequence().toSet()
        val attributes: Set<String> = cfg.getJSONObject("attributes$suffix").keys().asSequence().toSet()
        val parentOf: Map<String, String> = buildMap {
            cfg.optJSONObject("subcategories$suffix")?.let { subs ->
                for (parent in subs.keys()) for (child in subs.getJSONObject(parent).keys()) put(child, parent)
            }
        }
        val subcategories: Set<String> get() = parentOf.keys
    }
    private val taxonomy by lazy { Taxonomy(AppGraph.config, AppGraph.variantSuffix) }

    private fun axisMap(): Map<String, Axis> {
        val obj = AppGraph.config.optJSONObject("axis_map") ?: return emptyMap()
        val out = HashMap<String, Axis>()
        for (k in obj.keys()) {
            val label = obj.getString(k)
            Axis.ordered.firstOrNull { it.label == label }?.let { out[k] = it }
        }
        return out
    }

    fun openAlbum(album: Album) {
        _ui.update { it.copy(openAlbum = album, screen = Screen.Category, albumDays = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            val store = AppGraph.store
            val photos = when (album.kind) {
                Album.Kind.Tag -> store.photosByTag(album.title, limit = 600)
                Album.Kind.Event -> store.eventPhotos(album.key.removePrefix("event:").toLong())
            }
            val days = photos.groupBy { dayKey(it) }
                .map { (day, ps) -> DayGroup(day, "${ps.size}장", ps) }
                .sortedByDescending { it.photos.firstOrNull()?.takenAt ?: 0 }
            withContext(Dispatchers.Main) { _ui.update { it.copy(albumDays = days) } }
        }
    }

    fun openPhoto(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val d = AppGraph.store.photo(id)
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(openPhoto = d, screen = Screen.Photo) }
            }
        }
    }

    // ---------- 검색 ----------

    fun onQuery(q: String) {
        _ui.update { it.copy(query = q) }
    }

    fun search() {
        val q = _ui.value.query.trim()
        if (q.isEmpty()) { _ui.update { it.copy(results = emptyList(), parsed = null, resultSummary = "") }; return }
        _ui.update { it.copy(searching = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val store = AppGraph.store
            val parser = AppGraph.queryParser()
            val parsed = parser.parse(q)

            // 필터를 먼저 좁힌다 — CLIP은 '작년'을 모른다(§10.2)
            var allow: Set<Long>? = null
            if (parsed.tags.isNotEmpty()) {
                allow = parsed.tags.map { t -> store.photosByTag(t, 5000).map { it.id }.toSet() }
                    .reduce { a, b -> a intersect b }
            }
            val from = parsed.from
            val until = parsed.until
            if (from != null || until != null || parsed.hours != null) {
                val inRange = store.recent(20000).filter { c ->
                    val t = c.takenAt ?: return@filter false
                    (from == null || t >= from) && (until == null || t <= until) &&
                        parser.matchesHour(parsed, t)
                }.map { it.id }.toSet()
                allow = allow?.intersect(inRange) ?: inRange
            }

            val ids = if (parsed.text.isBlank()) {
                // 남은 말이 없으면 필터만으로 찾는다. 시간순.
                (allow ?: emptySet()).let { s -> store.recent(20000).filter { it.id in s }.map { it.id } }
            } else runCatching {
                val v = AppGraph.encoder.encodeText(parsed.text)
                AppGraph.searchIndex.topK(v, 120, allow).map { it.first }
            }.getOrDefault(emptyList())

            val byId = store.photosByIds(ids)
            val cards = ids.mapNotNull { byId[it] }
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(
                    parsed = parsed, results = cards, searching = false,
                    resultSummary = summarize(parsed, cards),
                ) }
            }
        }
    }

    /**
     * 디자인의 "AI 요약". **LLM을 쓰지 않는다** — 파서가 이미 해석한 조건과 결과 통계를
     * 문장 틀에 넣으면 같은 문장이 나오고, 지어내지 않으니 틀릴 수가 없다(§11.4).
     */
    private fun summarize(p: QueryParser.Parsed, cards: List<PhotoCard>): String {
        if (cards.isEmpty()) return "조건에 맞는 사진을 찾지 못했습니다."
        val parts = ArrayList<String>()
        p.chips.forEach { parts.add(it.substringAfter(": ")) }
        val span = cards.mapNotNull { it.takenAt }.let { ts ->
            if (ts.isEmpty()) null else {
                val a = LocalDate.ofInstant(Instant.ofEpochSecond(ts.min()), zone)
                val b = LocalDate.ofInstant(Instant.ofEpochSecond(ts.max()), zone)
                if (a.year == b.year && a.monthValue == b.monthValue) "${a.year}년 ${a.monthValue}월"
                else "${a.year}.${a.monthValue} – ${b.year}.${b.monthValue}"
            }
        }
        val head = (listOfNotNull(span) + parts).distinct().joinToString(", ")
        val body = "사진 ${nf.format(cards.size)}장을 찾았습니다."
        return if (head.isBlank()) body else "$head · $body"
    }

    fun pickSuggestion(q: String) { onQuery(q); search() }

    /** 최근 태그에서 만든 추천. 디자인은 고정 문구지만 갤러리마다 달라야 쓸모가 있다. */
    fun suggestions(): List<String> {
        val tags = _ui.value.albums.filter { it.kind == Album.Kind.Tag }.take(3).map { it.title }
        return (listOf("작년 여름 바다", "밤에 찍은 사진") + tags).distinct().take(4)
    }

    // ---------- 분류 수정 ----------

    fun openFix() {
        val album = _ui.value.openAlbum
        _ui.update { it.copy(screen = Screen.Fix, selected = emptySet()) }
        viewModelScope.launch(Dispatchers.IO) {
            val photos = when {
                album?.kind == Album.Kind.Tag -> AppGraph.store.photosByTag(album.title, 120)
                album?.kind == Album.Kind.Event ->
                    AppGraph.store.eventPhotos(album.key.removePrefix("event:").toLong()).take(120)
                else -> AppGraph.store.recent(120)
            }
            withContext(Dispatchers.Main) { _ui.update { it.copy(fixPhotos = photos) } }
        }
    }

    fun toggleSelect(id: Long) = _ui.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun moveSelected(toLabel: String) {
        val s = _ui.value
        if (s.selected.isEmpty()) { toast("옮길 사진을 먼저 골라주세요."); return }
        val n = s.selected.size
        viewModelScope.launch(Dispatchers.IO) {
            AppGraph.store.moveTag(
                s.selected.toList(),
                from = s.openAlbum?.takeIf { it.kind == Album.Kind.Tag }?.title,
                to = toLabel,
            )
            refreshBlocking()
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(selected = emptySet()) }
                toast("${n}장을 '$toLabel'(으)로 옮겼습니다.")
            }
        }
    }

    /**
     * 디자인의 "다시 학습". **학습하지 않는다** — 사용자가 확정한 사진의 임베딩을
     * 카테고리 프로토타입에 섞는다(§8.5). 재학습이 아니라 기준점 이동이라 즉시 반영되고
     * 되돌릴 수 있다. 라벨 문구는 사용자에게 익숙한 쪽을 쓰되 동작은 정직하게 둔다.
     */
    fun applyFeedback() {
        val s = _ui.value
        if (s.selected.isEmpty()) return
        val label = s.openAlbum?.title ?: return
        val n = s.selected.size
        viewModelScope.launch(Dispatchers.IO) {
            for (id in s.selected) AppGraph.store.feedback(id, label, correct = false)
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(selected = emptySet()) }
                toast("반영했습니다. 다음 분석부터 같은 패턴을 제외합니다.")
            }
        }
    }

    fun confirmTag(id: Long, label: String) {
        viewModelScope.launch(Dispatchers.IO) {
            AppGraph.store.feedback(id, label, correct = true)
            withContext(Dispatchers.Main) { toast("확인 완료. 같은 기준을 계속 사용합니다.") }
        }
    }

    fun moveTargets(): List<String> =
        _ui.value.albums.filter { it.kind == Album.Kind.Tag && it.key != _ui.value.openAlbum?.key }
            .take(6).map { it.title }

    // ---------- 정리 ----------

    /** 중복 그룹에서 지울 수 있는 사진. **그룹당 1장은 코드가 강제로 남긴다**(§12.4). */
    fun cleanupCandidates(): Pair<List<Long>, List<Long>> {
        val groups = AppGraph.store.dupeGroups()
        val wanted = groups.flatMap { it.ids }.toSet()
        return Grouping.keepOnePerGroup(wanted, groups)
    }

    // ---------- 표시 ----------

    fun totalText(): String = nf.format(_ui.value.counts.total)

    private fun dayKey(c: PhotoCard): String =
        c.takenAt?.let { dayLabel(it) } ?: "날짜 미상"

    private fun dayLabel(epoch: Long): String {
        val d = LocalDate.ofInstant(Instant.ofEpochSecond(epoch), zone)
        val now = LocalDate.now(zone)
        return when {
            d == now -> "오늘"
            d == now.minusDays(1) -> "어제"
            d.year == now.year -> "${d.monthValue}월 ${d.dayOfMonth}일"
            else -> "${d.year}년 ${d.monthValue}월 ${d.dayOfMonth}일"
        }
    }

    private companion object {
        /** 앨범으로 만들면 잡동사니가 되는 태그. 속성이라 필터로만 쓸모가 있다. */
        val HIDDEN_TAGS = setOf("위치있음", "야간", "미분류", "실외", "저조도", "텍스트많음")
    }
}
