package com.koneksiglobal.sapuranjau.game

import com.koneksiglobal.sapuranjau.engine.CellIndex
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// Codec = format yang tersimpan permanen di `board.moves` & `level_score.moves` (ADR-0036). Kalau
// round-trip meleset, replay membangun papan yang salah dan skor lama tak bisa di-re-sim lagi.
class MoveCodecTest {

    @Test
    fun `round-trip mempertahankan aksi dan urutan`() {
        val moves = listOf(
            Move(MoveAction.REVEAL, CellIndex(0, 0)),
            Move(MoveAction.FLAG, CellIndex(MoveCodec.MAX_X, MoveCodec.MAX_Y)),
            Move(MoveAction.CHORD, CellIndex(4, 2)),
            Move(MoveAction.REVEAL, CellIndex(29, 15)), // expert klasik 16×30
        )
        val bytes = MoveCodec.encode(moves)
        assertEquals(moves.size * 2, bytes.size, "2 byte per langkah (compact, 08 §2.6)")
        assertEquals(moves, MoveCodec.decode(bytes))
    }

    @Test
    fun `log kosong = board baru`() {
        assertEquals(emptyList(), MoveCodec.decode(ByteArray(0)))
        assertEquals(0, MoveCodec.encode(emptyList()).size)
    }

    @Test
    fun `sel di luar jangkauan codec ditolak, bukan disimpan diam-diam`() {
        assertFailsWith<IllegalArgumentException> {
            MoveCodec.encode(listOf(Move(MoveAction.REVEAL, CellIndex(MoveCodec.MAX_X + 1, 0))))
        }
        assertFailsWith<IllegalArgumentException> { MoveCodec.decode(ByteArray(3)) }
    }
}
