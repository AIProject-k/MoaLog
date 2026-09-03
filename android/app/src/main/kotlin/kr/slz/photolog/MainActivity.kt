package kr.slz.photolog

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kr.slz.photolog.ui.*

/**
 * 단일 액티비티 + Compose. 내비게이션 라이브러리를 쓰지 않는다 — 화면 전환이
 * [Screen] 하나짜리 상태 기계이고 뒤로가기 규칙도 한 줄이다([AppViewModel.back]).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        enableEdgeToEdge()
        setContent { PhotoLogTheme { App() } }
    }
}

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    else -> arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val t = theme
    val vm: AppViewModel = viewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()

    fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    fun access(): Access = when {
        Build.VERSION.SDK_INT >= 33 && granted(Manifest.permission.READ_MEDIA_IMAGES) -> Access.Full
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> Access.Partial
        Build.VERSION.SDK_INT < 33 && granted(Manifest.permission.READ_EXTERNAL_STORAGE) -> Access.Full
        else -> Access.None
    }

    var acc by remember { mutableStateOf(access()) }
    var booted by remember { mutableStateOf(false) }
    var permissionDeferred by remember { mutableStateOf(false) }
    val screen = if (permissionDeferred) ui.screen else startupScreen(acc, ui.screen)

    // 권한이 있으면 곧바로 인덱싱을 시작한다. 고유 워커라 두 번 돌지 않는다.
    LaunchedEffect(acc) {
        if (acc != Access.None && !booted) { booted = true; vm.startIndexing() }
    }

    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { acc = access() }

    // 휴지통은 시스템이 확인 대화상자를 띄우고 30일 보관까지 해 준다(§12.4).
    // 우리가 파일을 지우는 코드는 어디에도 없다.
    val trash = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) {
            vm.toast("휴지통으로 보냈습니다. 30일 안에 복구할 수 있습니다.")
            vm.refresh()
        } else vm.toast("취소했습니다.")
    }

    fun sendToTrash() {
        val (delete, kept) = vm.cleanupCandidates()
        if (delete.isEmpty()) { vm.toast("정리할 중복이 없습니다."); return }
        val uris = delete.map {
            android.content.ContentUris.withAppendedId(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), it)
        }
        val req = MediaStore.createTrashRequest(ctx.contentResolver, uris, true)
        trash.launch(IntentSenderRequest.Builder(req.intentSender).build())
        if (kept.isNotEmpty()) vm.toast("${kept.size}개 그룹은 한 장씩 남겼습니다.")
    }

    // 분류 항목 토글. 끄면 그 축의 앨범을 숨긴다 — 이미 계산된 태그는 지우지 않는다.
    // 다시 켜면 바로 돌아오므로 되돌릴 수 없는 동작이 아니다.
    var offAxes by remember { mutableStateOf(loadOffAxes()) }
    val toggles = remember(offAxes) {
        Axis.ordered.filter { it != Axis.All }
            .map { CategoryToggle(it.name, it.label, axisDesc(it), it.name !in offAxes) }
    }

    val uriOf: (Long) -> String? = remember { { id -> AppGraph.store.photo(id)?.uri } }
    val visible = remember(ui, offAxes) { ui.copy(albums = ui.albums.filter { it.axis.name !in offAxes }) }

    Column(Modifier.fillMaxSize().background(t.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(Modifier.weight(1f)) {
            when (screen) {
                Screen.Permission -> OnboardingScreen(
                    onGrant = { ask.launch(mediaPermissions()) },
                    onLater = {
                        permissionDeferred = true
                        vm.toast("설정에서 언제든 허용할 수 있습니다.")
                    },
                )
                Screen.Analyzing -> AnalyzingScreen(
                    progress = ui.counts.progress, done = ui.counts.done, total = ui.counts.total,
                )
                Screen.Home -> HomeScreen(vm, visible, uriOf)
                Screen.Albums -> AlbumsScreen(vm, visible, uriOf)
                Screen.Category -> CategoryScreen(vm, ui, uriOf)
                Screen.Photo -> PhotoScreen(vm, ui)
                Screen.Search -> SearchScreen(vm, ui)
                Screen.Fix -> FixScreen(vm, ui)
                Screen.Settings -> SettingsScreen(
                    vm, ui, toggles,
                    onFlip = { id ->
                        offAxes = if (id in offAxes) offAxes - id else offAxes + id
                        saveOffAxes(offAxes)
                    },
                    onTrash = ::sendToTrash,
                )
            }

            // 일부 허용은 안드로이드가 실제로 만드는 상태다. 그냥 두면 앨범이 왜
            // 비어 있는지 알 수 없다(§4.3).
            if (acc == Access.Partial && screen == Screen.Home) {
                Box(Modifier.align(Alignment.TopCenter).padding(top = 84.dp)) {
                    PartialAccessBanner { ask.launch(mediaPermissions()) }
                }
            }
            Toast(ui.toast, Modifier.align(Alignment.BottomCenter))
        }
        if (screen.showsNav) BottomNav(screen) { vm.go(it) }
    }

    // 하위 화면에서는 위로, 탭 화면에서는 시스템에 넘겨 앱을 닫는다.
    BackHandler(enabled = !screen.showsNav) { vm.back() }
}

/** 축 설명. 디자인의 toggle desc를 **실제 동작**에 맞춰 적었다. */
private fun axisDesc(a: Axis): String = when (a) {
    Axis.Person -> "사람이 찍힌 사진 앨범"
    Axis.Travel -> "위치와 이동 경로로 묶은 기록"
    Axis.Doc -> "문서·영수증·화면 캡처"
    Axis.Food -> "음식과 식탁"
    Axis.Moment -> "실내 일상, 상품, 그 밖의 장면"
    Axis.All -> ""
}

private fun loadOffAxes(): Set<String> =
    AppGraph.store.metaGet("off_axes")?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

private fun saveOffAxes(s: Set<String>) = AppGraph.store.metaPut("off_axes", s.joinToString(","))
