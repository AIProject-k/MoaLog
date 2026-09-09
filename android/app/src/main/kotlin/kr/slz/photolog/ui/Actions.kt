package kr.slz.photolog.ui

import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 안드로이드가 있어야만 할 수 있는 일. 화면 깊은 곳에서도 필요한데 람다로 계속
 * 내려보내면 시그니처가 지저분해져서 [staticCompositionLocalOf]로 한 번만 심는다.
 *
 * 둘 다 **시스템이 확인을 받는다** — 휴지통은 안드로이드의 확인 대화상자를 거치고,
 * 공유는 공유 시트에서 사용자가 받는 앱을 직접 고른다. 우리가 조용히 파일을
 * 지우거나 내보내는 경로는 없다.
 */
@Immutable
class AppActions(
    val trash: (Collection<Long>) -> Unit,
    val share: (Collection<Long>) -> Unit,
)

val LocalActions = staticCompositionLocalOf<AppActions> {
    error("AppActions가 심어지지 않았습니다")
}

/**
 * media_id로 content URI를 만든다. DB에 저장된 `uri` 열과 **같은 값**이다 —
 * 모든 사진이 `MediaStore.Images.Media`의 외부 볼륨에서 왔기 때문이다.
 *
 * 뷰어가 한 장 넘길 때마다 DB를 치지 않으려고 이렇게 한다.
 */
fun mediaUri(id: Long): String = ContentUris.withAppendedId(
    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), id
).toString()
