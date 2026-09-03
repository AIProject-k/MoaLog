package kr.slz.photolog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kr.slz.photolog.core.QueryParser

/**
 * 자연어 검색. 디자인의 구조를 지킨다: 입력 → 추천 칩 → AI 요약 → 3열 결과.
 *
 * 두 가지가 디자인과 다르다:
 *  - **해석 칩을 보여 준다.** 파서가 "작년"을 시간 범위로, "바다"를 CLIP 질의로 갈랐다는
 *    것을 사용자가 볼 수 있어야 한다. 조용히 틀리는 것보다 낫다(§10.2).
 *  - **"AI 요약"은 문장 틀이다.** LLM을 쓰지 않으므로 지어내지 않는다 — 찾은 조건과
 *    개수·기간을 그대로 적는다(§11.4).
 */
@Composable
fun SearchScreen(vm: AppViewModel, ui: AppViewModel.Ui) {
    val t = theme
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BackButton { vm.go(Screen.Home) }
                Box(
                    Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(Radius.field))
                        .background(t.surface2).padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = ui.query,
                        onValueChange = vm::onQuery,
                        singleLine = true,
                        textStyle = fonts.body.copy(color = t.text),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(t.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.search() }),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { inner ->
                            if (ui.query.isEmpty())
                                Text("말로 사진 찾기", style = fonts.body, color = t.text.copy(alpha = .42f))
                            inner()
                        },
                    )
                }
            }
        }

        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp),
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
            ) {
                items(vm.suggestions()) { s ->
                    Box(
                        Modifier.clip(RoundedCornerShape(Radius.card)).background(t.surface3)
                            .clickable { vm.pickSuggestion(s) }
                            .padding(horizontal = 13.dp, vertical = 8.dp)
                    ) { Text(s, style = fonts.chip, color = t.text.copy(alpha = .62f)) }
                }
            }
        }

        // 파서가 무엇을 소비했는지. 칩이 없으면 전부 CLIP으로 갔다는 뜻이다.
        ui.parsed?.chips?.takeIf { it.isNotEmpty() }?.let { chips ->
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp),
                    modifier = Modifier.padding(bottom = 14.dp),
                ) { items(chips) { c -> Chip(c, tinted = true) } }
            }
        }

        when {
            ui.searching -> item {
                Text("찾고 있습니다…", style = fonts.caption, color = t.textFaint,
                    modifier = Modifier.fillMaxWidth().padding(44.dp), textAlign = TextAlign.Center)
            }
            ui.results.isNotEmpty() -> {
                item {
                    Column(
                        Modifier.padding(horizontal = 16.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.card)).background(t.accentTint(.07f))
                            .padding(horizontal = 17.dp, vertical = 15.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("검색 요약", style = fonts.badge, color = t.accent)
                        Text(ui.resultSummary, style = fonts.bodySm, color = t.text.copy(alpha = .88f))
                    }
                }
                items(ui.results.chunked(3)) { row ->
                    Row(
                        Modifier.padding(top = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        for (p in row) Thumb(p.uri,
                            Modifier.weight(1f).aspectRatio(1f).clickable { vm.openPhoto(p.id) })
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
            ui.parsed != null -> item { Hint("조건에 맞는 사진을 찾지 못했습니다.\n다른 말로 적어보세요.") }
            else -> item { Hint("찾고 싶은 장면을 문장으로 적어보세요.\n날짜, 장소, 사물을 섞어도 됩니다.") }
        }
    }
}

@Composable
private fun Hint(text: String) = Text(
    text, style = fonts.caption, color = theme.text.copy(alpha = .4f),
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 34.dp, vertical = 44.dp),
)
