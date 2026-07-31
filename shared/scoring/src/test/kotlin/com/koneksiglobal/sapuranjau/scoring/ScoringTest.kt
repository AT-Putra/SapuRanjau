package com.koneksiglobal.sapuranjau.scoring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Guard wajib T-013 (ADR-0017): (a) output tak pernah < 0; (b) invariant §6.2 GDD —
// skill-tanpa-bayar tak pernah kalah dari bayar-reach-buruk. Mengunci formula sbg kode.
class ScoringTest {

    private val scoring = LevelScoring()
    private val par = LevelPar(parMoves = 20, parTimeMs = 40_000)
    private val params = ScoringParams(lifeCap = 5, baseScore = 1000)

    @Test fun perfectPlayNoLivesHitsBaseScore() {
        // 0 nyawa + tepat par -> efisiensi 1 di kedua faktor -> skor = baseScore (puncak).
        val play = LevelPlay(moves = par.parMoves, activeTimeMs = par.parTimeMs, livesUsed = 0)
        assertEquals(params.baseScore, scoring.levelScore(par, play, params))
    }

    @Test fun skillNoPayBeatsPayBadReach() {
        // invariant §6.2: skill-tanpa-bayar (par pas, 0 nyawa) vs bayar-reach-buruk
        // (pakai SEMUA nyawa cap, 10x par) -> skill menang telak.
        val skillNoPay = LevelPlay(moves = par.parMoves, activeTimeMs = par.parTimeMs, livesUsed = 0)
        val payBadReach = LevelPlay(
            moves = par.parMoves * 10,
            activeTimeMs = par.parTimeMs * 10,
            livesUsed = params.lifeCap,
        )
        val scoreSkill = scoring.levelScore(par, skillNoPay, params)
        val scorePay = scoring.levelScore(par, payBadReach, params)
        assertTrue(scoreSkill > scorePay, "skill($scoreSkill) harus > bayar-buruk($scorePay)")
    }

    @Test fun payingMoreNeverIncreasesScore() {
        // reach TETAP, hanya nyawa naik -> skor monoton turun (tak pernah naik krn bayar).
        val play = LevelPlay(moves = par.parMoves * 2, activeTimeMs = par.parTimeMs * 2, livesUsed = 0)
        var prev = scoring.levelScore(par, play, params)
        for (lives in 1..params.lifeCap + 2) {
            val next = scoring.levelScore(par, play.copy(livesUsed = lives), params)
            assertTrue(next <= prev, "livesUsed=$lives: skor($next) naik dari sebelumnya($prev)")
            prev = next
        }
    }

    @Test fun scoreNeverNegative() {
        val movesSamples = listOf(-5, -1, 0, 1, par.parMoves, par.parMoves * 100)
        val timeSamples = listOf(-5L, -1L, 0L, 1L, par.parTimeMs, par.parTimeMs * 100)
        val livesSamples = listOf(-5, 0, 1, params.lifeCap, params.lifeCap * 100)
        val paramSamples = listOf(
            params,
            params.copy(lifeCap = 0),
            params.copy(baseScore = 0),
            params.copy(baseScore = -50), // config rusak -> tetap tak boleh < 0
        )
        for (m in movesSamples) {
            for (t in timeSamples) {
                for (l in livesSamples) {
                    for (p in paramSamples) {
                        val score = scoring.levelScore(par, LevelPlay(m, t, l), p)
                        assertTrue(score >= 0, "moves=$m time=$t lives=$l params=$p -> score=$score < 0")
                    }
                }
            }
        }
    }

    @Test fun levelScoreIsDeterministic() {
        val play = LevelPlay(moves = 25, activeTimeMs = 50_000, livesUsed = 2)
        assertEquals(scoring.levelScore(par, play, params), scoring.levelScore(par, play, params))
    }
}
