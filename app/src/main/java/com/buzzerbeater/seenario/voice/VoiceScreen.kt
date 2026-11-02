package com.buzzerbeater.seenario.voice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.buzzerbeater.seenario.ui.theme.SeenarioTheme

private const val NOTHING_HEARD = "화면을 누르고 말해 주세요"

/**
 * 현재 음성 상태(대기 중 / 듣는 중 / 말하는 중)와 마지막으로 인식한 문장을 큰 글씨로 보여 준다.
 * 화면 전체가 하나의 버튼이라 어디를 탭해도(TalkBack에서는 두 번 탭) 듣기를 시작한다.
 */
@Composable
fun VoiceScreen(
    state: VoiceUiState,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val heard = state.lastHeard
    val tapLabel = if (state.hasMicPermission) "음성 명령 말하기" else "마이크 권한 다시 요청"

    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClickLabel = tapLabel, role = Role.Button, onClick = onTap)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.status.label,
            fontSize = 56.sp,
            lineHeight = 64.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics { contentDescription = "현재 상태: ${state.status.label}" },
        )
        Spacer(Modifier.height(40.dp))
        Text(
            text = heard?.let { "“$it”" } ?: NOTHING_HEARD,
            fontSize = 32.sp,
            lineHeight = 40.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.semantics {
                contentDescription = heard?.let { "마지막으로 인식한 문장: $it" } ?: NOTHING_HEARD
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun VoiceScreenPreview() {
    SeenarioTheme {
        VoiceScreen(VoiceUiState(VoiceStatus.LISTENING, "지금 풍경 묘사해줘"), onTap = {})
    }
}
