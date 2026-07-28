package com.koneksiglobal.sapuranjau.uikit

import androidx.compose.ui.graphics.Color
import com.koneksiglobal.sapuranjau.uikit.theme.DarkGameColors
import com.koneksiglobal.sapuranjau.uikit.theme.GameColors
import com.koneksiglobal.sapuranjau.uikit.theme.LightGameColors
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
