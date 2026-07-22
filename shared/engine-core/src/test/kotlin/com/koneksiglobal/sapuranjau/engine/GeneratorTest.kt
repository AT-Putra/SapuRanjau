package com.koneksiglobal.sapuranjau.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Self-check T-011: generator no-guess + solver + par. ADR-0031/0017/0006.
class GeneratorTest {

    private val engine = MinesweeperEngine()

    @Test fun generateIsFirstClickSafeStructural() {
        val fc = CellIndex(4, 4)
        val b = engine.generate(LevelConfig(9, 9, 10), seed = 1L, firstClick = fc)
        // firstClick + seluruh 3x3-nya bebas bom (ADR-0031) → firstClick = sel-0.
        assertFalse(fc in b.mines)
        for (n in b.neighbors(fc)) assertFalse(n in b.mines, "tetangga $n harusnya aman")
        assertEquals(0, b.adjacentMines(fc))
        val r = engine.reveal(b, fc)
        assertTrue(r is RevealResult.Revealed || r is RevealResult.LevelCleared)
    }

    @Test fun generatedBoardsAreAlwaysNoGuess() {
        // batch seed → tiap board hasil generate WAJIB solvable no-guess (jantung anti-cheat).
        for (seed in 1..40L) {
            val b = engine.generate(LevelConfig(9, 9, 10), seed, CellIndex(4, 4))
            assertTrue(engine.isSolvableNoGuess(b), "board seed=$seed harusnya no-guess")
            assertEquals(10, b.mines.size)
        }
    }

    @Test fun generateIsDeterministic() {
        val a = engine.generate(LevelConfig(16, 16, 40), 7L, CellIndex(8, 8))
        val b = engine.generate(LevelConfig(16, 16, 40), 7L, CellIndex(8, 8))
        assertEquals(a.mines, b.mines) // f(config, seed, firstClick) — provably-fair (ADR-0003)
    }

    @Test fun generateDoesNotMutateReturnedBoard() {
        val b = engine.generate(LevelConfig(9, 9, 10), 3L, CellIndex(4, 4))
        assertTrue(b.revealed.isEmpty() && b.flags.isEmpty()) // board bersih, bukan state solver
    }

    @Test fun computeParIsPositiveAndBounded() {
        val b = engine.generate(LevelConfig(9, 9, 10), 5L, CellIndex(4, 4))
        val par = engine.computeParMoves(b)
        assertTrue(par in 1..b.safeCellCount, "par=$par di luar [1, ${b.safeCellCount}]")
    }

    @Test fun computeParIsDeterministic() {
        val b = engine.generate(LevelConfig(9, 9, 10), 5L, CellIndex(4, 4))
        assertEquals(engine.computeParMoves(b), engine.computeParMoves(b))
    }

    @Test fun singleClickBoardHasParOne() {
        // 3x3 satu bom di sudut → satu sel-0 membuka semua → par = 1 klik.
        val b = Board(LevelConfig(3, 3, 1), 0L, setOf(CellIndex(0, 0)))
        assertEquals(1, engine.computeParMoves(b))
    }

    @Test fun solverRejectsGuessyBoard() {
        // 4x1, bom di (2,0): (3,0) terisolasi — tak terdeduksi single-point/subset → butuh nebak.
        val b = Board(LevelConfig(4, 1, 1), 0L, setOf(CellIndex(2, 0)))
        assertFalse(engine.isSolvableNoGuess(b))
    }

    @Test fun computeParMovesThrowsOnGuessyBoard() {
        // sama seperti solverRejectsGuessyBoard: (3,0) terisolasi → deduksi buntu, bukan par palsu.
        val b = Board(LevelConfig(4, 1, 1), 0L, setOf(CellIndex(2, 0)))
        assertFailsWith<IllegalStateException> { engine.computeParMoves(b) }
    }

    @Test fun generateThrowsOnImpossibleDensity() {
        // mineCount melebihi kapasitas (grid − zona aman 3x3) → bug kelayakan config (ADR-0031).
        assertFailsWith<IllegalArgumentException> {
            engine.generate(LevelConfig(3, 3, 9), 0L, CellIndex(1, 1))
        }
    }
}
