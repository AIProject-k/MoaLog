package kr.slz.photolog.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kr.slz.photolog.AppGraph
import kr.slz.photolog.core.Grouping
import kr.slz.photolog.core.QueryParser
import kr.slz.photolog.core.Selection
import kr.slz.photolog.data.Counts
import kr.slz.photolog.data.EventRow
import kr.slz.photolog.data.MonthRow
import kr.slz.photolog.data.PhotoCard
import kr.slz.photolog.data.PhotoDetail
import kr.slz.photolog.data.TagCount
import kr.slz.photolog.data.UserAlbumRow
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
 * 화면 상태 한 덩어리.
 *
 * DB 읽기는 전부 IO 디스패처로 넘긴다 — 1만 장 태그 집계가 메인 스레드에서 돌면
 * 스크롤이 끊긴다.
 *
 * **사진 목록은 페이지로 읽는다.** 예전에는 앨범을 열 때 600장을 한 번에 불러오고
 * 거기서 끝이었다. 601번째 사진은 앱 안에 존재하지 않는 것과 같았고, `전체 선택`이
 * 무엇을 고르는지도 알 수 없었다. 이제 [PAGE]장씩 이어 붙이고 총 장수를 함께 보여 준다.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    data class Ui(
        val screen: Screen = Screen.Photos,
        val previous: Screen = Screen.Photos,
        val axis: Axis = Axis.All,
        val kindFilter: AlbumKind? = null,
        val counts: Counts = Counts(0, 0, 0, 0),
        val workMessage: String = "",
        val indexing: Boolean = false,
        val albums: List<Album> = emptyList(),
        val cleanupCount: Int = 0,
        // 사진 탭
        val photoDays: List<DayGroup> = emptyList(),
        val photoTotal: Int = 0,
        val months: List<MonthRow> = emptyList(),
        val loadingMore: Boolean = false,
        val memories: List<Memory> = emptyList(),
        // 앨범 상세
        val openAlbum: Album? = null,
        val albumDays: List<DayGroup> = emptyList(),
        val albumLoaded: Int = 0,
        // 전체 화면 보기
        val viewerIds: List<Long> = emptyList(),
        val viewerIndex: Int = 0,
        /** 뷰어를 연 화면. 검색·정리에서 열었는데 예전 앨범으로 돌아가면 안 된다. */
        val viewerFrom: Screen = Screen.Photos,
        val openPhoto: PhotoDetail? = null,
        val infoOpen: Boolean = false,
        val detailsOpen: Boolean = false,
        val favorites: Set<Long> = emptySet(),
        // 검색
        val query: String = "",
        val parsed: QueryParser.Parsed? = null,
        val results: List<PhotoCard> = emptyList(),
        val resultSummary: String = "",
        val searching: Boolean = false,
        // 선택 (사진 탭·앨범 상세 공통)
        val selectionMode: Boolean = false,
        val selected: Set<Long> = emptySet(),
        val userAlbums: List<UserAlbumRow> = emptyList(),
        val pickerOpen: Boolean = false,
        // 분류 수정
        val fixPhotos: List<PhotoCard> = emptyList(),
        // 중복 정리
        val cleanupGroups: List<CleanupGroup> = emptyList(),
        val cleanupSelected: Set<Long> = emptySet(),
        val cleanupBytes: Long = 0,
        val toast: String? = null,
    ) {
        /** 지금 화면에 깔린 사진 전부. `전체 선택`이 무엇을 고르는지가 여기서 정해진다. */
        val visibleIds: List<Long> get() = when (screen) {
            Screen.Photos -> photoDays.flatMap { it.photos }.map(PhotoCard::id)
            Screen.Category -> albumDays.flatMap { it.photos }.map(PhotoCard::id)
            else -> emptyList()
        }
    }

    /**
     * 앨범 하나. **출처를 [AlbumKind]로 밝힌다** — 기기 폴더에서 사진을 빼는 것과
     * AI 분류에서 빼는 것은 사용자에게 완전히 다른 행동이라 섞어 두면 안 된다.
     */
    data class Album(
        val key: String,
        val title: String,
        val meta: String,
        val count: Int,
        val axis: Axis,
        val kind: AlbumKind,
        val coverIds: List<Long>,
    ) {
        val isFavorites get() = key == FAV_KEY

        /** 사진을 이 묶음에서만 뺄 수 있는가. 폴더와 기록은 우리가 정한 묶음이 아니다. */
        val removable get() = kind == AlbumKind.Tag || kind == AlbumKind.User
    }

    data class DayGroup(val day: String, val note: String, val photos: List<PhotoCard>)

    /** 홈 위쪽의 작은 모음. 사진이 모자라면 화면에서 통째로 뺀다. */
    data class Memory(val key: String, val title: String, val note: String, val photos: List<PhotoCard>)

    data class CleanupGroup(
        val group: Grouping.Group,
        val photos: List<PhotoCard>,
        val bytes: Long,
    ) {
        val key get() = Grouping.key(group)
        /** 왜 이 한 장을 남기라고 하는가. "가장 잘 나온"이라고 말하지 않는다 — 모르는 일이다. */
        val keepReason get() =
            if (group.kind == Grouping.Kind.EXACT) "보관 추천 · 원본으로 보이는 파일"
            else "보관 추천 · 가장 선명함"
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()

    private val nf = NumberFormat.getIntegerInstance(Locale.KOREA)
    private val zone = ZoneId.systemDefault()

    /** 휴지통으로 보내려고 시스템 대화상자를 띄운 id. 확인이 오면 이 목록을 지운다. */
    private var pendingTrash: Set<Long> = emptySet()

    init {
        AppGraph.init(app)
        observeWorker()
        refresh()
        loadPhotos(reset = true)
    }

    // ---------- 내비게이션 ----------

    fun go(screen: Screen) = _ui.update {
        it.copy(
            screen = screen,
            previous = if (screen.showsNav) screen else it.previous,
            selectionMode = false, selected = emptySet(),
        )
    }

    fun back() = _ui.update {
        val target = when (it.screen) {
            Screen.Viewer -> it.viewerFrom
            Screen.Fix -> if (it.openAlbum != null) Screen.Category else it.previous
            Screen.Category, Screen.Settings -> it.previous
            else -> Screen.Photos
        }
        it.copy(
            screen = target, selectionMode = false, selected = emptySet(),
            cleanupSelected = if (target == Screen.Cleanup) it.cleanupSelected else emptySet(),
            infoOpen = false, detailsOpen = false,
        )
    }

    fun setAxis(a: Axis) = _ui.update { it.copy(axis = a) }

    fun setKindFilter(k: AlbumKind?) = _ui.update { it.copy(kindFilter = k) }

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
                if (info.state == WorkInfo.State.SUCCEEDED) {
                    loadPhotos(reset = true)
                    if (_ui.value.screen == Screen.Analyzing) go(Screen.Photos)
                }
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
        val userAlbums = store.userAlbums()
        val choices = store.dupeChoices()
        // 정리 제안 장수 = 사용자가 "유지"로 덮은 묶음을 뺀 나머지에서, 묶음마다 한 장을 남긴 수
        val dupeExtra = store.dupeGroups()
            .filterNot { choices[Grouping.key(it)]?.dismissed == true }
            .sumOf { it.ids.size - 1 }
        val albums = buildAlbums(tags, events, userAlbums)
        val favorites = store.favoriteIds()
        val memories = buildMemories()
        withContext(Dispatchers.Main) {
            _ui.update { it.copy(
                counts = counts, albums = albums, cleanupCount = dupeExtra,
                userAlbums = userAlbums, favorites = favorites, memories = memories,
            ) }
        }
    }

    /**
     * 네 종류를 한 목록에 담되 종류를 잃지 않는다: 내 앨범 → 기기 폴더 → AI 분류 → 기록.
     * 축(axis)은 config의 `axis_map`으로 정한다 — 카테고리를 코드에 박으면
     * config.json에 카테고리를 추가해도 앨범이 안 생긴다.
     */
    private fun buildAlbums(
        tags: List<TagCount>,
        events: List<EventRow>,
        userAlbums: List<UserAlbumRow>,
    ): List<Album> {
        val store = AppGraph.store
        val axisOf = axisMap()
        val out = ArrayList<Album>()

        // 즐겨찾기는 사용자가 만든 목록이지만 표가 따로다. 앨범 목록 맨 위에 둔다 —
        // 넣기만 하고 다시 찾을 수 없으면 즐겨찾기가 아니다.
        val favCount = store.favoriteIds().size
        if (favCount > 0) out.add(Album(
            key = FAV_KEY, title = "즐겨찾기", meta = "내가 표시한 사진",
            count = favCount, axis = Axis.All, kind = AlbumKind.User,
            coverIds = store.favorites(3, 0).map(PhotoCard::id),
        ))
        for (a in userAlbums) out.add(Album(
            key = "user:${a.id}", title = a.name, meta = "내가 만든 앨범",
            count = a.count, axis = Axis.All, kind = AlbumKind.User, coverIds = a.coverIds,
        ))
        // 한 장짜리 폴더까지 늘어놓으면 목록이 잡동사니가 된다. 세 장부터 묶음으로 본다.
        for (b in store.buckets(minCount = 3)) out.add(Album(
            key = "folder:${b.name}", title = b.name, meta = "기기 폴더",
            count = b.count, axis = Axis.All, kind = AlbumKind.Folder, coverIds = b.coverIds,
        ))
        for (t in tags) {
            // 규칙 태그 중 출처 계열은 앨범으로 유용하지만 '위치있음'·'야간'은 속성이라 뺀다
            if (t.label in HIDDEN_TAGS) continue
            val covers = store.photosByTag(t.label, limit = 3).map { it.id }
            out.add(Album(
                key = "tag:${t.label}", title = t.label,
                meta = if (t.source == "rule") "출처" else "AI 분류",
                count = t.count,
                axis = axisOf[t.label] ?: taxonomy.parentOf[t.label]?.let { axisOf[it] } ?: Axis.Moment,
                kind = AlbumKind.Tag, coverIds = covers,
            ))
        }
        for (e in events) out.add(Album(
            key = "event:${e.id}", title = e.title,
            meta = listOfNotNull(e.placeName, dayLabel(e.startedAt)).joinToString(" · "),
            count = e.count, axis = if (e.placeName != null) Axis.Travel else Axis.Moment,
            kind = AlbumKind.Event, coverIds = e.coverIds,
        ))

        return out.sortedWith(compareBy<Album> { it.kind.ordinal }.thenBy { rank(it) }
            .thenByDescending { it.count })
    }

    /**
     * AI 앨범 안에서의 등급. 장수만 보면 '클로즈업'(속성, 사진 1/4에 붙음)이 '음식' 위에
     * 온다 — 정보량이 낮은 것이 먼저 보인다. 카테고리 → 세부종류 → 속성 → 출처.
     * 어느 라벨이 무엇인지는 config가 안다. 코드에 라벨을 박지 않는다.
     */
    private fun rank(a: Album): Int = when {
        a.kind != AlbumKind.Tag -> 0
        a.title in taxonomy.categories -> 0
        a.title in taxonomy.subcategories -> 1
        a.title in taxonomy.attributes -> 2
        else -> 3                                          // 출처 등 규칙 태그
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

    // ---------- 사진 탭 ----------

    /**
     * 전체 사진을 [PAGE]장씩 읽어 붙인다.
     * @param reset 처음부터 다시. 월 이동은 [offset]을 주고 목록을 갈아 끼운다.
     */
    fun loadPhotos(reset: Boolean = false, offset: Int? = null) {
        val s = _ui.value
        if (s.loadingMore) return
        val start = offset ?: if (reset) 0 else s.photoDays.sumOf { it.photos.size }
        val replace = reset || offset != null
        if (!replace && s.photoTotal > 0 && start >= s.photoTotal) return
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val store = AppGraph.store
            val page = store.photos(PAGE, start)
            val total = store.photoCount()
            // 월 목록은 전체를 훑어야 나온다. 스크롤로 한 장 더 읽을 때마다 다시 셀 이유가
            // 없으므로 목록을 갈아 끼울 때만 센다.
            val months = if (replace) store.months() else _ui.value.months
            withContext(Dispatchers.Main) {
                _ui.update {
                    it.copy(
                        photoDays = if (replace) toDays(page) else mergeDays(it.photoDays, page),
                        photoTotal = total, months = months, loadingMore = false,
                    )
                }
            }
        }
    }

    fun jumpToMonth(m: MonthRow) = loadPhotos(offset = m.offset)

    /** 이번 주 + 지난 해 오늘. 사진이 모자라면 그 칸을 만들지 않는다. */
    private fun buildMemories(): List<Memory> {
        val store = AppGraph.store
        val today = LocalDate.now(zone)
        val out = ArrayList<Memory>()

        val weekStart = today.minusDays(6).atStartOfDay(zone).toEpochSecond()
        val now = System.currentTimeMillis() / 1000
        val week = store.photosBetween(weekStart, now, 40)
        if (week.size >= MIN_MEMORY) out.add(Memory("week", "이번 주 사진", "${week.size}장", week))

        val md = "%02d-%02d".format(today.monthValue, today.dayOfMonth)
        val past = store.onThisDay(md, today.year.toString(), 60)
        past.groupBy { LocalDate.ofInstant(Instant.ofEpochSecond(it.takenAt!!), zone).year }
            .toSortedMap(compareByDescending { it })
            .forEach { (year, photos) ->
                if (photos.size >= MIN_MEMORY)
                    out.add(Memory("day$year", "${today.year - year}년 전 오늘", "${year}년 · ${photos.size}장", photos))
            }
        return out
    }

    // ---------- 앨범 상세 ----------

    fun openAlbum(album: Album) {
        _ui.update { it.copy(
            openAlbum = album, screen = Screen.Category, albumDays = emptyList(), albumLoaded = 0,
            selectionMode = false, selected = emptySet(),
        ) }
        loadAlbumPage(reset = true)
    }

    fun loadAlbumPage(reset: Boolean = false) {
        val s = _ui.value
        val album = s.openAlbum ?: return
        if (s.loadingMore) return
        val start = if (reset) 0 else s.albumLoaded
        if (!reset && start >= album.count) return
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val store = AppGraph.store
            val id = album.key.substringAfter(':')
            val page = when {
                // 즐겨찾기는 표가 따로라 종류(User)만 보고는 못 읽는다
                album.isFavorites -> store.favorites(PAGE, start)
                album.kind == AlbumKind.Tag -> store.photosByTag(album.title, PAGE, start)
                album.kind == AlbumKind.Folder -> store.photosByBucket(album.title, PAGE, start)
                album.kind == AlbumKind.User -> store.userAlbumPhotos(id.toLong(), PAGE, start)
                // 이벤트는 커야 수백 장이라 나눠 읽을 이유가 없다
                else -> if (start > 0) emptyList() else store.eventPhotos(id.toLong())
            }
            withContext(Dispatchers.Main) {
                _ui.update {
                    it.copy(
                        albumDays = if (reset) toDays(page) else mergeDays(it.albumDays, page),
                        albumLoaded = start + page.size, loadingMore = false,
                    )
                }
            }
        }
    }

    // ---------- 전체 화면 보기 ----------

    /** @param ids 좌우로 넘길 수 있는 사진 전부. 넘기기가 되려면 맥락이 필요하다. */
    fun openViewer(ids: List<Long>, id: Long) {
        val index = ids.indexOf(id).coerceAtLeast(0)
        _ui.update { it.copy(
            viewerIds = ids, viewerIndex = index, screen = Screen.Viewer, viewerFrom = it.screen,
            openPhoto = null, infoOpen = false, detailsOpen = false,
        ) }
        loadDetail(ids.getOrNull(index) ?: id)
    }

    fun onViewerPage(index: Int) {
        val id = _ui.value.viewerIds.getOrNull(index) ?: return
        _ui.update { it.copy(viewerIndex = index, openPhoto = null) }
        loadDetail(id)
    }

    private fun loadDetail(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        val d = AppGraph.store.photo(id)
        withContext(Dispatchers.Main) { _ui.update { it.copy(openPhoto = d) } }
    }

    fun toggleInfo() = _ui.update { it.copy(infoOpen = !it.infoOpen) }

    fun toggleDetails() = _ui.update { it.copy(detailsOpen = !it.detailsOpen) }

    fun currentId(): Long? = _ui.value.let { it.viewerIds.getOrNull(it.viewerIndex) }

    fun toggleFavorite(id: Long) {
        val on = id !in _ui.value.favorites
        _ui.update { it.copy(favorites = if (on) it.favorites + id else it.favorites - id) }
        viewModelScope.launch(Dispatchers.IO) { AppGraph.store.setFavorite(id, on) }
    }

    /** 이 사진의 분류를 사람이 확정한다. source가 'user'가 되어 재분석해도 살아남는다. */
    fun pinTag(id: Long, label: String) {
        viewModelScope.launch(Dispatchers.IO) {
            AppGraph.store.pinTag(id, label)
            refreshBlocking()
            withContext(Dispatchers.Main) { toast("'$label' 분류를 고정했습니다. 다시 분석해도 유지됩니다.") }
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

    /** 최근 태그에서 만든 추천. 고정 문구는 갤러리마다 달라야 쓸모가 있다. */
    fun suggestions(): List<String> {
        val tags = _ui.value.albums.filter { it.kind == AlbumKind.Tag }.take(3).map { it.title }
        return (listOf("작년 여름 바다", "밤에 찍은 사진") + tags).distinct().take(4)
    }

    // ---------- 선택 ----------

    fun startSelection(first: Long? = null) = _ui.update {
        it.copy(selectionMode = true, selected = setOfNotNull(first))
    }

    fun cancelSelection() = _ui.update { it.copy(selectionMode = false, selected = emptySet()) }

    fun toggleSelect(id: Long) = _ui.update { it.copy(selected = Selection.toggle(it.selected, id)) }

    /** **지금 화면에 깔린 것만** 고른다. 안 보이는 사진을 조용히 고르면 사고가 난다. */
    fun toggleSelectAll() = _ui.update {
        it.copy(selected = Selection.toggleAll(it.selected, it.visibleIds.toSet()))
    }

    fun favoriteSelected() {
        val ids = _ui.value.selected
        if (ids.isEmpty()) return
        _ui.update { it.copy(favorites = it.favorites + ids) }
        viewModelScope.launch(Dispatchers.IO) {
            for (id in ids) AppGraph.store.setFavorite(id, true)
            withContext(Dispatchers.Main) { toast("${ids.size}장을 즐겨찾기에 넣었습니다.") }
        }
    }

    // ---------- 내 앨범 ----------

    fun openPicker() = _ui.update { it.copy(pickerOpen = true) }
    fun closePicker() = _ui.update { it.copy(pickerOpen = false) }

    fun addSelectedToAlbum(albumId: Long) {
        val ids = _ui.value.selected
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            AppGraph.store.addToUserAlbum(albumId, ids)
            refreshBlocking()
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(pickerOpen = false, selectionMode = false, selected = emptySet()) }
                toast("${ids.size}장을 앨범에 넣었습니다.")
            }
        }
    }

    fun createAlbumWithSelected(name: String) {
        val ids = _ui.value.selected
        viewModelScope.launch(Dispatchers.IO) {
            val id = AppGraph.store.createUserAlbum(name.trim().ifBlank { "새 앨범" })
            AppGraph.store.addToUserAlbum(id, ids)
            refreshBlocking()
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(pickerOpen = false, selectionMode = false, selected = emptySet()) }
                toast("'${name.trim()}' 앨범을 만들었습니다.")
            }
        }
    }

    fun deleteOpenAlbum() {
        val album = _ui.value.openAlbum ?: return
        if (album.kind != AlbumKind.User || album.isFavorites) return
        viewModelScope.launch(Dispatchers.IO) {
            AppGraph.store.deleteUserAlbum(album.key.substringAfter(':').toLong())
            refreshBlocking()
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(openAlbum = null, screen = it.previous) }
                toast("앨범을 지웠습니다. 사진은 그대로 있습니다.")
            }
        }
    }

    /**
     * **앨범에서만 뺀다.** 사진은 기기에 그대로 남는다 — 이 구분을 흐리면
     * 사용자가 "정리"를 무서워하게 된다.
     */
    fun removeSelectedFromAlbum() {
        val s = _ui.value
        val album = s.openAlbum ?: return
        val ids = s.selected
        if (ids.isEmpty()) { toast("뺄 사진을 먼저 고르세요."); return }
        viewModelScope.launch(Dispatchers.IO) {
            when {
                album.isFavorites -> for (id in ids) AppGraph.store.setFavorite(id, false)
                album.kind == AlbumKind.Tag -> AppGraph.store.removeTag(ids, album.title)
                album.kind == AlbumKind.User ->
                    AppGraph.store.removeFromUserAlbum(album.key.substringAfter(':').toLong(), ids)
                else -> return@launch
            }
            refreshBlocking()
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(
                    albumDays = dropFrom(it.albumDays, ids),
                    albumLoaded = (it.albumLoaded - ids.size).coerceAtLeast(0),
                    fixPhotos = it.fixPhotos.filterNot { p -> p.id in ids },
                    selectionMode = false, selected = emptySet(),
                    openAlbum = it.openAlbum?.copy(count = (album.count - ids.size).coerceAtLeast(0)),
                ) }
                toast("${ids.size}장을 앨범에서 뺐습니다. 사진은 그대로 있습니다.")
            }
        }
    }

    // ---------- 분류 수정 ----------

    fun openFix() {
        val album = _ui.value.openAlbum
        _ui.update { it.copy(screen = Screen.Fix, selectionMode = true, selected = emptySet()) }
        viewModelScope.launch(Dispatchers.IO) {
            val photos = when {
                album?.kind == AlbumKind.Tag -> AppGraph.store.photosByTag(album.title, 120)
                album?.kind == AlbumKind.Folder -> AppGraph.store.photosByBucket(album.title, 120, 0)
                album?.kind == AlbumKind.User ->
                    AppGraph.store.userAlbumPhotos(album.key.substringAfter(':').toLong(), 120, 0)
                album?.kind == AlbumKind.Event ->
                    AppGraph.store.eventPhotos(album.key.substringAfter(':').toLong()).take(120)
                else -> AppGraph.store.recent(120)
            }
            withContext(Dispatchers.Main) { _ui.update { it.copy(fixPhotos = photos) } }
        }
    }

    fun moveSelected(toLabel: String) {
        val s = _ui.value
        if (s.selected.isEmpty()) { toast("옮길 사진을 먼저 골라주세요."); return }
        val n = s.selected.size
        viewModelScope.launch(Dispatchers.IO) {
            AppGraph.store.moveTag(
                s.selected.toList(),
                from = s.openAlbum?.takeIf { it.kind == AlbumKind.Tag }?.title,
                to = toLabel,
            )
            refreshBlocking()
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(selected = emptySet()) }
                toast("${n}장을 '$toLabel'(으)로 옮겼습니다.")
            }
        }
    }

    fun moveTargets(): List<String> =
        _ui.value.albums.filter { it.kind == AlbumKind.Tag && it.key != _ui.value.openAlbum?.key }
            .take(6).map { it.title }

    // ---------- 정리 ----------

    fun loadCleanup() {
        viewModelScope.launch(Dispatchers.IO) {
            val store = AppGraph.store
            val choices = store.dupeChoices()
            val cards = store.dupeGroups().mapNotNull { raw ->
                val choice = choices[Grouping.key(raw)]
                if (choice?.dismissed == true) return@mapNotNull null
                val group = Grouping.withKeep(raw, choice?.keepId)
                val byId = store.photosByIds(group.ids)
                val photos = group.ids.mapNotNull(byId::get)
                if (photos.size < 2) null
                else CleanupGroup(group, photos, store.bytesOf(group.ids.filter { it != group.bestId }))
            }
            withContext(Dispatchers.Main) { _ui.update { it.copy(cleanupGroups = cards) } }
        }
    }

    fun toggleCleanupSelect(id: Long) = updateCleanupSelection {
        Selection.toggle(_ui.value.cleanupSelected, id)
    }

    fun toggleCleanupSelectAll() = updateCleanupSelection {
        Selection.toggleAll(_ui.value.cleanupSelected,
            _ui.value.cleanupGroups.flatMap { g -> g.photos.map(PhotoCard::id) }.toSet())
    }

    /** "보관 추천 제외하고 선택" — 묶음마다 남길 한 장만 빼고 전부 고른다. */
    fun selectAllButKept() = updateCleanupSelection {
        Grouping.allButKept(_ui.value.cleanupGroups.map(CleanupGroup::group))
    }

    private fun updateCleanupSelection(block: () -> Set<Long>) {
        val next = block()
        _ui.update { it.copy(cleanupSelected = next) }
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = AppGraph.store.bytesOf(Grouping.keepOnePerGroup(
                next, _ui.value.cleanupGroups.map(CleanupGroup::group)).first)
            withContext(Dispatchers.Main) { _ui.update { it.copy(cleanupBytes = bytes) } }
        }
    }

    /** 보관할 사진을 사람이 바꾼다. 선명해도 눈을 감았을 수 있으니 마지막 판단은 사람이 한다. */
    fun setKeep(groupKey: String, id: Long) {
        _ui.update { s ->
            s.copy(cleanupGroups = s.cleanupGroups.map {
                if (it.key == groupKey) it.copy(group = Grouping.withKeep(it.group, id)) else it
            })
        }
        viewModelScope.launch(Dispatchers.IO) { AppGraph.store.setDupeKeep(groupKey, id) }
    }

    /** "이 묶음은 유지" — 다음에도 같은 제안을 다시 하지 않는다. */
    fun dismissGroup(groupKey: String) {
        _ui.update { s ->
            s.copy(
                cleanupGroups = s.cleanupGroups.filterNot { it.key == groupKey },
                cleanupSelected = s.cleanupSelected -
                    s.cleanupGroups.firstOrNull { it.key == groupKey }?.photos?.map(PhotoCard::id).orEmpty().toSet(),
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            AppGraph.store.setDupeDismissed(groupKey, true)
            refreshBlocking()
        }
        toast("이 묶음은 그대로 둡니다.")
    }

    /** 중복 그룹에서 지울 수 있는 사진. **그룹당 1장은 코드가 강제로 남는다**(§12.4). */
    fun cleanupCandidates(): Pair<List<Long>, List<Long>> =
        Grouping.keepOnePerGroup(_ui.value.cleanupSelected, _ui.value.cleanupGroups.map(CleanupGroup::group))

    // ---------- 휴지통 ----------

    /** 시스템 대화상자를 띄우기 직전에 부른다. 확인이 오면 이 목록을 화면에서 지운다. */
    fun willTrash(ids: Collection<Long>) { pendingTrash = ids.toSet() }

    /**
     * 휴지통 확인 뒤. **DB와 화면에서 즉시 지운다** — 워커의 다음 동기화를 기다리면
     * 방금 보낸 사진이 앨범에 그대로 보이고, 사용자는 정리가 됐는지 알 수 없다.
     */
    fun onTrashConfirmed() {
        val gone = pendingTrash
        pendingTrash = emptySet()
        if (gone.isEmpty()) return
        _ui.update { s ->
            s.copy(
                photoDays = dropFrom(s.photoDays, gone),
                albumDays = dropFrom(s.albumDays, gone),
                photoTotal = (s.photoTotal - gone.size).coerceAtLeast(0),
                albumLoaded = (s.albumLoaded - gone.size).coerceAtLeast(0),
                openAlbum = s.openAlbum?.let { it.copy(count = (it.count - gone.size).coerceAtLeast(0)) },
                results = s.results.filterNot { it.id in gone },
                memories = s.memories.mapNotNull { m ->
                    m.copy(photos = m.photos.filterNot { it.id in gone }).takeIf { it.photos.size >= MIN_MEMORY }
                },
                fixPhotos = s.fixPhotos.filterNot { it.id in gone },
                viewerIds = s.viewerIds.filterNot { it in gone },
                favorites = s.favorites - gone,
                cleanupGroups = s.cleanupGroups.mapNotNull { g ->
                    val left = g.photos.filterNot { it.id in gone }
                    if (left.size < 2) null
                    else g.copy(group = g.group.copy(ids = left.map(PhotoCard::id)), photos = left)
                },
                cleanupSelected = s.cleanupSelected - gone,
                selectionMode = false, selected = emptySet(),
                screen = if (s.screen == Screen.Viewer && s.viewerIds.size <= 1) s.viewerFrom else s.screen,
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            AppGraph.store.forget(gone)
            refreshBlocking()
        }
        IndexWorker.nudge(getApplication())
    }

    fun cancelTrash() { pendingTrash = emptySet() }

    // ---------- 표시 ----------

    fun totalText(): String = nf.format(_ui.value.counts.total)

    /** 용량을 사람 말로. 정리로 얼마가 비는지는 숫자로 보여야 판단이 된다. */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1fGB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.0fMB".format(bytes / (1L shl 20).toDouble())
        else -> "%.0fKB".format(bytes / 1024.0)
    }

    private fun toDays(photos: List<PhotoCard>): List<DayGroup> =
        photos.groupBy(::dayKey).map { (day, ps) -> DayGroup(day, "${ps.size}장", ps) }

    /**
     * 다음 페이지를 붙인다. 마지막 날짜 묶음과 첫 날짜 묶음이 같은 날이면 **합친다** —
     * 안 그러면 "9월 3일" 머리글이 두 번 나와 사용자가 목록이 깨졌다고 생각한다.
     */
    private fun mergeDays(existing: List<DayGroup>, more: List<PhotoCard>): List<DayGroup> {
        if (more.isEmpty()) return existing
        val next = toDays(more)
        val last = existing.lastOrNull() ?: return next
        val first = next.first()
        if (last.day != first.day) return existing + next
        val joined = last.photos + first.photos
        return existing.dropLast(1) + DayGroup(last.day, "${joined.size}장", joined) + next.drop(1)
    }

    private fun dropFrom(days: List<DayGroup>, ids: Set<Long>): List<DayGroup> =
        days.mapNotNull { g ->
            val left = g.photos.filterNot { it.id in ids }
            if (left.isEmpty()) null else DayGroup(g.day, "${left.size}장", left)
        }

    private fun dayKey(c: PhotoCard): String = c.takenAt?.let { dayLabel(it) } ?: "날짜 미상"

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
        /** 한 번에 읽는 장수. 화면 한 폭이 30장 안팎이라 열 폭쯤 앞서 읽는 셈이다. */
        const val PAGE = 300

        /** 즐겨찾기 앨범의 열쇠. 표가 따로라 다른 앨범과 섞이지 않게 이름을 박아 둔다. */
        const val FAV_KEY = "fav:0"

        /** 이만큼 안 되면 추억 칸을 만들지 않는다. 두 장짜리 "추억"은 추억이 아니다. */
        const val MIN_MEMORY = 3

        /** 앨범으로 만들면 잡동사니가 되는 태그. 속성이라 필터로만 쓸모가 있다. */
        val HIDDEN_TAGS = setOf("위치있음", "야간", "미분류", "실외", "저조도", "텍스트많음")
    }
}
