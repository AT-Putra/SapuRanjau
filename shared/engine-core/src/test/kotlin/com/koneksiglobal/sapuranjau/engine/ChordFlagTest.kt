package com.koneksiglobal.sapuranjau.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Self-check T-012: toggleFlag + chord (ADR-0019). Board dirakit langsung (generate = T-011).
// Test sepaket → akses `flags`/`revealed` internal.
class ChordFlagTest {

    private val engine = MinesweeperEngine()

    // 3x3, satu bom di (0,0).
    private fun board() = Board(LevelConfig(3, 3, 1), seed = 42L, mines = setOf(CellIndex(0, 0)))

    @Test fun toggleFlagTogglesInPlace() {
        val b = board()
        assertEquals(b, engine.toggleFlag(b, CellIndex(2, 2))) // kembalikan instance sama
        assertTrue(CellIndex(2, 2) in b.flags)
        engine.toggleFlag(b, CellIndex(2, 2))
        assertFalse(CellIndex(2, 2) in b.flags) // tap kedua → lepas
    }

    @Test fun cannotFlagRevealedCell() {
        val b = board()
        engine.reveal(b, CellIndex(1, 0))
        engine.toggleFlag(b, CellIndex(1, 0))
        assertFalse(CellIndex(1, 0) in b.flags) // sel terbuka tak bisa ditandai
    }

    @Test fun chordNoOpWhenUnsatisfied() {
        val b = board()
        engine.reveal(b, CellIndex(1, 1)) // angka "1", tanpa flag
        val r = assertIs<RevealResult.Revealed>(engine.chord(b, CellIndex(1, 1)))
        assertTrue(r.cells.isEmpty()) // flag(0) != angka(1) → no-op
    }

    @Test fun chordNoOpOnUnrevealedCell() {
        val b = board()
        val r = assertIs<RevealResult.Revealed>(engine.chord(b, CellIndex(2, 2)))
        assertTrue(r.cells.isEmpty())
    }

    @Test fun chordOpensNeighborsWhenSatisfied() {
        val b = board()
        engine.reveal(b, CellIndex(1, 0)) // "1"
        engine.toggleFlag(b, CellIndex(0, 0)) // flag bom yang benar
        val r = assertIs<RevealResult.LevelCleared>(engine.chord(b, CellIndex(1, 0)))
        assertTrue(r.cells.none { it.index == CellIndex(0, 0) }) // bom tak pernah terbuka
        assertEquals(b.safeCellCount, b.revealed.size) // semua sel aman terbuka
    }

    @Test fun chordExplodesOnWrongFlag() {
        val b = board()
        engine.reveal(b, CellIndex(1, 1)) // "1"
        engine.toggleFlag(b, CellIndex(1, 0)) // flag SALAH (sel aman), bom (0,0) tak diflag
        assertEquals(RevealResult.HitMine(CellIndex(0, 0)), engine.chord(b, CellIndex(1, 1)))
    }

    @Test fun revealCascadeDoesNotSweepFlaggedCell() {
        // 5x1, bom di (4,0): (0,0)-(2,0) region "0" tersambung, (3,0) angka "1" (bukan "0").
        val b = Board(LevelConfig(5, 1, 1), seed = 0L, mines = setOf(CellIndex(4, 0)))
        engine.toggleFlag(b, CellIndex(3, 0)) // flag SALAH (sel aman), sebelum cascade
        engine.reveal(b, CellIndex(0, 0)) // cascade dari region "0" tersambung
        assertTrue(CellIndex(3, 0) in b.flags) // flag tetap — TAK tersapu cascade
        assertFalse(CellIndex(3, 0) in b.revealed) // sel tetap tertutup
    }

    @Test fun revealOnFlaggedCellIsNoOp() {
        // reveal LANGSUNG di sel berflag = no-op (flag = "tidak membuka"), tak boleh buka +
        // biarkan flag menempel (state flagged+revealed) yang meracuni hitung chord tetangga.
        val b = Board(LevelConfig(5, 1, 1), seed = 0L, mines = setOf(CellIndex(4, 0)))
        engine.toggleFlag(b, CellIndex(3, 0)) // flag sel aman
        val r = assertIs<RevealResult.Revealed>(engine.reveal(b, CellIndex(3, 0)))
        assertTrue(r.cells.isEmpty()) // tak ada yang terbuka
        assertFalse(CellIndex(3, 0) in b.revealed) // tetap tertutup
        assertTrue(CellIndex(3, 0) in b.flags) // flag utuh
    }

    @Test fun chordDeterministic() {
        fun run(): RevealResult {
            val b = board()
            engine.reveal(b, CellIndex(1, 0))
            engine.toggleFlag(b, CellIndex(0, 0))
            return engine.chord(b, CellIndex(1, 0))
        }
        assertEquals(run(), run())
    }
}
