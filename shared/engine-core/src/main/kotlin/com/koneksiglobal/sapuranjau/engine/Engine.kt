package com.koneksiglobal.sapuranjau.engine

// engine-core — kontrak RATIFIED (05_CONTRACTS §1, ADR-0032). Deterministik, tanpa I/O.
// T-010: model Board + reveal/flood-fill. T-012: toggleFlag + chord (ADR-0019).
// generate no-guess & solver (isSolvableNoGuess/computeParMoves) = T-011.

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
    // Klik-pertama tempat board diwujudkan (ADR-0031) = titik mulai kanonik solver/par.
    // null utk board rakitan-test → fallback ke sel-0 terkecil (NoGuess.kt).
    internal val firstClick: CellIndex? = null,
) {
    internal val revealed: MutableSet<CellIndex> = HashSet()
    internal val flags: MutableSet<CellIndex> = HashSet()

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
        // Flag = "tidak membuka" (01_GDD.md): sel berflag kebal reveal langsung, sama seperti kebal
        // cascade (floodFrom) & target chord. Cegah state flagged+revealed yang meracuni hitung
        // flag tetangga di chord(). Buka-paksa harus lepas flag dulu (toggleFlag), bukan lewat reveal.
        if (at in board.flags) return RevealResult.Revealed(emptyList())
        if (at in board.mines) return RevealResult.HitMine(at)

        val opened = ArrayList<RevealedCell>()
        board.floodFrom(at, opened)
        return board.result(opened)
    }

    override fun toggleFlag(board: Board, at: CellIndex): Board {
        require(board.inBounds(at)) { "cell $at di luar grid ${board.config.gridWidth}x${board.config.gridHeight}" }
        // Flag gratis-langkah (ADR-0018), toggle in-place. Sel terbuka tak bisa ditandai.
        if (at in board.revealed) return board
        if (!board.flags.add(at)) board.flags.remove(at) // sudah ada → lepas
        return board
    }

    // Chord (ADR-0019): tap angka terbuka yang jumlah flag tetangganya = angkanya →
    // buka semua tetangga tak-berflag. Flag salah (bom tak diflag) → meledak. = 1 langkah.
    override fun chord(board: Board, at: CellIndex): RevealResult {
        require(board.inBounds(at)) { "cell $at di luar grid ${board.config.gridWidth}x${board.config.gridHeight}" }
        if (at !in board.revealed) return RevealResult.Revealed(emptyList()) // chord hanya di sel terbuka
        val nbrs = board.neighbors(at)
        if (nbrs.count { it in board.flags } != board.adjacentMines(at)) {
            return RevealResult.Revealed(emptyList()) // belum tuntas → no-op
        }
        val targets = nbrs.filter { it !in board.flags && it !in board.revealed }
        // nbrs sudah urut (y,x) → bom pertama deterministik (ADR-0003).
        targets.firstOrNull { it in board.mines }?.let { return RevealResult.HitMine(it) }
        val opened = ArrayList<RevealedCell>()
        for (t in targets) board.floodFrom(t, opened) // set revealed dedup tumpang-tindih cascade
        return board.result(opened)
    }

    // T-011 — impl di NoGuess.kt (solver+generator+par berbagi satu mesin deduksi).
    override fun generate(config: LevelConfig, seed: Long, firstClick: CellIndex): Board =
        generateNoGuess(config, seed, firstClick)

    override fun isSolvableNoGuess(board: Board): Boolean =
        solveClears(board.freshCopy())

    override fun computeParMoves(board: Board): Int =
        parPath(board.freshCopy())
}

// Flood-fill dari sel aman `from` (bukan bom): buka `from`, dan bila 0 tetangga-bom sebarkan.
// Set `revealed` deterministik apa pun urutan traversal (ADR-0003); pengurutan list di result().
// Dipakai reveal() & chord() → satu implementasi flood-fill.
// Sel berflag TAK pernah tersapu cascade (flag = "tidak membuka", 01_GDD.md) — konsisten dg
// chord() yang juga mengecualikan tetangga berflag dari targetnya.
private fun Board.floodFrom(from: CellIndex, opened: MutableList<RevealedCell>) {
    val stack = ArrayDeque<CellIndex>()
    stack.addLast(from)
    while (stack.isNotEmpty()) {
        val c = stack.removeLast()
        if (!revealed.add(c)) continue // sudah terbuka
        val adj = adjacentMines(c)
        opened += RevealedCell(c, adj)
        if (adj == 0) for (n in neighbors(c)) {
            if (n !in revealed && n !in mines && n !in flags) stack.addLast(n)
        }
    }
}

// List diurut (y,x) agar identik antar client/server (ADR-0003); Cleared saat semua sel aman terbuka.
private fun Board.result(opened: MutableList<RevealedCell>): RevealResult {
    opened.sortWith(compareBy({ it.index.y }, { it.index.x }))
    return if (revealed.size == safeCellCount) RevealResult.LevelCleared(opened)
    else RevealResult.Revealed(opened)
}

// internal (bukan private) → dipakai ulang NoGuess.kt (T-011).
internal fun Board.inBounds(c: CellIndex): Boolean =
    c.x in 0 until config.gridWidth && c.y in 0 until config.gridHeight

internal fun Board.neighbors(c: CellIndex): List<CellIndex> {
    val out = ArrayList<CellIndex>(8)
    for (dy in -1..1) for (dx in -1..1) {
        if (dx == 0 && dy == 0) continue
        val n = CellIndex(c.x + dx, c.y + dy)
        if (inBounds(n)) out += n
    }
    return out
}

internal fun Board.adjacentMines(c: CellIndex): Int =
    neighbors(c).count { it in mines }
