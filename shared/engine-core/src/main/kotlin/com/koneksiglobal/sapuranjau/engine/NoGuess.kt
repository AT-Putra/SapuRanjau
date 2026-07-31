package com.koneksiglobal.sapuranjau.engine

import java.util.Random

// T-011 — generator no-guess + solver + par chord-greedy. ADR-0031/0017/0018/0019/0006.
// Satu mesin deduksi (single-point + subset/counting) dipakai `isSolvableNoGuess`,
// `computeParMoves`, dan validasi `generate`. Seluruhnya deterministik f(config, seed,
// firstClick) → provably-fair & re-sim utuh (ADR-0003). java.util.Random = LCG terdokumentasi
// → identik di JVM & ART.

// Katup pengaman & knob kalibrasi (ADR-0031; nilai final via benchmark T-011).
// ponytail: batas percobaan reject-sampling — kena = LevelConfig density terlalu tinggi (bug
// config), bukan jalur normal. Perketat density lewat benchmark, jangan diam-diam gagal.
internal const val DEFAULT_MAX_ATTEMPTS = 500

// Salinan kerja bersih (mines & firstClick sama; revealed/flags kosong). Solver/par TAK boleh
// mem-mutasi board input pemanggil.
internal fun Board.freshCopy(): Board = Board(config, seed, mines, firstClick)

// Titik mulai kanonik: klik-pertama bila valid (sel-0), else sel-0 terkecil (y,x). Membuka
// region tanpa nebak. Board hasil generate selalu punya ≥1 (firstClick = sel-0 by construction).
internal fun Board.canonicalStart(): CellIndex? {
    firstClick?.let { if (it !in mines && adjacentMines(it) == 0) return it }
    for (y in 0 until config.gridHeight) {
        for (x in 0 until config.gridWidth) {
            val c = CellIndex(x, y)
            if (c !in mines && adjacentMines(c) == 0) return c
        }
    }
    return null
}

private class Constraint(val hidden: Set<CellIndex>, val need: Int)

// Satu ronde deduksi dari state terlihat (angka terbuka + flag). TAK mengintip `mines` untuk
// memutuskan aman/bom (hanya untuk angka sel terbuka, yang memang terlihat pemain). Kembalikan
// (sel-aman, sel-bom) yang terbukti oleh aturan — belum tentu masih tersembunyi.
private fun Board.deduce(): Pair<Set<CellIndex>, Set<CellIndex>> {
    val cons = ArrayList<Constraint>()
    for (c in revealed) {
        val num = adjacentMines(c)
        if (num == 0) continue
        val nbrs = neighbors(c)
        val hidden = nbrs.filterTo(HashSet()) { it !in revealed && it !in flags }
        if (hidden.isEmpty()) continue
        cons.add(Constraint(hidden, num - nbrs.count { it in flags }))
    }
    val safe = LinkedHashSet<CellIndex>()
    val mine = LinkedHashSet<CellIndex>()
    // single-point / counting
    for (con in cons) {
        when {
            con.need == 0 -> safe += con.hidden
            con.need == con.hidden.size -> mine += con.hidden
        }
    }
    // subset/counting: a.hidden ⊂ b.hidden → selisih terdeduksi. ponytail: O(C²) atas frontier;
    // frontier-segmentation (ADR-0031) hanya bila benchmark tunjuk ini hot di grid besar.
    for (a in cons) {
        for (b in cons) {
            if (a === b || a.hidden.size >= b.hidden.size || !b.hidden.containsAll(a.hidden)) continue
            val diff = b.hidden - a.hidden
            when (b.need - a.need) {
                0 -> safe += diff
                diff.size -> mine += diff
            }
        }
    }
    return safe to mine
}

// Solver boolean: buka start, deduksi sampai fixpoint. true bila semua sel aman terbuka tanpa
// nebak. MEM-MUTASI board (pakai di salinan kerja).
internal fun GameEngine.solveClears(board: Board): Boolean {
    val start = board.canonicalStart() ?: return false
    reveal(board, start)
    while (true) {
        val (safe, mine) = board.deduce()
        val newMine = mine.filter { it !in board.flags }
        val newSafe = safe.filter { it !in board.revealed }
        if (newMine.isEmpty() && newSafe.isEmpty()) break
        board.flags.addAll(newMine)
        for (s in newSafe.sortedWith(compareBy({ it.y }, { it.x }))) {
            if (s !in board.revealed) reveal(board, s)
        }
    }
    return board.revealed.size == board.safeCellCount
}

// Par = panjang jalur bersih solver, chord-greedy deterministik (ADR-0017/0018/0019). 1 reveal=1,
// chord=1, flag=0. Bukan minimum terbukti — greedy tetap tajam & sama utk semua papan. MEM-MUTASI.
internal fun GameEngine.parPath(board: Board): Int {
    // Tanpa canonical start = tak bisa buka region tanpa nebak → bukan no-guess, par tak terdefinisi.
    // Gagal eksplisit (ADR-0031), bukan diam-diam return 0 — konsisten dg check() buntu di bawah.
    val start = board.canonicalStart()
        ?: error("computeParMoves: board tanpa canonical start (bukan no-guess-solvable) — par tak terdefinisi")
    reveal(board, start)
    var moves = 1 // klik pertama
    while (board.revealed.size < board.safeCellCount) {
        // 1. flag semua bom terdeduksi (gratis-langkah)
        board.flags.addAll(board.deduce().second)
        // 2. chord-greedy: chord tiap angka terpuaskan yang masih punya tetangga tersembunyi
        var progressed = false
        for (c in board.numberedRevealed()) {
            val nbrs = board.neighbors(c)
            if (nbrs.count { it in board.flags } != board.adjacentMines(c)) continue
            if (nbrs.none { it !in board.revealed && it !in board.flags }) continue
            val before = board.revealed.size
            chord(board, c)
            if (board.revealed.size > before) {
                moves++
                progressed = true
            }
        }
        if (progressed) continue
        // 3. tak ada chord → buka satu sel aman terdeduksi langsung (terkecil (y,x))
        val target = board.deduce().first
            .filter { it !in board.revealed }
            .minWithOrNull(compareBy({ it.y }, { it.x }))
        if (target != null) {
            reveal(board, target)
            moves++
            continue
        }
        break // buntu → deduksi tak bisa lanjut, dicek di bawah
    }
    // jangan diam-diam gagal (ADR-0031, konsisten dg generateNoGuess): par HANYA valid utk board
    // no-guess. Board buntu (belum tuntas) → bug pemanggilan, bukan par salah yang di-ship diam-diam.
    check(board.revealed.size == board.safeCellCount) {
        "computeParMoves dipanggil pada board yang bukan no-guess-solvable (deduksi buntu setelah $moves langkah)"
    }
    return moves
}

private fun Board.numberedRevealed(): List<CellIndex> =
    revealed.filter { adjacentMines(it) > 0 }.sortedWith(compareBy({ it.y }, { it.x }))

// Generator no-guess: penempatan deterministik → solve-check → local-repair bounded K.
// first-click-safe klasik: firstClick + tetangganya bebas bom (zona aman) → firstClick = sel-0.
internal fun GameEngine.generateNoGuess(
    config: LevelConfig,
    seed: Long,
    firstClick: CellIndex,
    k: Int = DEFAULT_MAX_ATTEMPTS,
): Board {
    val all = (0 until config.gridHeight).flatMap { y -> (0 until config.gridWidth).map { x -> CellIndex(x, y) } }
    require(firstClick in all) { "firstClick $firstClick di luar grid ${config.gridWidth}x${config.gridHeight}" }
    val forbidden = HashSet<CellIndex>().apply {
        add(firstClick)
        addAll(firstClick.box())
    }
    val allowed = all.filter { it !in forbidden } // sudah urut (y,x)
    require(config.mineCount in 0..allowed.size) {
        "mineCount ${config.mineCount} > kapasitas ${allowed.size} (grid − zona-aman klik-pertama) → density config mustahil (ADR-0031)"
    }

    // Reject-sampling deterministik: undi ULANG seluruh penempatan tiap percobaan dari PRNG yang
    // maju → sampel independen konvergen andal di density layak. (Naive local-repair = jalan-acak
    // terkorelasi → TAK konvergen bahkan di density rendah — temuan sweep T-011, amandemen ADR-0031.)
    val rnd = Random(seed)
    var attempts = 1
    while (true) {
        val mines = pickMines(allowed, config.mineCount, rnd)
        if (solveClears(Board(config, seed, mines, firstClick))) {
            return Board(config, seed, mines, firstClick) // board bersih (revealed kosong)
        }
        if (++attempts > k) {
            val dens = "%.1f".format(config.mineCount * 100.0 / (config.gridWidth * config.gridHeight))
            error(
                "no-guess gagal dalam $k percobaan (grid ${config.gridWidth}x${config.gridHeight}, " +
                    "mines ${config.mineCount}, density $dens%) → LevelConfig density terlalu tinggi: " +
                    "bug kelayakan config (ADR-0031), BUKAN board guessy yang di-ship.",
            )
        }
    }
}

// 3x3 (tanpa pusat) di sekitar sel — utk zona aman klik-pertama.
private fun CellIndex.box(): List<CellIndex> =
    (-1..1).flatMap { dy -> (-1..1).mapNotNull { dx -> if (dx == 0 && dy == 0) null else CellIndex(x + dx, y + dy) } }

// Fisher-Yates parsial deterministik → mineCount sel pertama dari `allowed` teracak seed.
private fun pickMines(allowed: List<CellIndex>, count: Int, rnd: Random): Set<CellIndex> {
    val a = allowed.toMutableList()
    for (i in 0 until count) {
        val j = i + rnd.nextInt(a.size - i)
        a[i] = a[j].also { a[j] = a[i] }
    }
    return a.subList(0, count).toHashSet()
}
