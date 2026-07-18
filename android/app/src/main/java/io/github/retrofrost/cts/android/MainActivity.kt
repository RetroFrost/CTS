package io.github.retrofrost.cts.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.retrofrost.cts.android.ui.GoogleCtsApp
import io.github.retrofrost.cts.android.ui.theme.CtsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CtsTheme {
                GoogleCtsApp()
            }
        }
    }
}
