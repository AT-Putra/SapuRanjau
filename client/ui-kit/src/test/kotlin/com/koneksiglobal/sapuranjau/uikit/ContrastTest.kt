package com.koneksiglobal.sapuranjau.uikit

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.koneksiglobal.sapuranjau.uikit.theme.DarkGameColors
import com.koneksiglobal.sapuranjau.uikit.theme.DarkScheme
import com.koneksiglobal.sapuranjau.uikit.theme.GameColors
import com.koneksiglobal.sapuranjau.uikit.theme.LightGameColors
import com.koneksiglobal.sapuranjau.uikit.theme.LightScheme
import org.junit.jupiter.api.Test
import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertTrue

// Kontras angka 1–8 = janji aksesibilitas (03 §5 WCAG AA), jadi dijaga TEST — bukan komentar di
// dokumen yang membusuk diam-diam. Nilai klasik apa adanya sempat gagal di tema terang
// (#1565C0 = 4,48 · #2E7D32 = 3,99 · #C62828 = 4,38) dan itu baru ketahuan karena diukur (ADR-0042).
class ContrastTest {

    @Test
    fun `angka 1-8 memenuhi WCAG AA di tema terang`() = assertAllReadable(LightGameColors, "terang")

    @Test
    fun `angka 1-8 memenuhi WCAG AA di tema gelap`() = assertAllReadable(DarkGameColors, "gelap")

    @Test
    fun `bendera dan bom cukup kontras terhadap latar selnya`() {
        // Bendera tampil di sel TERTUTUP, bom di sel yang sudah meledak (latar mineDanger).
        listOf(
            Triple("bendera terang", LightGameColors.flag, LightGameColors.cellHidden),
            Triple("bendera gelap", DarkGameColors.flag, DarkGameColors.cellHidden),
        ).forEach { (nama, fg, bg) ->
            val r = contrast(fg, bg)
            assertTrue(r >= AA) { "$nama rasio %.2f < $AA".format(r) }
        }
    }

    // Peran Material dipakai komponen yang belum ditulis (dialog, kartu, text field T-032/033/034),
    // jadi pasangannya dijaga di sini — bukan menunggu ketahuan lewat screenshot seperti chip ungu.
    @Test
    fun `pasangan teks-latar Material memenuhi WCAG AA di kedua tema`() {
        listOf(LightScheme to "terang", DarkScheme to "gelap").forEach { (scheme, tema) ->
            textPairs(scheme).forEach { (peran, pair) ->
                val r = contrast(pair.first, pair.second)
                assertTrue(r >= AA) { "$peran tema $tema: rasio %.2f < $AA".format(r) }
            }
        }
    }

    private fun textPairs(s: ColorScheme) = listOf(
        "onPrimary/primary" to (s.onPrimary to s.primary),
        "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
        "onSecondary/secondary" to (s.onSecondary to s.secondary),
        "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
        "onTertiary/tertiary" to (s.onTertiary to s.tertiary),
        "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
        "onBackground/background" to (s.onBackground to s.background),
        "onSurface/surface" to (s.onSurface to s.surface),
        "onSurfaceVariant/surfaceVariant" to (s.onSurfaceVariant to s.surfaceVariant),
        "onSurface/surfaceContainer" to (s.onSurface to s.surfaceContainer),
        "onError/error" to (s.onError to s.error),
        "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
        "inverseOnSurface/inverseSurface" to (s.inverseOnSurface to s.inverseSurface),
    )

    private fun assertAllReadable(colors: GameColors, tema: String) {
        (1..8).forEach { n ->
            val r = contrast(colors.numbers[n], colors.cellRevealed)
            assertTrue(r >= AA) { "angka $n tema $tema: rasio %.2f < $AA".format(r) }
        }
    }

    // WCAG 2.x relative luminance + rasio kontras.
    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun luminance(c: Color): Double {
        fun lin(v: Float): Double {
            val d = v.toDouble()
            return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private companion object {
        const val AA = 4.5 // teks normal, WCAG AA
    }
}
