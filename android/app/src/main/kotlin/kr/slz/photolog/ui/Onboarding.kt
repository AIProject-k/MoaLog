package kr.slz.photolog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 온보딩. 권한을 요구하기 **전에** 왜 필요한지와 무엇을 하지 않는지를 먼저 말한다.
 *
 * 디자인 원문의 "분석은 기기 안에서만 처리되고 사진은 어디에도 올라가지 않습니다"를
 * 지킬 수 있는 이유는 매니페스트에 `INTERNET` 권한이 없기 때문이다 — 사용자가 앱
 * 정보에서 직접 확인할 수 있는 약속이다(ANDROID.md §16).
 */
@Composable
fun OnboardingScreen(onGrant: () -> Unit, onLater: () -> Unit) {
    val t = theme
    Column(
        Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 24.dp),
    ) {
        // 디자인은 가운데 블록을 세로 중앙에 두고 버튼만 바닥에 붙인다.
        // verticalScroll과 Center를 같이 쓰면 Center가 안 먹는다(내용 높이로 측정되므로).
        // 이 화면은 내용이 고정이라 스크롤이 필요 없다.
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(26.dp, Alignment.CenterVertically),
        ) {
            ScanningGrid(Modifier.align(Alignment.CenterHorizontally))

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("쌓아둔 사진,\nAI가 알아서 정리합니다", style = fonts.display, color = t.text)
                Text(
                    "인물, 장소, 날짜, 화면 캡처까지 구분해 앨범을 만들어 둡니다. " +
                        "분석은 기기 안에서만 처리되고 사진은 어디에도 올라가지 않습니다.",
                    style = fonts.body, color = t.text.copy(alpha = .55f),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                BulletCard("기기 내 처리 · 네트워크 사용 없음")
                BulletCard("분류가 틀리면 바로 고칠 수 있고, 다음부터 반영됩니다")
            }
        }
        Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("사진 접근 허용하고 시작", onClick = onGrant)
            GhostButton("나중에 할게요", onClick = onLater)
        }
    }
}

/**
 * 권한이 **일부만** 허용된 상태(Android 14+ `READ_MEDIA_VISUAL_USER_SELECTED`).
 * 디자인에는 없는 화면이지만 안드로이드가 실제로 만드는 상태다 — 그냥 두면
 * 사용자는 앨범이 왜 비어 있는지 알 수 없다(ANDROID.md §4.3).
 */
@Composable
fun PartialAccessBanner(onRequestAll: () -> Unit) {
    val t = theme
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(Radius.card))
            .background(t.accentTint(.06f))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("선택한 사진만 볼 수 있습니다", style = fonts.itemTitle, color = t.accentHi)
        Text(
            "허용한 사진만 분류됩니다. 갤러리 전체를 정리하려면 전체 접근을 켜 주세요.",
            style = fonts.meta.copy(lineHeight = androidx.compose.ui.unit.TextUnit(19.2f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = t.textMuted,
        )
        Box(
            Modifier.padding(top = 4.dp)
                .clip(RoundedCornerShape(Radius.chip))
                .background(t.accent)
                .clickable(onClick = onRequestAll)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text("전체 허용", style = fonts.chip.copy(fontWeight = FontWeight.Bold), color = Color.White)
        }
    }
}
