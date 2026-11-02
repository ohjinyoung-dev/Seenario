package com.buzzerbeater.seenario

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.buzzerbeater.seenario.data.SupabaseProvider
import com.buzzerbeater.seenario.data.UserSettingsRepository
import com.buzzerbeater.seenario.ui.theme.SeenarioTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val TAG = "SEENARIO"

class MainActivity : ComponentActivity() {
    private lateinit var seenario: SeenarioController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) initSupabase()
        // 음성·카메라 연결과 화면 조립은 SeenarioScreen.kt에 있다.
        seenario = SeenarioController(this)
        setContent {
            SeenarioTheme {
                SeenarioScreen(seenario)
            }
        }
    }

    // 헤드셋 버튼 → 듣기 시작 (화면에 있을 때만 키 이벤트가 온다)
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        seenario.handleKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        seenario.handleKeyUp(keyCode) || super.onKeyUp(keyCode, event)

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