package com.paherdin.record2

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

// A1 compilation host; product content awaits the owner's F0 design.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { }
    }
}
