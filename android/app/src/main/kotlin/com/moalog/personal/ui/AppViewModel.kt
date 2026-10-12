package com.moalog.personal.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.moalog.personal.AppGraph
import com.moalog.personal.core.DayGrouping
import com.moalog.personal.core.GeoPhoto
import com.moalog.personal.core.Grouping
import com.moalog.personal.core.QueryParser
import com.moalog.personal.core.Relevance
import com.moalog.personal.core.Selection
import com.moalog.personal.data.Counts
import com.moalog.personal.data.EventRow
import com.moalog.personal.data.MonthRow
import com.moalog.personal.data.PhotoCard
import com.moalog.personal.data.PhotoDetail
import com.moalog.personal.data.TagCount
import com.moalog.personal.data.UserAlbumRow
import com.moalog.personal.work.IndexWorker
import com.moalog.personal.work.IndexPhase
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
        /**
         * 앨범을 어느 종류로 볼까: "all" | "image" | "video". `"video"`면 앨범 목록이 **영상만으로 분류**된다 —
         * 개수·표지·열었을 때의 내용이 전부 영상이다. 갤러리 탭의 [mediaFilter]와 따로 둔다:
         * 갤러리에서 영상만 보다가 앨범 탭에 가면 사진 앨범도 보이는 게 자연스럽다.
         */
        val albumMedia: String = "all",
        val counts: Counts = Counts(0, 0, 0, 0),
        val phaseCounts: Counts = Counts(0, 0, 0, 0),
        /** 뜨겁거나 배터리가 낮아 분석이 잠시 멈춘 사유("기기가 뜨거워 잠시 멈췄습니다"). 멈춘 게 아니면 null. */
        val paused: String? = null,
        /** AI 모델을 쓸 수 없는 이유. 정상이면 null. 설정의 분석 현황이 이것을 보여 준다. */
        val modelError: String? = null,
        val indexPhase: IndexPhase = IndexPhase.PHOTOS,
        val workMessage: String = "",
        val indexing: Boolean = false,
        val albums: List<Album> = emptyList(),
        val cleanupCount: Int = 0,
        /** 전체 영상 수. 앨범 탭의 "영상 N개를 기준으로 다시 묶었습니다" 띠가 쓴다. */
        val videoTotal: Int = 0,
        // 사진 탭
        val photoDays: List<DayGroup> = emptyList(),
        val photoTotal: Int = 0,
        val mediaFilter: String = "all",
        val months: List<MonthRow> = emptyList(),
        /** 월 이동으로 목록을 갈아 끼울 때마다 오른다. 화면이 그때 맨 위(그 달의 머리글)로 스크롤한다. */
        val jumpToken: Int = 0,
        val loadingMore: Boolean = false,
        val memories: List<Memory> = emptyList(),
        // 앨범 상세
        val openAlbum: Album? = null,
        val albumDays: List<DayGroup> = emptyList(),
        val albumLoaded: Int = 0,
        // 전체 화면 보기
        val viewerIds: List<Long> = emptyList(),
        val viewerIndex: Int = 0,
        val videoControls: VideoPlayerControls = VideoPlayerControls(),
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
        val sceneTimes: Map<Long, Long> = emptyMap(),
        /** 영상마다 잘 맞은 장면들(점수 순). 뷰어에서 장면 사이를 바로 뛴다. */
        val sceneHits: Map<Long, List<Long>> = emptyMap(),
        val searchVideosOnly: Boolean = false,
        val resultSummary: String = "",
        val searching: Boolean = false,
        /** 끈 "이렇게 이해했습니다" 칩의 글자. 끈 칩은 조건으로 쓰지 않는다. */
        val searchOff: Set<String> = emptySet(),
        /** 앞쪽 몇 개가 "가까운 결과"인가. 나머지는 "그 밖의 결과"(접힘). */
        val closeCount: Int = 0,
        val othersOpen: Boolean = false,
        /** 검색 입력창에 포커스를 줄 차례인가(갤러리의 검색 아이콘). 화면이 한 번 받으면 [consumeSearchFocus]로 내린다. */
        val searchFocus: Boolean = false,
        // 선택 (사진 탭·앨범 상세 공통)
        val selectionMode: Boolean = false,
        val selected: Set<Long> = emptySet(),
        val userAlbums: List<UserAlbumRow> = emptyList(),
        /** 하단 시트 하나(앨범에 추가·월 이동·AI 분류·옮길 앨범). 한 번에 하나만 뜬다. */
        val sheet: Sheet? = null,
        /** 되돌릴 수 없는 일을 한 번 더 묻는 대화상자. */
        val dialog: Dialog? = null,
        /** 앨범 상세 오른쪽 위 ⋮ 메뉴. */
        val menuOpen: Boolean = false,
        // 분류 수정
        val fixPhotos: List<PhotoCard> = emptyList(),
        // 중복 정리
        val cleanupGroups: List<CleanupGroup> = emptyList(),
        val cleanupSelected: Set<Long> = emptySet(),
        val cleanupBytes: Long = 0,
        val toast: ToastMsg? = null,
        /** 안드로이드 "일부 선택" 접근. 빈 화면의 원인을 정확히 말하려고 화면이 안다. */
        val limitedAccess: Boolean = false,
        // 지도
        val mapPoints: List<GeoPhoto> = emptyList(),
        /** 지도 탭에 한 번이라도 들어와 읽었는가. 읽기 전의 빈 목록과 "위치가 있는 사진이 없음"을 가른다. */
        val mapLoaded: Boolean = false,
        /** 지도에서 눌러 연 덩어리의 사진들(최신순). 비어 있으면 아래 띠가 닫혀 있다. */
        val mapSelection: List<PhotoCard> = emptyList(),
    ) {
        /** 지금 화면에 깔린 사진 전부. `전체 선택`이 무엇을 고르는지가 여기서 정해진다. */
        val visibleIds: List<Long> get() = when (screen) {
            Screen.Photos -> photoDays.flatMap { it.photos }.map(PhotoCard::id)
            Screen.Category -> albumDays.flatMap { it.photos }.map(PhotoCard::id)
            Screen.Search -> results.take(closeCount).map(PhotoCard::id)
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
        /** 그 가운데 영상 수. 전체 보기의 카드에 "영상 N"을 보이려는 것이다. */
        val videos: Int = 0,
    ) {
        val isFavorites get() = key == FAV_KEY

        /** 사진을 이 묶음에서만 뺄 수 있는가. 폴더와 기록은 우리가 정한 묶음이 아니다. */
        val removable get() = kind == AlbumKind.Tag || kind == AlbumKind.User
    }

    /** @param month 이 묶음이 속한 달("2026-09"). 날짜 미상은 "none". 스크러버의 월 표시가 쓴다. */
    data class DayGroup(val day: String, val note: String, val photos: List<PhotoCard>, val month: String = "none")

    /** 홈 위쪽의 작은 모음. 사진이 모자라면 화면에서 통째로 뺀다. */
    data class Memory(val key: String, val title: String, val note: String, val photos: List<PhotoCard>)

    data class CleanupGroup(
        val group: Grouping.Group,
        val photos: List<PhotoCard>,
        val bytes: Long,
        /** 사용자가 "이 묶음은 유지"로 덮었는가. 목록에는 남고(다시 볼 수 있게) 선택·집계에서 빠진다. */
        val held: Boolean = false,
        val info: Map<Long, com.moalog.personal.data.FileInfo> = emptyMap(),
        /** 앱이 처음 추천한 보관 사진. 사용자가 보관 대상을 바꿔도 "보관 추천" 표시는 이 사진에 남는다. */
        val recommendedId: Long = group.bestId,
    ) {
        val key get() = Grouping.key(group)
        /** 왜 이 한 장을 남기라고 하는가. "가장 잘 나온"이라고 말하지 않는다 — 모르는 일이다. */
        val keepReason get() =
            if (group.kind == Grouping.Kind.EXACT) "보관 추천 · 원본으로 보이는 파일"
            else "보관 추천 · 가장 선명함"
    }

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui.asStateFlow()
    private var lastAccessibleIds: Set<Long>? = null

    private val nf = NumberFormat.getIntegerInstance(Locale.KOREA)
    private val zone = ZoneId.systemDefault()

    /**
     * 앨범 내용을 읽는 일의 세대. 앨범을 새로 열거나 종류 필터를 바꾸면 올린다 — 앞선 읽기가 늦게
     * 끝나 새 목록을 덮어쓰는 걸 막는다(필터를 빠르게 넘길 때 실제로 생긴다).
     */
    private var albumEpoch = 0

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

    fun back() = _ui.update { state ->
        if (state.screen == Screen.Viewer && state.videoControls.fullscreen)
            return@update state.copy(videoControls = state.videoControls.exitFullscreen())
        val target = when (state.screen) {
            Screen.Viewer -> state.viewerFrom
            Screen.Fix -> if (state.openAlbum != null) Screen.Category else state.previous
            Screen.Category, Screen.Settings -> state.previous
            else -> Screen.Photos
        }
        state.copy(
            screen = target, selectionMode = false, selected = emptySet(),
            cleanupSelected = if (target == Screen.Cleanup) state.cleanupSelected else emptySet(),
            infoOpen = false, detailsOpen = false,
        )
    }

    fun setAxis(a: Axis) = _ui.update { it.copy(axis = a) }

    fun setKindFilter(k: AlbumKind?) = _ui.update { it.copy(kindFilter = k) }

    private var toastJob: kotlinx.coroutines.Job? = null

    /**
     * 화면 아래 토스트. 동작(되돌리기·앨범 보기)이 붙으면 눌러 볼 시간을 더 준다(4초, 없으면 2.6초).
     * 새 토스트가 오면 앞의 것은 바로 바뀐다 — 쌓이지 않는다.
     */
    fun toast(msg: String, action: String? = null, run: (() -> Unit)? = null) {
        toastJob?.cancel()
        _ui.update { it.copy(toast = ToastMsg(msg, action, run)) }
        toastJob = viewModelScope.launch {
            kotlinx.coroutines.delay(if (action != null) 4000 else 2600)
            _ui.update { it.copy(toast = null) }
        }
    }

    /** 토스트의 동작 버튼. */
    fun toastAction() {
        val t = _ui.value.toast ?: return
        toastJob?.cancel()
        _ui.update { it.copy(toast = null) }
        t.run?.invoke()
    }

    fun openSheet(sheet: Sheet) = _ui.update { it.copy(sheet = sheet, menuOpen = false) }
    fun closeSheet() = _ui.update { it.copy(sheet = null) }
    fun openDialog(dialog: Dialog) = _ui.update { it.copy(dialog = dialog, menuOpen = false) }
    fun closeDialog() = _ui.update { it.copy(dialog = null) }
    fun setMenu(open: Boolean) = _ui.update { it.copy(menuOpen = open) }

    // ---------- 인덱싱 ----------

    /**
     * 분석을 시작한다. **분석 화면은 첫 분석에만 띄운다** — 이미 분석해 둔 사진이 있으면 갤러리를 그대로 두고 뒤에서 돈다.
     * 안 그러면 앱을 켤 때마다 "0%" 화면이 0.2초쯤 번쩍였다(S23 실기기에서 화면 녹화로 확인).
     */
    fun startIndexing() {
        IndexWorker.start(getApplication())
        val first = AppGraph.store.countsPhotos().done == 0          // COUNT 한 번. 비동기로 하면 첫 분석에서 빈 갤러리가 한두 프레임 비친다
        _ui.update {
            it.copy(
                indexing = true, indexPhase = IndexPhase.PHOTOS,
                screen = if (first && it.screen == Screen.Photos) Screen.Analyzing else it.screen,
            )
        }
    }

    /** "이어서". 멈춘 이유(열·배터리)를 이번 한 번 무시하고 계속한다. 워커가 이미 끝났다면 다시 시작한다. */
    fun resumeIndexing() {
        com.moalog.personal.work.IndexControl.requestResume()
        IndexWorker.start(getApplication())
        _ui.update { it.copy(paused = null) }
    }

    fun reanalyze() {
        viewModelScope.launch(Dispatchers.IO) {
            AppGraph.store.requeueClip()
            IndexWorker.restart(getApplication())
            _ui.update { it.copy(screen = Screen.Analyzing, indexing = true, indexPhase = IndexPhase.PHOTOS) }
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
            var lastRefreshAt = 0L
            var workerRefresh: kotlinx.coroutines.Job? = null
            wm.getWorkInfosForUniqueWorkFlow(IndexWorker.NAME).collect { infos ->
                val info = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    ?: infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
                    ?: infos.firstOrNull() ?: return@collect
                val running = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
                val msg = info.progress.getString(IndexWorker.KEY_MSG).orEmpty()
                val phase = IndexPhase.entries.firstOrNull {
                    it.name == info.progress.getString(IndexWorker.KEY_PHASE)
                } ?: IndexPhase.PHOTOS
                val phaseCounts = Counts(
                    info.progress.getInt(IndexWorker.KEY_TOTAL, 0),
                    info.progress.getInt(IndexWorker.KEY_META, 0),
                    info.progress.getInt(IndexWorker.KEY_DONE, 0),
                    info.progress.getInt(IndexWorker.KEY_FAILED, 0),
                )
                val paused = msg.substringAfter(" · ", msg).takeIf { info.progress.getBoolean(IndexWorker.KEY_PAUSED, false) }
                _ui.update { it.copy(indexing = running, workMessage = msg, paused = paused,
                    indexPhase = phase, phaseCounts = phaseCounts) }
                val now = android.os.SystemClock.elapsedRealtime()
                if (info.state.isFinished) {
                    workerRefresh?.join()
                    lastRefreshAt = now
                    workerRefresh = viewModelScope.launch(Dispatchers.IO) { refreshBlocking() }
                } else if (now - lastRefreshAt >= WORKER_REFRESH_INTERVAL_MS && workerRefresh?.isActive != true) {
                    lastRefreshAt = now
                    workerRefresh = viewModelScope.launch(Dispatchers.IO) { refreshBlocking() }
                }
                if (running && phase == IndexPhase.VIDEOS && _ui.value.screen == Screen.Analyzing) {
                    loadPhotos(reset = true)
                    go(Screen.Photos)
                }
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
        val accessibleIds = store.accessibleIds()
        val accessChanged = lastAccessibleIds?.let { it != accessibleIds } ?: false
        lastAccessibleIds = accessibleIds
        // 목록을 만드는 동안 종류 필터가 바뀔 수 있다. 시작할 때의 값으로 만들고, 끝에서 아직 같은지 본다.
        val media = _ui.value.albumMedia
        val tags = store.tagCounts(media)
        val events = store.events(60, media)
        val userAlbums = store.userAlbums(media)
        val choices = store.dupeChoices()
        // 정리 제안 장수 = 사용자가 "유지"로 덮은 묶음을 뺀 나머지에서, 묶음마다 한 장을 남긴 수
        val dupeExtra = store.dupeGroups()
            .filterNot { choices[Grouping.key(it)]?.dismissed == true }
            .sumOf { it.ids.size - 1 }
        val albums = buildAlbums(tags, events, userAlbums, media)
        val videoTotal = store.photoCount("video")
        val favorites = store.favoriteIds()
        val memories = buildMemories()
        // 모델 준비는 처음 한 번 복사한다. 여기는 IO 스레드라 메인을 막지 않는다.
        val modelError = AppGraph.modelError
        withContext(Dispatchers.Main) {
            _ui.update { state ->
                val opened = if (accessChanged) state.openAlbum?.let { old ->
                    // 종류를 가른 보기에서는 그 종류가 하나도 없는 앨범이 목록에서 빠진다. 열어 둔 앨범까지
                    // 닫아 버리지 말고 빈 채로 둔다.
                    albums.firstOrNull { it.key == old.key }
                        ?: old.takeIf { state.albumMedia != "all" }?.copy(count = 0, videos = 0, coverIds = emptyList())
                } else if (state.albumMedia == media) {
                    // 열어 둔 앨범의 개수·표지를 새로 읽은 값으로. 옮기기·빼기를 되돌렸을 때 머리글이 그대로 남지 않게 한다.
                    state.openAlbum?.let { old -> albums.firstOrNull { it.key == old.key } ?: old }
                } else state.openAlbum
                val currentViewerId = state.viewerIds.getOrNull(state.viewerIndex)
                val viewerLost = accessChanged && (currentViewerId == null || currentViewerId !in accessibleIds)
                val visibleViewerIds = if (accessChanged) state.viewerIds.filter(accessibleIds::contains) else state.viewerIds
                // 사진 보기를 잃었을 때만 돌려보낸다. 다른 화면에서는 보던 자리를 지킨다 — 분석이 새 사진을 들일
                // 때마다 접근 가능한 목록이 바뀌는데, 그때마다 갤러리로 튕기면 안 된다.
                val nextScreen = when {
                    accessChanged && state.screen == Screen.Category && opened == null -> state.previous
                    viewerLost && state.screen == Screen.Viewer -> state.viewerFrom
                    else -> state.screen
                }
                state.copy(
                    counts = counts, modelError = modelError, albums = if (state.albumMedia == media) albums else state.albums,
                    cleanupCount = dupeExtra, videoTotal = videoTotal,
                    userAlbums = userAlbums, favorites = favorites, memories = memories,
                    openAlbum = opened,
                    screen = nextScreen,
                    photoDays = if (accessChanged) visibleDays(state.photoDays, accessibleIds) else state.photoDays,
                    albumDays = if (accessChanged) visibleDays(state.albumDays, accessibleIds) else state.albumDays,
                    albumLoaded = if (accessChanged) visibleDays(state.albumDays, accessibleIds).sumOf { d -> d.photos.size } else state.albumLoaded,
                    results = if (accessChanged) state.results.filter { it.id in accessibleIds } else state.results,
                    sceneTimes = if (accessChanged) state.sceneTimes.filterKeys(accessibleIds::contains) else state.sceneTimes,
                    viewerIds = if (viewerLost) emptyList() else visibleViewerIds,
                    viewerIndex = if (accessChanged && currentViewerId != null && currentViewerId in accessibleIds)
                        visibleViewerIds.indexOf(currentViewerId).coerceAtLeast(0) else if (viewerLost) 0 else state.viewerIndex,
                    openPhoto = if (accessChanged && state.openPhoto?.id?.let(accessibleIds::contains) != true) null else state.openPhoto,
                    fixPhotos = if (accessChanged) state.fixPhotos.filter { it.id in accessibleIds } else state.fixPhotos,
                    selected = if (accessChanged) state.selected.intersect(accessibleIds) else state.selected,
                    selectionMode = if (accessChanged && state.selected.none(accessibleIds::contains)) false else state.selectionMode,
                    cleanupGroups = if (accessChanged) emptyList() else state.cleanupGroups,
                    cleanupSelected = if (accessChanged) emptySet() else state.cleanupSelected,
                    cleanupBytes = if (accessChanged) 0 else state.cleanupBytes,
                )
            }
        }
        if (accessChanged) when (_ui.value.screen) {
            Screen.Photos -> loadPhotos(reset = true)
            Screen.Category -> loadAlbumPage(reset = true)
            Screen.Search -> search()
            Screen.Viewer -> currentId()?.let(::loadDetail)
            Screen.Cleanup -> loadCleanup()
            Screen.Fix -> openFix()
            else -> Unit
        }
    }

    private fun visibleDays(days: List<DayGroup>, accessibleIds: Set<Long>): List<DayGroup> =
        toDays(days.flatMap { it.photos }.filter { it.id in accessibleIds })

    /**
     * 네 종류를 한 목록에 담되 종류를 잃지 않는다: 내 앨범 → 기기 폴더 → AI 분류 → 기록.
     * 축(axis)은 config의 `axis_map`으로 정한다 — 카테고리를 코드에 박으면
     * config.json에 카테고리를 추가해도 앨범이 안 생긴다.
     */
    private fun buildAlbums(
        tags: List<TagCount>,
        events: List<EventRow>,
        userAlbums: List<UserAlbumRow>,
        media: String,
    ): List<Album> {
        val store = AppGraph.store
        val axisOf = axisMap()
        val out = ArrayList<Album>()

        // 즐겨찾기는 사용자가 만든 목록이지만 표가 따로다. 앨범 목록 맨 위에 둔다 —
        // 넣기만 하고 다시 찾을 수 없으면 즐겨찾기가 아니다.
        val fav = store.favoriteCounts(media)
        if (fav.count > 0) out.add(Album(
            key = FAV_KEY, title = "즐겨찾기", meta = "내가 표시한 사진·영상",
            count = fav.count, axis = Axis.All, kind = AlbumKind.User,
            coverIds = store.favorites(3, 0, media).map(PhotoCard::id), videos = fav.videos,
        ))
        // 종류를 가른 보기에서는 그 종류가 없는 내 앨범을 뺀다. "전체"에서는 빈 앨범도 남긴다 —
        // 사용자가 만든 것이 사라졌다고 느끼지 않게(접근이 줄어 0개가 된 경우도 같다).
        for (a in userAlbums) if (media == "all" || a.count > 0) out.add(Album(
            key = "user:${a.id}", title = a.name, meta = "내가 만든 앨범",
            count = a.count, axis = Axis.All, kind = AlbumKind.User, coverIds = a.coverIds, videos = a.videos,
        ))
        // 한 장짜리 폴더까지 늘어놓으면 목록이 잡동사니가 된다. 세 장부터 묶음으로 본다.
        for (b in store.buckets(minCount = 3, kind = media)) out.add(Album(
            key = "folder:${b.name}", title = b.name, meta = "기기 폴더",
            count = b.count, axis = Axis.All, kind = AlbumKind.Folder, coverIds = b.coverIds, videos = b.videos,
        ))
        for (t in tags) {
            // 규칙 태그 중 출처 계열은 앨범으로 유용하지만 '위치있음'·'야간'은 속성이라 뺀다
            if (t.label in HIDDEN_TAGS) continue
            val covers = store.photosByTag(t.label, limit = 3, kind = media).map { it.id }
            out.add(Album(
                key = "tag:${t.label}", title = t.label,
                meta = if (t.source == "rule") "출처" else "AI 분류",
                count = t.count,
                axis = axisOf[t.label] ?: taxonomy.parentOf[t.label]?.let { axisOf[it] } ?: Axis.Moment,
                kind = AlbumKind.Tag, coverIds = covers, videos = t.videos,
            ))
        }
        for (e in events) out.add(Album(
            key = "event:${e.id}", title = e.title,
            meta = listOfNotNull(e.placeName, dayLabel(e.startedAt)).joinToString(" · "),
            count = e.count, axis = if (e.placeName != null) Axis.Travel else Axis.Moment,
            kind = AlbumKind.Event, coverIds = e.coverIds, videos = e.videos,
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
            val page = store.photos(PAGE, start, s.mediaFilter)
            val total = store.photoCount(s.mediaFilter)
            // 월 목록은 전체를 훑어야 나온다. 스크롤로 한 장 더 읽을 때마다 다시 셀 이유가
            // 없으므로 목록을 갈아 끼울 때만 센다.
            val months = if (replace) withUndated(store.months(s.mediaFilter), total) else _ui.value.months
            withContext(Dispatchers.Main) {
                _ui.update {
                    it.copy(
                        photoDays = if (replace) toDays(page) else mergeDays(it.photoDays, page),
                        photoTotal = total, months = months, loadingMore = false,
                        jumpToken = if (offset != null) it.jumpToken + 1 else it.jumpToken,
                    )
                }
            }
        }
    }

    fun jumpToMonth(m: MonthRow) {
        closeSheet()
        loadPhotos(offset = m.offset)
    }

    /** 촬영 시각이 없는 항목은 정렬에서 맨 뒤라 월 목록에 안 잡힌다. 월 이동 시트의 맨 끝에 "날짜 미상"으로 붙인다. */
    private fun withUndated(months: List<MonthRow>, total: Int): List<MonthRow> {
        val dated = months.sumOf { it.count }
        return if (total > dated) months + MonthRow("none", total - dated, dated) else months
    }

    fun setMediaFilter(kind: String) {
        if (_ui.value.loadingMore) return
        _ui.update { it.copy(mediaFilter = kind, selected = emptySet(), selectionMode = false) }
        loadPhotos(reset = true)
    }

    /** 이번 주 + 지난 해 오늘. 사진이 모자라면 그 칸을 만들지 않는다. */
    private fun buildMemories(): List<Memory> {
        val store = AppGraph.store
        val today = LocalDate.now(zone)
        val out = ArrayList<Memory>()

        val weekStart = today.minusDays(6).atStartOfDay(zone).toEpochSecond()
        val now = System.currentTimeMillis() / 1000
        val week = store.photosBetween(weekStart, now, 40)
        if (week.size >= MIN_MEMORY) out.add(Memory("week", "이번 주 사진·영상", countNote(week), week))

        val md = "%02d-%02d".format(today.monthValue, today.dayOfMonth)
        val past = store.onThisDay(md, today.year.toString(), 60)
        past.groupBy { LocalDate.ofInstant(Instant.ofEpochSecond(it.takenAt!!), zone).year }
            .toSortedMap(compareByDescending { it })
            .forEach { (year, photos) ->
                if (photos.size >= MIN_MEMORY)
                    out.add(Memory("day$year", "${today.year - year}년 전 오늘", "${year}년 · ${countNote(photos)}", photos))
            }
        return out
    }

    // ---------- 앨범 상세 ----------

    fun openAlbum(album: Album) {
        albumEpoch++
        _ui.update { it.copy(
            openAlbum = album, screen = Screen.Category, albumDays = emptyList(), albumLoaded = 0,
            selectionMode = false, selected = emptySet(), loadingMore = false,
        ) }
        loadAlbumPage(reset = true)
    }

    /**
     * 앨범 목록(과 열려 있는 앨범)을 사진/동영상/전체로 다시 본다. 목록은 그 종류만으로 다시 만들어지고,
     * 앨범 안에 있으면 개수·표지·내용이 같은 종류로 바뀐다.
     */
    fun setAlbumMedia(media: String) {
        if (_ui.value.albumMedia == media) return
        albumEpoch++
        _ui.update { it.copy(albumMedia = media, selectionMode = false, selected = emptySet(), loadingMore = false) }
        if (_ui.value.screen == Screen.Category && _ui.value.openAlbum?.kind == AlbumKind.Memory) {
            _ui.update { it.copy(albumDays = toDays(byMedia(memoryPhotos, media))) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            refreshBlocking()
            withContext(Dispatchers.Main) {
                val s = _ui.value
                val old = s.openAlbum
                if (s.screen == Screen.Category && old != null) {
                    val fresh = s.albums.firstOrNull { it.key == old.key }
                        ?: old.copy(count = 0, videos = 0, coverIds = emptyList())
                    _ui.update { it.copy(openAlbum = fresh, albumDays = emptyList(), albumLoaded = 0) }
                    loadAlbumPage(reset = true)
                }
            }
        }
    }

    /**
     * 사진 탭 위 모아보기 카드를 눌렀을 때. 앨범 상세 화면(Category)을 같은 모양으로 연다 — 이미 읽어 둔 사진만 있어서
     * 따로 DB를 읽지 않는다. 종류 필터는 화면에서 거른다.
     */
    fun openMemory(m: Memory) {
        albumEpoch++
        memoryPhotos = m.photos
        val album = Album(
            key = "memory:${m.key}", title = m.title, meta = m.note, count = m.photos.size, axis = Axis.All,
            kind = AlbumKind.Memory, coverIds = m.photos.take(3).map(PhotoCard::id),
            videos = m.photos.count { it.mediaKind == com.moalog.personal.data.MediaKind.VIDEO },
        )
        _ui.update { s ->
            s.copy(
                openAlbum = album, screen = Screen.Category, albumDays = toDays(byMedia(m.photos, s.albumMedia)),
                albumLoaded = m.photos.size, selectionMode = false, selected = emptySet(), loadingMore = false,
            )
        }
    }

    /** 열어 둔 모아보기의 사진. 종류 필터를 바꿔도 다시 읽지 않고 여기서 거른다. */
    private var memoryPhotos: List<PhotoCard> = emptyList()

    private fun byMedia(photos: List<PhotoCard>, media: String): List<PhotoCard> = when (media) {
        "video" -> photos.filter { it.mediaKind == com.moalog.personal.data.MediaKind.VIDEO }
        "image" -> photos.filter { it.mediaKind != com.moalog.personal.data.MediaKind.VIDEO }
        else -> photos
    }

    /**
     * 앨범 탭의 "새 앨범"이나 시트에서 이름을 받아 만든다. 사진·영상을 골라 두었으면 함께 넣고, 아니면 빈 앨범이다.
     * 만든 뒤 토스트의 "앨범 보기"로 바로 열 수 있다.
     */
    fun createAlbumFromSheet(name: String) {
        val title = name.trim().take(20)
        if (title.isEmpty()) return
        val ids = _ui.value.selected
        viewModelScope.launch(Dispatchers.IO) {
            val id = AppGraph.store.createUserAlbum(title)
            if (ids.isNotEmpty()) AppGraph.store.addToUserAlbum(id, ids)
            refreshBlocking()
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(sheet = null, selectionMode = false, selected = emptySet()) }
                toast(
                    if (ids.isNotEmpty()) "${ids.size}개를 앨범에 넣었습니다." else "'$title' 앨범을 만들었습니다.",
                    "앨범 보기",
                ) { _ui.value.albums.firstOrNull { it.key == "user:$id" }?.let(::openAlbum) }
            }
        }
    }

    fun loadAlbumPage(reset: Boolean = false) {
        val s = _ui.value
        val album = s.openAlbum ?: return
        if (album.kind == AlbumKind.Memory) return                 // 이미 읽어 둔 것을 쓴다
        if (s.loadingMore) return
        val start = if (reset) 0 else s.albumLoaded
        if (!reset && start >= album.count) return
        val media = s.albumMedia
        val epoch = albumEpoch
        _ui.update { it.copy(loadingMore = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val store = AppGraph.store
            val id = album.key.substringAfter(':')
            val page = when {
                // 즐겨찾기는 표가 따로라 종류(User)만 보고는 못 읽는다
                album.isFavorites -> store.favorites(PAGE, start, media)
                album.kind == AlbumKind.Tag -> store.photosByTag(album.title, PAGE, start, media)
                album.kind == AlbumKind.Folder -> store.photosByBucket(album.title, PAGE, start, media)
                album.kind == AlbumKind.User -> store.userAlbumPhotos(id.toLong(), PAGE, start, media)
                // 이벤트는 커야 수백 장이라 나눠 읽을 이유가 없다
                else -> if (start > 0) emptyList() else store.eventPhotos(id.toLong(), media)
            }
            withContext(Dispatchers.Main) {
                if (epoch != albumEpoch) return@withContext      // 그사이 다른 앨범·다른 종류로 넘어갔다
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
        val current = _ui.value
        val index = ids.indexOf(id).coerceAtLeast(0)
        val viewerTimes = when (current.screen) {
            Screen.Search -> current.results.mapNotNull { p -> p.sceneTimeMs?.let { p.id to it } }.toMap()
            Screen.Category -> current.albumDays.flatMap { it.photos }
                .mapNotNull { p -> p.sceneTimeMs?.let { p.id to it } }.toMap()
            else -> emptyMap()
        }
        val hits = if (current.screen == Screen.Search) current.sceneHits
            else viewerTimes.mapValues { listOf(it.value) }
        val opened = ids.getOrNull(index) ?: id
        _ui.update { it.copy(
            viewerIds = ids, viewerIndex = index, screen = Screen.Viewer, viewerFrom = it.screen,
            sceneTimes = viewerTimes, sceneHits = hits,
            openPhoto = null, infoOpen = false, detailsOpen = false,
            // 장면을 찾아 들어왔으면 그 자리부터 바로 튼다. 그냥 연 영상은 사용자가 누를 때까지 기다린다.
            videoControls = it.videoControls.exitFullscreen().resetPlayback(autoPlay = opened in viewerTimes),
        ) }
        loadDetail(ids.getOrNull(index) ?: id)
    }

    fun onViewerPage(index: Int) {
        val id = _ui.value.viewerIds.getOrNull(index) ?: return
        _ui.update { it.copy(viewerIndex = index, openPhoto = null,
            videoControls = it.videoControls.resetPlayback(autoPlay = id in it.sceneTimes)) }
        loadDetail(id)
    }

    private fun loadDetail(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        val d = AppGraph.store.photo(id)
        withContext(Dispatchers.Main) { _ui.update { it.copy(openPhoto = d) } }
    }

    fun toggleInfo() = _ui.update { it.copy(infoOpen = !it.infoOpen) }

    /** 뷰어에서 다른 장면 칩을 눌렀을 때. */
    fun seekVideo(ms: Long) = _ui.update { it.copy(videoControls = it.videoControls.seek(ms)) }

    fun toggleVideoMute() = _ui.update { it.copy(videoControls = it.videoControls.toggleMute()) }

    fun saveVideoPlayback(mediaId: Long, positionMs: Long, resumePlayback: Boolean) = _ui.update { state ->
        if (state.viewerIds.getOrNull(state.viewerIndex) == mediaId)
            state.copy(videoControls = state.videoControls.savePlayback(positionMs, resumePlayback))
        else state
    }

    fun saveVideoPosition(mediaId: Long, positionMs: Long) = _ui.update { state ->
        if (state.viewerIds.getOrNull(state.viewerIndex) == mediaId)
            state.copy(videoControls = state.videoControls.savePlayback(positionMs, state.videoControls.resumePlayback))
        else state
    }

    fun setVideoFullscreen(enabled: Boolean) = _ui.update {
        it.copy(videoControls = if (enabled) it.videoControls.copy(fullscreen = true)
            else it.videoControls.exitFullscreen())
    }

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

    /** 갤러리의 검색 아이콘으로 들어왔을 때. 입력창에 바로 커서가 가게 한다. */
    fun openSearch() = _ui.update { it.copy(screen = Screen.Search, previous = Screen.Search, searchFocus = true,
        selectionMode = false, selected = emptySet()) }

    /** 포커스를 한 번 줬다. 사진 보기에서 돌아와 검색 화면이 다시 그려져도 키보드가 또 뜨지 않게 내려 둔다. */
    fun consumeSearchFocus() = _ui.update { it.copy(searchFocus = false) }

    /** 새 질의. 앞서 끈 칩은 질의가 바뀌면 의미가 없으니 모두 되살린다. */
    fun search() {
        _ui.update { it.copy(searchOff = emptySet(), othersOpen = false) }
        runSearch()
    }

    /** "이렇게 이해했습니다" 칩을 끄거나 다시 켠다. 끈 칩은 조건으로 쓰지 않고 같은 질의를 다시 돌린다. */
    fun toggleChip(chip: String) {
        _ui.update { it.copy(searchOff = if (chip in it.searchOff) it.searchOff - chip else it.searchOff + chip) }
        runSearch()
    }

    fun clearSearch() {
        searchJob?.cancel()
        _ui.update { it.copy(query = "", parsed = null, results = emptyList(), closeCount = 0, searching = false,
            searchOff = emptySet(), othersOpen = false, resultSummary = "", sceneTimes = emptyMap(), sceneHits = emptyMap(),
            selectionMode = false, selected = emptySet()) }
    }

    fun toggleOthers() = _ui.update { it.copy(othersOpen = !it.othersOpen) }

    private var searchJob: kotlinx.coroutines.Job? = null

    private fun runSearch() {
        val q = _ui.value.query.trim()
        val off = _ui.value.searchOff
        val videosOnly = _ui.value.searchVideosOnly
        if (q.isEmpty()) { clearSearch(); return }
        searchJob?.cancel()
        _ui.update { it.copy(searching = true) }
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val store = AppGraph.store
            val parser = AppGraph.queryParser()
            val parsed = parser.parse(q, disabled = off)

            // 필터를 먼저 좁힌다 — CLIP은 '작년'을 모른다(§10.2)
            var base: Set<Long> = store.accessibleIds()
            val from = parsed.from
            val until = parsed.until
            if (from != null || until != null || parsed.hours != null) {
                val inRange = store.takenBetween(from, until)
                    .filter { (_, t) -> parser.matchesHour(parsed, t) }
                    .mapTo(HashSet()) { it.first }
                base = base.intersect(inRange)
            }
            val wantVideo = videosOnly || parsed.media == "video"
            if (wantVideo) base = base.filterTo(HashSet()) { it < 0 }
            else if (parsed.media == "image") base = base.filterTo(HashSet()) { it > 0 }

            // "가까운 결과"의 범위. 태그 칩이 있으면 그 태그가 붙은 것이고, 없으면 CLIP 점수로 가른다.
            val tagSet: Set<Long>? = if (parsed.tags.isEmpty()) null
                else parsed.tags.map { t -> store.idsByTag(t) }.reduce { x, y -> x intersect y }
            val closeAllow = tagSet?.let { base intersect it } ?: base

            val close = rank(parsed.text, closeAllow, 120)
            // 태그가 조건인 검색은 태그가 안 붙은 것도 CLIP으로 한 번 더 찾아 "그 밖의 결과"로 접어 둔다.
            val other = if (tagSet != null) {
                val words = (listOfNotNull(parsed.text.takeIf { it.isNotBlank() }) + parsed.tags).joinToString(" ")
                rank(words, base - closeAllow, 60)
            } else Ranked(emptyList(), emptyMap(), emptyMap())

            val failed = close.failed || other.failed
            val closeCount = when {
                tagSet != null || parsed.text.isBlank() -> close.ids.size
                else -> Relevance.closeCount(close.scores)
            }
            val ordered = close.ids + other.ids
            val sceneTimes = close.sceneTimes + other.sceneTimes
            val sceneHits = close.sceneHits + other.sceneHits

            val byId = store.photosByIds(ordered)
            val cards = ordered.mapNotNull { mediaId -> byId[mediaId]?.copy(sceneTimeMs = sceneTimes[mediaId]) }
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(
                    parsed = parsed, results = cards, closeCount = closeCount.coerceAtMost(cards.size), searching = false,
                    sceneTimes = sceneTimes, sceneHits = sceneHits, selectionMode = false, selected = emptySet(),
                    resultSummary = if (failed) "검색 중 오류가 났습니다. 다시 검색해 주세요."
                        else summarize(parsed, cards.take(closeCount), off),
                ) }
            }
        }
    }

    /** 점수순으로 정렬된 id와 점수, 영상의 맞은 장면들. */
    /** [failed]는 CLIP·영상 검색이 예외로 멈춘 것이다. 빈 결과와 구분해서 화면에 알린다(계획서 MLG-101). */
    private class Ranked(val ids: List<Long>, val sceneTimes: Map<Long, Long>, val sceneHits: Map<Long, List<Long>>,
                         val scores: List<Float> = emptyList(), val failed: Boolean = false)

    /** [text]가 비면 시간순(필터만으로 찾기), 있으면 CLIP 점수순. 사진은 색인에서, 영상은 프레임 단위로 따로 찾는다. */
    private fun rank(text: String, allow: Set<Long>, limit: Int): Ranked {
        val store = AppGraph.store
        if (allow.isEmpty()) return Ranked(emptyList(), emptyMap(), emptyMap())
        if (text.isBlank()) {
            val ids = store.idsNewestFirst().asSequence().filter { it in allow }.take(limit).toList()
            return Ranked(ids, emptyMap(), emptyMap())
        }
        return runCatching {
            val v = AppGraph.encoder.encodeText(text)
            // 영상은 프레임 단위로 따로 찾는다. 사진 색인에 들어 있는 영상 평균 벡터를 쓰면
            // 30초 중 3초만 바다인 영상은 평균에 묻혀 안 잡힌다.
            val scenes = store.searchVideoScenes(v, allow, perVideo = 3)
            val photoAllow = allow.filterTo(HashSet()) { it > 0 }
            val photos = AppGraph.searchIndex.topK(v, limit, photoAllow).filter { it.first > 0 }
            val merged = (photos + scenes.map { it.key to it.value.first().second })
                .sortedByDescending { it.second }.take(limit)
            Ranked(
                ids = merged.map { it.first },
                sceneTimes = scenes.mapValues { it.value.first().first },
                sceneHits = scenes.mapValues { e -> e.value.map { it.first } },
                scores = merged.map { it.second },
            )
        }.onFailure {
            // 조용히 빈 결과를 내면 "찾을 게 없다"와 "검색이 고장났다"가 똑같아 보인다.
            android.util.Log.e("Search", "장면 검색 실패", it)
        }.getOrElse { Ranked(emptyList(), emptyMap(), emptyMap(), failed = true) }
    }

    fun toggleVideoSearch() {
        _ui.update { it.copy(searchVideosOnly = !it.searchVideosOnly) }
        if (_ui.value.query.isNotBlank()) runSearch()
    }

    /**
     * "앨범으로 모으기" 1단계 — **가까운 결과를 전부 고른 상태로** 확인 모드에 들어간다.
     * 검색은 늘 많이 돌려주고 뒤쪽은 관련이 약하다. 그걸 확인 없이 통째로 저장하면 앨범에 엉뚱한 사진이
     * 섞이므로, 빼고 싶은 것을 눌러 빼게 한다. "그 밖의 결과"는 확인 대상에 넣지 않는다.
     */
    fun startSearchReview() = _ui.update {
        if (it.results.isEmpty() || it.searching || it.closeCount == 0) it
        else it.copy(selectionMode = true, selected = it.results.take(it.closeCount).map(PhotoCard::id).toSet())
    }

    /** 2단계 — 사용자가 남긴 것만, 맞은 장면 시각과 함께 저장한다. 앨범에서 열어도 그 장면부터 튼다. */
    fun saveSearchAlbum(name: String) {
        val snapshot = _ui.value
        val picked = snapshot.results.take(snapshot.closeCount).filter { it.id in snapshot.selected }
        if (picked.isEmpty()) { toast("저장할 결과를 하나 이상 고르세요."); return }
        val title = name.trim().take(20).ifBlank { snapshot.query.trim().take(20).ifBlank { "검색 모음" } }
        viewModelScope.launch(Dispatchers.IO) {
            val album = AppGraph.store.createUserAlbum(title)
            val sceneTimes = picked.mapNotNull { p -> p.sceneTimeMs?.let { p.id to it } }.toMap()
            AppGraph.store.addToUserAlbum(album, picked.map { it.id }, sceneTimes)
            refreshBlocking()
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(selectionMode = false, selected = emptySet()) }
                toast("'$title' 앨범에 ${picked.size}개를 담았습니다.", "앨범 보기") {
                    _ui.value.albums.firstOrNull { it.key == "user:$album" }?.let(::openAlbum)
                }
            }
        }
    }

    /**
     * 디자인의 "AI 요약". **LLM을 쓰지 않는다** — 파서가 이미 해석한 조건과 결과 통계를
     * 문장 틀에 넣으면 같은 문장이 나오고, 지어내지 않으니 틀릴 수가 없다(§11.4).
     * 켜 둔 칩만 말한다. 시간 조건이 있으면 "2025.6 – 2025.8, 작년, 여름 ·", 없으면 태그 이름으로 시작한다.
     */
    private fun summarize(p: QueryParser.Parsed, cards: List<PhotoCard>, off: Set<String>): String {
        if (cards.isEmpty()) return "조건에 맞는 사진·영상을 찾지 못했습니다."
        val on = p.chips.filter { it !in off }
        val timeLabels = on.filter { it.startsWith("시간: ") }.map { it.removePrefix("시간: ") }
        val span = if (p.from != null || p.until != null) cards.mapNotNull { it.takenAt }.let { ts ->
            if (ts.isEmpty()) null else {
                val a = LocalDate.ofInstant(Instant.ofEpochSecond(ts.min()), zone)
                val b = LocalDate.ofInstant(Instant.ofEpochSecond(ts.max()), zone)
                if (a.year == b.year && a.monthValue == b.monthValue) "${a.year}년 ${a.monthValue}월"
                else "${a.year}.${a.monthValue} – ${b.year}.${b.monthValue}"
            }
        } else null
        var head = (listOfNotNull(span) + timeLabels).distinct()
        if (head.isEmpty()) head = on.filter { it.startsWith("태그: ") || it.startsWith("장소: ") }.map { it.substringAfter(": ") }
        val photos = cards.count { it.mediaKind == com.moalog.personal.data.MediaKind.PHOTO }
        val videos = cards.size - photos
        val found = listOfNotNull(
            photos.takeIf { it > 0 }?.let { "사진 ${nf.format(it)}장" },
            videos.takeIf { it > 0 }?.let { "영상 ${nf.format(it)}개" },
        ).joinToString(" · ")
        val body = "$found 찾았습니다."
        return if (head.isEmpty()) body else "${head.joinToString(", ")} · $body"
    }

    fun pickSuggestion(q: String) { onQuery(q); search() }

    /** 검색 첫 화면의 "자주 나오는 분류": 지금 갤러리에서 사진·영상이 많은 AI 앨범 이름. */
    fun frequentTags(): List<String> =
        _ui.value.albums.filter { it.kind == AlbumKind.Tag && it.title in taxonomy.categories }.take(4).map { it.title }

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
        val added = ids.filter { it !in _ui.value.favorites }       // 되돌리기는 이번에 새로 넣은 것만 뺀다
        _ui.update { it.copy(favorites = it.favorites + ids, selectionMode = false, selected = emptySet()) }
        viewModelScope.launch(Dispatchers.IO) {
            for (id in ids) AppGraph.store.setFavorite(id, true)
            refreshBlocking()
            withContext(Dispatchers.Main) {
                toast("${ids.size}개를 즐겨찾기에 넣었습니다.", "되돌리기") {
                    _ui.update { it.copy(favorites = it.favorites - added.toSet()) }
                    viewModelScope.launch(Dispatchers.IO) {
                        for (id in added) AppGraph.store.setFavorite(id, false)
                        refreshBlocking()
                        toast("취소했습니다.")
                    }
                }
            }
        }
    }

    // ---------- 내 앨범 ----------

    fun openPicker() = openSheet(Sheet.Add)
    fun closePicker() = closeSheet()

    fun addSelectedToAlbum(albumId: Long) {
        val ids = _ui.value.selected
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val already = AppGraph.store.userAlbumMembers(albumId, ids)
            AppGraph.store.addToUserAlbum(albumId, ids)
            refreshBlocking()
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(sheet = null, selectionMode = false, selected = emptySet()) }
                toast("${ids.size}개를 앨범에 넣었습니다.", "되돌리기") {
                    viewModelScope.launch(Dispatchers.IO) {
                        AppGraph.store.removeFromUserAlbum(albumId, ids - already)
                        refreshBlocking()
                        toast("취소했습니다.")
                    }
                }
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
                _ui.update { it.copy(sheet = null, selectionMode = false, selected = emptySet()) }
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
                toast("앨범을 삭제했습니다. 사진·영상은 그대로 있습니다.")
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
        if (ids.isEmpty()) { toast("뺄 사진·영상을 먼저 고르세요."); return }
        viewModelScope.launch(Dispatchers.IO) {
            // 되돌리기에 쓸 것을 지우기 전에 읽어 둔다(태그 점수·출처, 영상 장면 시각).
            var undo: (suspend () -> Unit)? = null
            when {
                album.isFavorites -> {
                    for (id in ids) AppGraph.store.setFavorite(id, false)
                    undo = { for (id in ids) AppGraph.store.setFavorite(id, true) }
                }
                album.kind == AlbumKind.Tag -> {
                    val rows = AppGraph.store.tagRows(ids, album.title)
                    AppGraph.store.removeTag(ids, album.title)
                    undo = { AppGraph.store.restoreTagRows(rows) }
                }
                album.kind == AlbumKind.User -> {
                    val albumId = album.key.substringAfter(':').toLong()
                    val scenes = AppGraph.store.userAlbumScenes(albumId, ids)
                    AppGraph.store.removeFromUserAlbum(albumId, ids)
                    undo = { AppGraph.store.addToUserAlbum(albumId, ids, scenes) }
                }
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
                toast("${ids.size}개를 앨범에서 뺐습니다. 사진·영상은 그대로 있습니다.", "되돌리기") {
                    viewModelScope.launch(Dispatchers.IO) {
                        undo?.invoke()
                        refreshBlocking()
                        withContext(Dispatchers.Main) {
                            // 열어 둔 앨범과 분류 수정 목록을 다시 읽는다.
                            if (_ui.value.screen == Screen.Category) loadAlbumPage(reset = true)
                            if (_ui.value.screen == Screen.Fix) openFix()
                            toast("취소했습니다.")
                        }
                    }
                }
            }
        }
    }

    // ---------- 분류 수정 ----------

    fun openFix() {
        val album = _ui.value.openAlbum
        val media = _ui.value.albumMedia
        _ui.update { it.copy(screen = Screen.Fix, selectionMode = true, selected = emptySet()) }
        viewModelScope.launch(Dispatchers.IO) {
            val photos = when {
                album?.kind == AlbumKind.Tag -> AppGraph.store.photosByTag(album.title, 120, 0, media)
                album?.kind == AlbumKind.Folder -> AppGraph.store.photosByBucket(album.title, 120, 0, media)
                album?.kind == AlbumKind.User ->
                    AppGraph.store.userAlbumPhotos(album.key.substringAfter(':').toLong(), 120, 0, media)
                album?.kind == AlbumKind.Event ->
                    AppGraph.store.eventPhotos(album.key.substringAfter(':').toLong(), media).take(120)
                else -> AppGraph.store.recent(120)
            }
            withContext(Dispatchers.Main) { _ui.update { it.copy(fixPhotos = photos) } }
        }
    }

    fun moveSelected(toLabel: String) {
        val s = _ui.value
        if (s.selected.isEmpty()) { toast("옮길 사진·영상을 먼저 골라주세요."); return }
        val ids = s.selected.toList()
        val n = ids.size
        val from = s.openAlbum?.takeIf { it.kind == AlbumKind.Tag }?.title
        viewModelScope.launch(Dispatchers.IO) {
            val store = AppGraph.store
            val fromRows = from?.let { store.tagRows(ids, it) }.orEmpty()
            val hadTo = store.tagRows(ids, toLabel).map { it.id }.toSet()
            store.moveTag(ids, from = from, to = toLabel)
            refreshBlocking()
            withContext(Dispatchers.Main) {
                _ui.update { st -> st.copy(
                    selected = emptySet(),
                    fixPhotos = st.fixPhotos.filterNot { it.id in ids },
                    albumDays = dropFrom(st.albumDays, ids.toSet()),
                    albumLoaded = (st.albumLoaded - n).coerceAtLeast(0),
                    openAlbum = st.openAlbum?.copy(count = (st.openAlbum.count - n).coerceAtLeast(0)),
                ) }
                toast("${n}개를 '$toLabel' 앨범으로 옮겼습니다. 사진·영상은 그대로 있습니다.", "되돌리기") {
                    viewModelScope.launch(Dispatchers.IO) {
                        store.removeTag(ids.filter { it !in hadTo }, toLabel)
                        store.restoreTagRows(fromRows)
                        refreshBlocking()
                        withContext(Dispatchers.Main) {
                            if (_ui.value.screen == Screen.Fix) openFix()
                            else if (_ui.value.screen == Screen.Category) loadAlbumPage(reset = true)
                            toast("취소했습니다.")
                        }
                    }
                }
            }
        }
    }

    fun moveTargets(): List<String> =
        _ui.value.albums.filter { it.kind == AlbumKind.Tag && it.key != _ui.value.openAlbum?.key }
            .take(6).map { it.title }

    // ---------- 지도 ----------

    /** 지도 탭에 들어올 때마다 읽는다. 점 몇천 개라 싸고, 사진을 지웠거나 접근이 줄었으면 바로 반영된다. */
    fun loadMap() {
        viewModelScope.launch(Dispatchers.IO) {
            val store = AppGraph.store
            val points = store.geoPhotos()
            val alive = points.mapTo(HashSet(points.size)) { it.id }
            // 열어 둔 덩어리에서 사라진 사진을 뺀다. 뷰어에서 지우고 돌아온 경우.
            val kept = _ui.value.mapSelection.map { it.id }.filter { it in alive }
            val cards = if (kept.isEmpty()) emptyList() else store.photosByIds(kept).values.sortedByDescending { it.takenAt ?: Long.MIN_VALUE }
            withContext(Dispatchers.Main) {
                _ui.update { it.copy(mapPoints = points, mapLoaded = true, mapSelection = cards) }
            }
        }
    }

    /** 덩어리를 눌러 그 사진들을 아래 띠에 올린다. 최신순. */
    fun selectMapPhotos(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            val cards = AppGraph.store.photosByIds(ids).values.sortedByDescending { it.takenAt ?: Long.MIN_VALUE }
            withContext(Dispatchers.Main) { _ui.update { it.copy(mapSelection = cards) } }
        }
    }

    fun clearMapSelection() = _ui.update { it.copy(mapSelection = emptyList()) }

    // ---------- 정리 ----------

    fun loadCleanup() {
        viewModelScope.launch(Dispatchers.IO) {
            val store = AppGraph.store
            val choices = store.dupeChoices()
            val cards = store.dupeGroups().mapNotNull { raw ->
                val choice = choices[Grouping.key(raw)]
                val group = Grouping.withKeep(raw, choice?.keepId)
                val byId = store.photosByIds(group.ids)
                val photos = group.ids.mapNotNull(byId::get)
                if (photos.size < 2) null
                else CleanupGroup(
                    group, photos, store.bytesOf(group.ids.filter { it != group.bestId }),
                    held = choice?.dismissed == true, info = store.fileInfo(group.ids), recommendedId = raw.bestId,
                )
            }
            withContext(Dispatchers.Main) { _ui.update { it.copy(cleanupGroups = cards) } }
        }
    }

    fun toggleCleanupSelect(id: Long) = updateCleanupSelection {
        Selection.toggle(_ui.value.cleanupSelected, id)
    }

    fun toggleCleanupSelectAll() = updateCleanupSelection {
        Selection.toggleAll(_ui.value.cleanupSelected,
            _ui.value.cleanupGroups.filterNot { it.held }.flatMap { g -> g.photos.map(PhotoCard::id) }.toSet())
    }

    /** "보관 추천 제외하고 선택" — 묶음마다 남길 한 장만 빼고 전부 고른다. "유지"로 둔 묶음은 건드리지 않는다. */
    fun selectAllButKept() = updateCleanupSelection {
        Grouping.allButKept(_ui.value.cleanupGroups.filterNot { it.held }.map(CleanupGroup::group))
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

    /**
     * "이 묶음은 유지" ↔ "다시 보기". 유지로 둔 묶음은 목록에 접힌 채 남고 선택·집계에서 빠지며, 다음에도 같은 제안을
     * 하지 않는다. 눌러서 되돌릴 수 있으므로 되돌릴 수 없는 동작이 아니다.
     */
    fun toggleHold(groupKey: String) {
        val now = _ui.value.cleanupGroups.firstOrNull { it.key == groupKey }?.held?.not() ?: return
        _ui.update { s ->
            val ids = s.cleanupGroups.firstOrNull { it.key == groupKey }?.photos?.map(PhotoCard::id).orEmpty().toSet()
            s.copy(
                cleanupGroups = s.cleanupGroups.map { if (it.key == groupKey) it.copy(held = now) else it },
                cleanupSelected = if (now) s.cleanupSelected - ids else s.cleanupSelected,
            )
        }
        updateCleanupSelection { _ui.value.cleanupSelected }
        viewModelScope.launch(Dispatchers.IO) {
            AppGraph.store.setDupeDismissed(groupKey, now)
            refreshBlocking()
        }
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

    /**
     * 날짜 머리글로 묶는다. 3장 이하인 이웃한 날은 한 머리글로 합친다(`DayGrouping`). 쪽을 붙이거나 일부를 지울 때도
     * 이 함수로 **처음부터 다시** 묶는다 — 합쳐진 머리글이 쪽 경계에서 어긋나지 않는다.
     */
    private fun toDays(photos: List<PhotoCard>): List<DayGroup> {
        val today = LocalDate.now(zone)
        return DayGrouping.group(photos, { p -> p.takenAt?.let { LocalDate.ofInstant(Instant.ofEpochSecond(it), zone) } }, today)
            .map { span ->
                DayGroup(
                    day = spanLabel(span, today), note = countNote(span.items), photos = span.items,
                    month = span.newest?.let { "%04d-%02d".format(it.year, it.monthValue) } ?: "none",
                )
            }
    }

    private fun spanLabel(span: DayGrouping.Span<PhotoCard>, today: LocalDate): String {
        val newest = span.newest ?: return "날짜 미상"
        if (!span.merged) return dayLabel(newest, today)
        val oldest = span.oldest ?: return dayLabel(newest, today)
        val year = if (newest.year == today.year) "" else "${newest.year}년 "
        return "$year${newest.monthValue}월 ${oldest.dayOfMonth}일 – ${newest.dayOfMonth}일"
    }

    /** 다음 쪽을 붙인다. 합쳐진 머리글이 쪽 경계에서 갈라지지 않게 전체를 다시 묶는다. */
    private fun mergeDays(existing: List<DayGroup>, more: List<PhotoCard>): List<DayGroup> {
        if (more.isEmpty()) return existing
        return toDays(existing.flatMap { it.photos } + more)
    }

    /** 사진은 "장", 영상은 "개"로 센다. 영상 3개를 "3장"이라고 적으면 사진으로 읽힌다. */
    private fun countNote(items: List<PhotoCard>): String {
        val videos = items.count { it.mediaKind == com.moalog.personal.data.MediaKind.VIDEO }
        val photos = items.size - videos
        return when {
            videos == 0 -> "${photos}장"
            photos == 0 -> "영상 ${videos}개"
            else -> "사진 ${photos}장 · 영상 ${videos}개"
        }
    }

    private fun dropFrom(days: List<DayGroup>, ids: Set<Long>): List<DayGroup> =
        toDays(days.flatMap { it.photos }.filterNot { it.id in ids })

    private fun dayLabel(epoch: Long): String =
        dayLabel(LocalDate.ofInstant(Instant.ofEpochSecond(epoch), zone), LocalDate.now(zone))

    private fun dayLabel(d: LocalDate, now: LocalDate): String = when {
        d == now -> "오늘"
        d == now.minusDays(1) -> "어제"
        d.year == now.year -> "${d.monthValue}월 ${d.dayOfMonth}일"
        else -> "${d.year}년 ${d.monthValue}월 ${d.dayOfMonth}일"
    }

    private companion object {
        const val WORKER_REFRESH_INTERVAL_MS = 2_000L

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
