package com.koneksiglobal.sapuranjau.engine

import kotlin.test.Test
import kotlin.test.assertTrue

// Benchmark kalibrasi T-011 (ADR-0031): latency generate klik-pertama + tingkat sukses per
// grid/density → data untuk kalibrasi ceiling density, K, ambang warning admin. Bukan gate CI
// ketat (angka dicetak, bukan di-assert) — hanya korektnes yang di-assert. Budget target
// reveal p95 < 200ms (ARCH §11). Jalankan: ./gradlew :shared:engine-core:test --info (lihat stdout).
class GeneratorBenchmark {

    private val engine = MinesweeperEngine()

    private data class Probe(val w: Int, val h: Int, val mines: Int, val n: Int, val k: Int)

    @Test fun benchmarkGenerateLatency() {
        val probes = listOf(
            Probe(9, 9, 10, n = 30, k = 200), // beginner 12.3%
            Probe(9, 9, 13, n = 20, k = 200), // 16.0%
            Probe(16, 16, 40, n = 20, k = 200), // intermediate 15.6%
            Probe(16, 16, 50, n = 12, k = 150), // 19.5%
            Probe(16, 30, 99, n = 8, k = 80), // expert 20.6% — sonda ceiling
        )
        println("\n=== T-011 generate benchmark (density → latency, ADR-0031) ===")
        println("grid       mines  dens%   n  ok   p50ms  p95ms  maxms")
        for (p in probes) {
            val ok = ArrayList<Long>()
            var fails = 0
            for (seed in 1..p.n.toLong()) {
                val fc = CellIndex(p.w / 2, p.h / 2)
                val t0 = System.nanoTime()
                val board = try {
                    engine.generateNoGuess(LevelConfig(p.w, p.h, p.mines), seed, fc, k = p.k)
                } catch (e: IllegalStateException) {
                    fails++
                    null
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (board != null) {
                    ok += ms
                    assertTrue(engine.isSolvableNoGuess(board), "sukses generate WAJIB no-guess") // korektnes
                }
            }
            val s = ok.sorted()
            val dens = p.mines * 100.0 / (p.w * p.h)
            println(
                "%2dx%-2d    %5d  %5.1f  %2d  %2d   %5d  %5d  %5d".format(
                    p.w, p.h, p.mines, dens, p.n, ok.size,
                    pct(s, 0.50), pct(s, 0.95), s.lastOrNull() ?: -1,
                ),
            )
        }
        println("Catatan: ok<n atau p95 tinggi = density mendekati/di atas ceiling no-guess → knob kalibrasi.\n")
    }

    private fun pct(sorted: List<Long>, p: Double): Long =
        if (sorted.isEmpty()) -1 else sorted[minOf(sorted.size - 1, (p * sorted.size).toInt())]
}
