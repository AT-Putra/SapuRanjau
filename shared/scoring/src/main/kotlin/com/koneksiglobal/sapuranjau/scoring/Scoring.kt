package com.koneksiglobal.sapuranjau.scoring

import kotlin.math.roundToInt

// scoring — kontrak RATIFIED (05_CONTRACTS §2, ADR-0017/0018). Deterministik, tanpa I/O.
// levelScore = round( baseScore × (w_m·f_moves + w_t·f_time) × max(0, 1 − livesUsed/lifeCap) )
// f_moves = clamp(parMoves/moves, 0, 1); f_time = clamp(parTimeMs/activeTimeMs, 0, 1)
// Semua faktor ∈ [0,1] → hasil selalu >= 0 (invariant §6.2 GDD: puncak skor tanpa bayar).

// Par dihitung engine-core PER-PAPAN (ADR-0017); scoring murni atas angka.
data class LevelPar(
    val parMoves: Int,
    val parTimeMs: Long,
)

data class LevelPlay(
    val moves: Int, // ADR-0018: 1 reveal = 1; flag/unflag tak dihitung; chord = 1
    val activeTimeMs: Long,
    val livesUsed: Int,
)

// Konstanta tunable (ADR-0017) — default di sini BUKAN final; sumber sebenarnya admin-config.
data class ScoringParams(
    val moveWeight: Double = 0.6, // w_m
    val timeWeight: Double = 0.4, // w_t (w_m + w_t = 1)
    val lifeCap: Int, // capNyawa: nyawa yang menolkan skor level
    val baseScore: Int, // Base(L): field admin per LevelConfig
)

interface Scoring {
    fun levelScore(par: LevelPar, play: LevelPlay, params: ScoringParams): Int // >= 0
}

class LevelScoring : Scoring {
    override fun levelScore(par: LevelPar, play: LevelPlay, params: ScoringParams): Int {
        val fMoves = ratio(par.parMoves.toDouble(), play.moves.toDouble())
        val fTime = ratio(par.parTimeMs.toDouble(), play.activeTimeMs.toDouble())
        val efficiency = params.moveWeight * fMoves + params.timeWeight * fTime
        val livesPenalty = penalty(play.livesUsed, params.lifeCap)
        return (params.baseScore * efficiency * livesPenalty).roundToInt().coerceAtLeast(0)
    }

    // clamp(par/actual, 0, 1); actual tak positif -> 0 (bukan lempar NaN/Infinity ke roundToInt).
    private fun ratio(par: Double, actual: Double): Double =
        if (actual <= 0.0) 0.0 else (par / actual).coerceIn(0.0, 1.0)

    // max(0, 1 - livesUsed/lifeCap); lifeCap tak positif -> 0 (config rusak = tak ada kredit efisiensi).
    private fun penalty(livesUsed: Int, lifeCap: Int): Double =
        if (lifeCap <= 0) 0.0 else (1.0 - livesUsed.toDouble() / lifeCap).coerceIn(0.0, 1.0)
}
