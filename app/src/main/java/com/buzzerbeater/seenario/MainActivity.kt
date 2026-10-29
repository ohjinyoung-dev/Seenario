package com.buzzerbeater.seenario

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.lifecycleScope
import com.buzzerbeater.seenario.data.SupabaseProvider
import com.buzzerbeater.seenario.data.UserSettingsRepository
import com.buzzerbeater.seenario.ui.theme.SeenarioTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val TAG = "SEENARIO"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) initSupabase()
        setContent {
            SeenarioTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = "Android",
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

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    SeenarioTheme {
        Greeting("Android")
    }
}