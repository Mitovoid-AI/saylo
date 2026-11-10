package ai.mitovoid.saylo

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** The Sayvra app: onboarding on first run (Onboarding.kt), then Home and the rest (Main.kt). The bubble and the
 *  keyboard open it when something is missing (the key, the microphone). */
class MainActivity : ComponentActivity() {
    /** Bumped on every resume, so screens re-check permissions changed in Android's Settings. */
    private var resumed by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        Dictation.init(this)
        setContent {
            SayloTheme {
                var onboarded by remember { mutableStateOf(Store.onboarded) }
                if (onboarded) MainApp(resumed) { Store.onboarded = false; onboarded = false }
                else Onboarding(resumed) { onboarded = true }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed++
    }
}
