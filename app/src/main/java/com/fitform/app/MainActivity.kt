package com.fitform.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.fitform.app.ui.theme.FitFormColors
import com.fitform.app.ui.theme.FitFormTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FitFormTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(FitFormColors.Ink),
                    color = FitFormColors.Ink,
                ) {
                    FitFormNavHost()
                }
            }
        }
    }
}
