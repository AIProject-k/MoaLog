package com.moalog.personal.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign

/**
 * 여러 줄이 될 수 있는 글. 디자인의 `text-wrap: pretty`가 하던 일을 한다 — 마지막 줄에 글자 하나만 덩그러니 남지 않게.
 *
 * 한국어는 글자 사이 어디서든 줄이 바뀌어서 "…합니|다."처럼 끝 글자가 혼자 내려가곤 한다. 끝의 두 글자 사이에
 * WORD JOINER(U+2060)를 넣어 두면 둘이 함께 내려간다. 이 문자는 눈에 보이지 않고 폭이 없다.
 * 스크린 리더가 읽는 글은 원래 문장 그대로 둔다.
 */
@Composable
fun Prose(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    textAlign: TextAlign? = null,
) {
    Text(
        text.keepTail(), style = style, color = color, textAlign = textAlign,
        modifier = modifier.semantics { this.text = AnnotatedString(text) },
    )
}

private const val WORD_JOINER = '\u2060'

/** 끝의 두 글자(끝의 마침표·물음표 같은 부호는 건너뛴다) 사이에 [WORD_JOINER]를 넣는다. 앞이 공백이면 이미 낱말 경계라 그대로 둔다. */
internal fun String.keepTail(): String {
    var end = length
    while (end > 0 && !this[end - 1].isLetterOrDigit()) end--
    if (end < 2 || !this[end - 2].isLetterOrDigit()) return this
    return substring(0, end - 1) + WORD_JOINER + substring(end - 1)
}
