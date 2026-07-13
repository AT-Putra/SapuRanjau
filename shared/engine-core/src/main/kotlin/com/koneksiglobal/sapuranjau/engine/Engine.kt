package com.koneksiglobal.sapuranjau.engine

// engine-core — kontrak RATIFIED (05_CONTRACTS §1, ADR-0032). Deterministik, tanpa I/O.
// T-010 mengisi: model Board + reveal/flood-fill. generate no-guess = T-011,
// chord + toggleFlag = T-012, solver (isSolvableNoGuess/computeParMoves) = T-011.

data class LevelConfig(
    val gridWidth: Int,
    val gridHeight: Int,
    val mineCount: Int,
)

data class CellIndex(val x: Int, val y: Int)

// Board sepenuhnya ditentukan oleh (config, seed, firstClick). Peta bom hidup di sini,
// TAPI objek ini hanya utuh di server — client tak pernah menerima peta bom (05 §6).
// Impl detail (bebas per ADR-0032): mines immutable; revealed adalah state run yang di-mutasi
// oleh reveal(). Ctor internal → hanya generate (T-011) / test yang merakit Board.
class Board internal constructor(
    val config: LevelConfig,
    val seed: Long,
    internal val mines: Set<CellIndex>,
) {
    internal val revealed: MutableSet<CellIndex> = HashSet()

    internal val safeCellCount: Int = config.gridWidth * config.gridHeight - mines.size
}

data class RevealedCell(val index: CellIndex, val adjacentMines: Int) // 0..8

sealed interface RevealResult {
    data class Revealed(val cells: List<RevealedCell>) : RevealResult
    data class HitMine(val at: CellIndex) : RevealResult
    data class LevelCleared(val cells: List<RevealedCell>) : RevealResult
}

interface GameEngine {
    fun generate(config: LevelConfig, seed: Long, firstClick: CellIndex): Board
    fun reveal(board: Board, at: CellIndex): RevealResult
    fun toggleFlag(board: Board, at: CellIndex): Board
    fun chord(board: Board, at: CellIndex): RevealResult
    fun isSolvableNoGuess(board: Board): Boolean
    fun computeParMoves(board: Board): Int
}

class MinesweeperEngine : GameEngine {

    override fun reveal(board: Board, at: CellIndex): RevealResult {
        require(board.inBounds(at)) { "cell $at di luar grid ${board.config.gridWidth}x${board.config.gridHeight}" }
        if (at in board.mines) return RevealResult.HitMine(at)

        // Flood-fill iteratif: buka `at`, dan bila 0 tetangga-bom, sebarkan ke tetangganya.
        // Set hasil deterministik apa pun urutan traversal; list-nya diurutkan (y,x) agar
        // client & server (ADR-0003) menghasilkan urutan identik.
        val opened = ArrayList<RevealedCell>()
        val stack = ArrayDeque<CellIndex>()
        stack.addLast(at)
        while (stack.isNotEmpty()) {
            val c = stack.removeLast()
            if (!board.revealed.add(c)) continue // sudah terbuka
            val adj = board.adjacentMines(c)
            opened += RevealedCell(c, adj)
            if (adj == 0) {
                for (n in board.neighbors(c)) {
                    if (n !in board.revealed && n !in board.mines) stack.addLast(n)
                }
            }
        }
        opened.sortWith(compareBy({ it.index.y }, { it.index.x }))

        return if (board.revealed.size == board.safeCellCount) {
            RevealResult.LevelCleared(opened)
        } else {
            RevealResult.Revealed(opened)
        }
    }

    // ponytail: stub jalur-task berikutnya, bukan lupa. Impl per task yang disebut.
    override fun generate(config: LevelConfig, seed: Long, firstClick: CellIndex): Board =
        TODO("T-011: generator no-guess (ADR-0031)")

    override fun toggleFlag(board: Board, at: CellIndex): Board =
        TODO("T-012: state flag (ADR-0019)")

    override fun chord(board: Board, at: CellIndex): RevealResult =
        TODO("T-012: chord (ADR-0019)")

    override fun isSolvableNoGuess(board: Board): Boolean =
        TODO("T-011: solver no-guess (ADR-0031)")

    override fun computeParMoves(board: Board): Int =
        TODO("T-011: par = jalur bersih solver (ADR-0017)")
}

private fun Board.inBounds(c: CellIndex): Boolean =
    c.x in 0 until config.gridWidth && c.y in 0 until config.gridHeight

private fun Board.neighbors(c: CellIndex): List<CellIndex> {
    val out = ArrayList<CellIndex>(8)
    for (dy in -1..1) for (dx in -1..1) {
        if (dx == 0 && dy == 0) continue
        val n = CellIndex(c.x + dx, c.y + dy)
        if (inBounds(n)) out += n
    }
    return out
}

private fun Board.adjacentMines(c: CellIndex): Int =
    neighbors(c).count { it in mines }
