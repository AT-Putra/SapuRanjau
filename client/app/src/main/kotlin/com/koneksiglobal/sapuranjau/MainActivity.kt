package com.koneksiglobal.sapuranjau

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.koneksiglobal.sapuranjau.casual.CasualScreen
import com.koneksiglobal.sapuranjau.uikit.theme.SapuRanjauTheme

// Satu Activity, seluruh layar dirender Compose. Fase ini cuma casual; turnamen/dompet/leaderboard
// menyusul (T-032/033/034) — navigasi baru ditambahkan saat benar-benar ada tujuan kedua.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SapuRanjauTheme {
                CasualScreen()
            }
        }
    }
}
