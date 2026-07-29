package com.koneksiglobal.sapuranjau

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.koneksiglobal.sapuranjau.casual.CasualScreen
import com.koneksiglobal.sapuranjau.leaderboard.LeaderboardScreen
import com.koneksiglobal.sapuranjau.tournament.TournamentScreen
import com.koneksiglobal.sapuranjau.uikit.theme.SapuRanjauTheme
import com.koneksiglobal.sapuranjau.wallet.WalletScreen

private enum class Tujuan(val label: String, val ikon: String) {
    CASUAL("Casual", "🎯"),
    TURNAMEN("Turnamen", "🏆"),
    NYAWA("Nyawa", "❤️"),
    PERINGKAT("Peringkat", "📊"),
}

// Satu Activity, seluruh layar dirender Compose. **Tanpa library navigasi**: semua tujuan bersifat
// top-level dan tak punya back-stack untuk dikelola — satu state + NavigationBar sudah cukup. ViewModel
// tiap layar hidup di ViewModelStore Activity, jadi berpindah tab TIDAK mereset papan casual
// (janji "progres tersimpan" di dialog nyawa turnamen, ADR-0037).
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { SapuRanjauTheme { App() } }
    }
}

@Composable
private fun App() {
    var tujuan by rememberSaveable { mutableStateOf(Tujuan.CASUAL) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tujuan.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tujuan == t,
                        onClick = { tujuan = t },
                        icon = { Text(t.ikon) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (tujuan) {
                Tujuan.CASUAL -> CasualScreen()
                Tujuan.TURNAMEN -> TournamentScreen(
                    onMainCasual = { tujuan = Tujuan.CASUAL },
                    onBeliNyawa = { tujuan = Tujuan.NYAWA },
                )
                Tujuan.NYAWA -> WalletScreen(onMainCasual = { tujuan = Tujuan.CASUAL })
                Tujuan.PERINGKAT -> LeaderboardScreen()
            }
        }
    }
}
