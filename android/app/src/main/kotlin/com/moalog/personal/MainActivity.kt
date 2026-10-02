package com.moalog.personal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.moalog.personal.data.MediaAccessPolicy
import com.moalog.personal.ui.*
import com.moalog.personal.work.IndexWorker

/**
 * 단일 액티비티 + Compose. 내비게이션 라이브러리를 쓰지 않는다 — 화면 전환이
 * [Screen] 하나짜리 상태 기계이고 뒤로가기 규칙도 한 줄이다([AppViewModel.back]).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        coil.Coil.setImageLoader(videoImageLoader(this))
        // 앱은 항상 어둡다. 시스템이 밝은 모드여도 상태바·내비게이션 바 아이콘이 밝아야 보인다.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { PhotoLogTheme { App() } }
    }
}

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    else -> arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun App() {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val t = theme
    val vm: AppViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()

    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    fun access(): Access = when {
        Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) && granted(Manifest.permission.READ_MEDIA_VIDEO) -> Access.Full
        Build.VERSION.SDK_INT >= 33 && (granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VIDEO)) -> Access.Partial
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.Partial
        Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.Full
        else -> Access.None
    }

    var acc by remember { mutableStateOf(access()) }
    var booted by remember { mutableStateOf(false) }
    var lastPermissionState by remember { mutableStateOf<List<Boolean>?>(null) }
    // "나중에 할게요": 권한 화면에 머물며 안내만 바꾼다(디자인의 Permission/later). 허용하기 전에는 갤러리가 비어 있다.
    var laterPressed by remember { mutableStateOf(false) }
    val screen = startupScreen(acc, ui.screen)

    // 권한이 있으면 곧바로 인덱싱을 시작한다. 고유 워커라 두 번 돌지 않는다.
    LaunchedEffect(acc) {
        if (acc != Access.None && !booted) { booted = true; vm.startIndexing() }
    }

    DisposableEffect(lifecycleOwner, ctx) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val current = access()
                val permissionState = listOf(
                    granted(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
                        else Manifest.permission.READ_EXTERNAL_STORAGE),
                    Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.READ_MEDIA_VIDEO),
                    Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED),
                )
                val changed = MediaAccessPolicy.permissionsChanged(lastPermissionState, permissionState)
                acc = current
                if (changed) IndexWorker.start(ctx)
                lastPermissionState = permissionState
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { acc = access(); if (acc != Access.None) vm.startIndexing() }

    // 휴지통은 시스템이 확인 대화상자를 띄우고 30일 보관까지 해 준다(§12.4).
    // 우리가 파일을 지우는 코드는 어디에도 없다.
    val trash = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) {
            vm.toast("휴지통으로 보냈습니다. 30일 안에 복구할 수 있습니다.")
            vm.onTrashConfirmed()
        } else {
            vm.cancelTrash()
            vm.toast("취소했습니다.")
        }
    }

    fun uriOf(id: Long): Uri = Uri.parse(mediaUri(id))

    /**
     * 휴지통으로. **어떤 id를 보냈는지 뷰모델에 먼저 알린다** — 시스템이 확인해 주면
     * 그 목록을 화면과 DB에서 바로 지워야 방금 정리한 사진이 계속 보이지 않는다.
     */
    fun sendToTrash(ids: Collection<Long>) {
        if (ids.isEmpty()) { vm.toast("휴지통으로 보낼 사진·영상을 고르세요."); return }
        vm.willTrash(ids)
        val req = MediaStore.createTrashRequest(ctx.contentResolver, ids.map(::uriOf), true)
        trash.launch(IntentSenderRequest.Builder(req.intentSender).build())
    }

    /** 공유 시트. 받는 앱은 사용자가 고른다 — 우리가 어디로 보낼지 정하지 않는다. */
    fun share(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val uris = ArrayList(ids.map(::uriOf))
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris) }
        }
        intent.type = when {
            ids.all { it < 0 } -> "video/*"
            ids.all { it > 0 } -> "image/*"
            else -> "*/*"
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(intent, "공유"))
    }

    // 분류 항목 토글. 끄면 그 축의 AI 앨범을 숨긴다 — 이미 계산된 태그는 지우지 않는다.
    // 다시 켜면 바로 돌아오므로 되돌릴 수 없는 동작이 아니다.
    var offAxes by remember { mutableStateOf(loadOffAxes()) }
    val toggles = remember(offAxes) {
        Axis.ordered.filter { it != Axis.All }
            .map { CategoryToggle(it.name, it.label, axisDesc(it), it.name !in offAxes) }
    }

    val uriText: (Long) -> String? = remember { { id -> mediaUri(id) } }
    // 숨김은 AI 앨범에만 건다. 기기 폴더와 내 앨범은 우리가 분류한 것이 아니다.
    val visible = remember(ui, offAxes, acc) {
        ui.copy(
            albums = ui.albums.filterNot { it.kind == AlbumKind.Tag && it.axis.name in offAxes },
            limitedAccess = acc == Access.Partial,
        )
    }
    val actions = remember { AppActions(trash = ::sendToTrash, share = ::share) }
    val fullScreenVideo = screen == Screen.Viewer && ui.videoControls.fullscreen
    val imeOpen = WindowInsets.isImeVisible

    // 탭 바는 탭 화면에서만. 선택 중에는 선택 바가 그 자리를 쓰고, 검색 중 키보드가 올라오면 키보드가 덮는다(디자인).
    val showTabs = screen.showsNav && !ui.selectionMode && !(screen == Screen.Search && imeOpen)
    // 탭 바가 없고 시스템 바 뒤까지 그리지 않는 화면은 내비게이션 바만큼 비운다.
    val padNav = !showTabs && !ui.selectionMode &&
        screen in setOf(Screen.Permission, Screen.Analyzing, Screen.Settings)

    val placeHolder = rememberSaveableStateHolder()
    val keepPlace: @Composable (String, @Composable () -> Unit) -> Unit = { key, content ->
        placeHolder.SaveableStateProvider(key, content)
    }

    CompositionLocalProvider(LocalActions provides actions) {
        Box(Modifier.fillMaxSize().background(t.bg)) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier.weight(1f)
                        .then(if (padNav) Modifier.navigationBarsPadding() else Modifier)
                        .then(if (screen == Screen.Search) Modifier.imePadding() else Modifier),
                ) {
                    when (screen) {
                        Screen.Permission -> OnboardingScreen(
                            later = laterPressed,
                            onGrant = { ask.launch(mediaPermissions()) },
                            onLater = { laterPressed = true },
                        )
                        Screen.Analyzing -> AnalyzingScreen(
                            progress = ui.phaseCounts.progress, done = ui.phaseCounts.done, total = ui.phaseCounts.total,
                            paused = ui.paused, onResume = vm::resumeIndexing,
                        )
                        // 화면을 떠났다 돌아와도 보던 자리를 지킨다: 사진을 열었다 닫거나 탭을 오가면 스크롤이 맨 위로 가던 문제.
                        // 앨범 상세·분류 수정은 앨범마다 따로 기억한다. 뷰어는 열 때마다 새로 시작해야 해서 넣지 않는다
                        // (저장된 페이지가 복원되면 다른 사진이 열린다).
                        Screen.Photos -> keepPlace("photos") { PhotosScreen(vm, visible) { ask.launch(mediaPermissions()) } }
                        Screen.Albums -> keepPlace("albums") { AlbumsScreen(vm, visible, uriText) }
                        Screen.Category -> keepPlace("category:${ui.openAlbum?.key}") { CategoryScreen(vm, ui) }
                        Screen.Viewer -> ViewerScreen(vm, ui)
                        Screen.Search -> keepPlace("search") { SearchScreen(vm, ui) }
                        Screen.Fix -> keepPlace("fix:${ui.openAlbum?.key}") { FixScreen(vm, ui) }
                        Screen.Cleanup -> keepPlace("cleanup") { CleanupScreen(vm, ui) }
                        Screen.Settings -> keepPlace("settings") {
                            SettingsScreen(
                                vm, ui, toggles,
                                onFlip = { id ->
                                    offAxes = if (id in offAxes) offAxes - id else offAxes + id
                                    saveOffAxes(offAxes)
                                },
                            )
                        }
                    }
                }
                if (showTabs) TabBar(screen, cleanupDot = ui.cleanupCount > 0, onGo = { vm.go(it) })
            }

            // ---- 화면 위에 얹히는 것들
            if (ui.menuOpen && screen == Screen.Category) CategoryMenu(vm, ui)
            AlbumSheets(vm, ui, uriText)
            ui.dialog?.let { DialogHost(vm, ui, it) }
            val navBottom = with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }
            Toast(
                text = ui.toast?.text, action = ui.toast?.action, onAction = vm::toastAction,
                bottom = toastBottom(screen, ui, showTabs) + navBottom,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    // 정리 탭에 들어올 때마다 묶음을 다시 읽는다. 사진을 지운 뒤 남은 묶음이 달라진다.
    LaunchedEffect(screen) { if (screen == Screen.Cleanup) vm.loadCleanup() }

    // 위에 얹힌 것부터 닫고, 하위 화면에서는 위로, 선택 중이면 선택부터 푼다. 탭 화면에서는 시스템에 넘겨 앱을 닫는다.
    BackHandler(
        enabled = ui.dialog != null || ui.sheet != null || ui.menuOpen || !screen.showsNav || ui.selectionMode,
    ) {
        when {
            ui.dialog != null -> vm.closeDialog()
            ui.sheet != null -> vm.closeSheet()
            ui.menuOpen -> vm.setMenu(false)
            ui.selectionMode && screen != Screen.Fix -> vm.cancelSelection()
            else -> vm.back()
        }
    }
}

/**
 * 토스트가 가려지면 안 되는 것(탭 바·선택 바·정리 바·뷰어 하단) 위로 띄우는 높이. 디자인 값(탭 96 · 선택 112 · 정리 176 ·
 * 뷰어 104 · 그 밖 24)에서 제스처 영역 20dp를 뺐다 — 실제 내비게이션 바 높이는 호출 쪽에서 더한다.
 */
private fun toastBottom(screen: Screen, ui: AppViewModel.Ui, showTabs: Boolean): androidx.compose.ui.unit.Dp = when {
    screen == Screen.Viewer -> 84.dp
    ui.selectionMode -> 92.dp
    screen == Screen.Cleanup && ui.cleanupSelected.isNotEmpty() -> 156.dp
    screen == Screen.Fix -> 92.dp
    showTabs -> 76.dp
    else -> 16.dp
}

@Composable
private fun CategoryMenu(vm: AppViewModel, ui: AppViewModel.Ui) {
    val t = theme
    val album = ui.openAlbum ?: return
    if (album.kind == AlbumKind.Tag) PopupMenu(
        onClose = { vm.setMenu(false) },
        items = listOf(MenuItem("분류 수정", I.Ai, t.text1) { vm.setMenu(false); vm.openFix() }),
        note = "AI 앨범은 삭제할 수 없습니다. 끄려면 설정 › 분류 항목.",
    ) else PopupMenu(
        onClose = { vm.setMenu(false) },
        items = listOf(MenuItem("앨범 삭제", I.Trash, t.dangerText) { vm.openDialog(Dialog.DeleteAlbum) }),
        note = "앨범을 지워도 사진·영상은 그대로 있습니다.",
    )
}

@Composable
private fun DialogHost(vm: AppViewModel, ui: AppViewModel.Ui, dialog: Dialog) {
    when (dialog) {
        Dialog.DeleteAlbum -> ConfirmDialog(
            title = "'${ui.openAlbum?.title.orEmpty()}' 앨범을 삭제할까요?",
            body = "앨범만 지웁니다. 사진·영상은 그대로 있습니다.",
            confirm = "앨범 삭제",
            onConfirm = { vm.closeDialog(); vm.deleteOpenAlbum() },
            onCancel = vm::closeDialog,
        )
        Dialog.DeleteAll -> ConfirmDialog(
            title = "분류 결과를 모두 삭제할까요?",
            body = "사진·영상은 지우지 않습니다. 다시 분석하면 분류가 새로 만들어집니다.",
            confirm = "분류 결과 삭제",
            onConfirm = { vm.closeDialog(); vm.clearAnalysis() },
            onCancel = vm::closeDialog,
        )
    }
}

/** 축 설명. 디자인의 toggle desc를 **실제 동작**에 맞춰 적었다. */
private fun axisDesc(a: Axis): String = when (a) {
    Axis.Person -> "사람이 찍힌 사진·영상 앨범"
    Axis.Travel -> "위치와 이동 경로로 묶은 기록"
    Axis.Doc -> "문서·영수증·화면 캡처"
    Axis.Food -> "음식과 식탁"
    Axis.Moment -> "실내 일상, 상품, 그 밖의 장면"
    Axis.All -> ""
}

private fun loadOffAxes(): Set<String> =
    AppGraph.store.metaGet("off_axes")?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

private fun saveOffAxes(s: Set<String>) = AppGraph.store.metaPut("off_axes", s.joinToString(","))
