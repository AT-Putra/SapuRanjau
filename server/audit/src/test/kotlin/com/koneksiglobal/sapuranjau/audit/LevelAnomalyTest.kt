package com.koneksiglobal.sapuranjau.audit

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

// Aturan sinyal = fungsi murni dari angka yang sudah dihitung `game` saat menutup level → diuji
// tanpa database maupun Spring. Penulisan barisnya diuji terpisah (`AuditServiceTest`).
class LevelAnomalyTest {

    // Ambang default: 80 ms/langkah (sama dengan jalur casual T-024) & lifeCap + 3.
    private fun sinyal(p: LevelFacts) = signalsOf(p, minMsPerMove = 80, livesOverCap = 3)

    private fun facts(moves: Int = 30, par: Int = 20, ms: Long = 30_000, lives: Int = 0, cap: Int = 3) =
        LevelFacts(moves, par, ms, lives, cap)

    @Test
    fun `permainan manusia biasa tak menghasilkan sinyal`() {
        assertEquals(emptyList(), sinyal(facts()))
    }

    @Test
    fun `menyamai jalur solver = perfect_path`() {
        assertEquals(listOf("perfect_path"), sinyal(facts(moves = 20, par = 20)))
        assertEquals(listOf("perfect_path"), sinyal(facts(moves = 19, par = 20)))
    }

    @Test
    fun `lebih cepat dari ambang per langkah = too_fast`() {
        assertEquals(listOf("too_fast"), sinyal(facts(moves = 30, par = 20, ms = 30 * 79)))
        // Tepat di ambang belum berbunyi: batasnya "< 80 ms", bukan "<= 80 ms".
        assertEquals(emptyList(), sinyal(facts(moves = 30, par = 20, ms = 30 * 80)))
    }

    @Test
    fun `nyawa jauh di atas lifeCap = lives_over_cap (ADR-0037)`() {
        assertEquals(emptyList(), sinyal(facts(lives = 5, cap = 3)), "mentok cap itu rutin, bukan anomali")
        assertEquals(listOf("lives_over_cap"), sinyal(facts(lives = 6, cap = 3)))
    }

    @Test
    fun `sinyal bisa berbunyi bersamaan`() {
        assertEquals(
            listOf("perfect_path", "too_fast", "lives_over_cap"),
            sinyal(facts(moves = 20, par = 20, ms = 100, lives = 9, cap = 3)),
        )
    }

    @Test
    fun `level tanpa langkah terskor tak dianggap terlalu cepat`() {
        assertEquals(emptyList(), sinyal(facts(moves = 0, par = 20, ms = 0)))
    }
}
