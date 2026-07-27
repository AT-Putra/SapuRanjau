package com.koneksiglobal.sapuranjau.game

import com.koneksiglobal.sapuranjau.engine.CellIndex

// Log langkah = satu-satunya state yang disimpan (ADR-0036): revealed/flag direkonstruksi dengan
// replay atas board f(config, seed, klik-pertama). Format sama persis di `board.moves` (berjalan)
// dan `level_score.moves` (final, bahan re-sim anti-cheat ARCH §9).

// USE_LIFE = pemakaian nyawa setelah kena bom (ADR-0037), dicatat di log yang sama supaya replay
// merekonstruksi "hidup lagi + bom terflag" tanpa kolom/tabel tambahan. Ordinal 3 = slot terakhir
// yang muat di 2 bit codec; log lama (aksi 0..2) tetap terbaca apa adanya.
enum class MoveAction { REVEAL, FLAG, CHORD, USE_LIFE }

data class Move(val action: MoveAction, val cell: CellIndex)

// 2 byte per langkah: b0 = aksi(2 bit atas) | x(6 bit bawah), b1 = y. Plafon 64×256 sel — jauh di
// atas grid terbesar yang mungkin (expert klasik 16×30, ADR-0031). Compact sesuai `08` §2.6.
// ponytail: 2 bit aksi kini PENUH (4/4). Aksi ke-5 = ganti format (mis. 3 byte), bukan tambal.
object MoveCodec {
    const val MAX_X = 63
    const val MAX_Y = 255

    fun encode(moves: List<Move>): ByteArray {
        val out = ByteArray(moves.size * 2)
        moves.forEachIndexed { i, m ->
            require(m.cell.x in 0..MAX_X && m.cell.y in 0..MAX_Y) { "cell ${m.cell} di luar jangkauan codec" }
            out[i * 2] = ((m.action.ordinal shl 6) or m.cell.x).toByte()
            out[i * 2 + 1] = m.cell.y.toByte()
        }
        return out
    }

    fun decode(bytes: ByteArray): List<Move> {
        require(bytes.size % 2 == 0) { "log langkah rusak: ${bytes.size} byte (harus genap)" }
        return (bytes.indices step 2).map { i ->
            val b0 = bytes[i].toInt() and 0xFF
            val action = MoveAction.entries.getOrElse(b0 shr 6) { error("log langkah rusak: aksi ${b0 shr 6} di offset $i") }
            Move(action, CellIndex(b0 and 0x3F, bytes[i + 1].toInt() and 0xFF))
        }
    }
}
