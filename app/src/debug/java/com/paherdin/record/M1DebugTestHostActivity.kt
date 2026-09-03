package com.paherdin.record

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable

/** Generic debug-only Compose host used by instrumented recreation tests. */
class M1DebugTestHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            checkNotNull(M1DebugTestContent.content) {
                "M1 debug test content must be installed before Activity launch"
            }.invoke()
        }
    }
}

/** Process-local content seam; both this registry and its Activity are absent from release. */
internal object M1DebugTestContent {
    var content: (@Composable () -> Unit)? = null
}
