package com.buzzerbeater.seenario

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.buzzerbeater.seenario.data.SupabaseProvider
import com.buzzerbeater.seenario.data.UserSettingsRepository
import com.buzzerbeater.seenario.ui.theme.SeenarioTheme
import com.buzzerbeater.seenario.voice.VoiceController
import com.buzzerbeater.seenario.voice.VoiceScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val TAG = "SEENARIO"

class MainActivity : ComponentActivity() {
    private lateinit var voiceController: VoiceController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) initSupabase()
        // TODO: 카메라 모듈이 준비되면 이 콜백에서 캡처를 시작한다.
        voiceController = VoiceController(this) { Log.d(TAG, "onDescribeRequested (카메라 연결 전)") }
        lifecycle.addObserver(voiceController)
        setContent {
            SeenarioTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    VoiceScreen(
                        state = voiceController.uiState,
                        onTap = voiceController::onScreenTapped,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    private fun initSupabase() {
        val repository = UserSettingsRepository(SupabaseProvider.client)
        lifecycleScope.launch {
            try {
                val userId = repository.ensureSignedIn()
                val settings = repository.getOrCreate(userId)
                Log.d(TAG, "sound_mode=${settings.soundMode}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Supabase init failed", e)
            }
        }
    }
}