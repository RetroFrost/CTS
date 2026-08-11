package io.github.retrofrost.cts.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.retrofrost.cts.android.model.VisualModel
import io.github.retrofrost.cts.android.ui.CtsAndroidAppV2
import io.github.retrofrost.cts.android.ui.CtsFirstRunSetup
import io.github.retrofrost.cts.android.ui.theme.CtsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CtsTheme {
                val preferences = remember {
                    getSharedPreferences("cts-first-run", MODE_PRIVATE)
                }
                var setupComplete by remember {
                    // CTS 2.0 has a materially new first-run flow. Do not let the old
                    // alpha setup flag skip it on an upgraded installation.
                    mutableStateOf(preferences.getBoolean("setup-complete-v2", false))
                }
                var preferredModel by remember {
                    mutableStateOf(
                        VisualModel.fromId(preferences.getString("preferred-model", null)),
                    )
                }

                if (!setupComplete) {
                    CtsFirstRunSetup(
                        initialModel = preferredModel,
                        onComplete = { model ->
                            preferredModel = model
                            preferences.edit()
                                .putString("preferred-model", model.id)
                                .putBoolean("setup-complete-v2", true)
                                .apply()
                            setupComplete = true
                        },
                    )
                } else {
                    CtsAndroidAppV2(initialModel = preferredModel)
                }
            }
        }
    }
}
