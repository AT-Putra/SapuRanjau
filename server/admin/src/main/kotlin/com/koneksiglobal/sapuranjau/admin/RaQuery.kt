package com.koneksiglobal.sapuranjau.admin

import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper // Jackson 3 — mapper default Spring Boot 4

// Penerjemah query `ra-data-simple-rest` (ADR-0013): `range=[0,24]`, `sort=["id","ASC"]`, dan header
// balasan `Content-Range: <resource> 0-24/319`. Dipakai SEMUA resource admin (T-042 menambah banyak)
// supaya kontraknya ditulis sekali.
//
// `filter={}` sengaja belum diterjemahkan: tiap resource punya kolom filter berbeda, dan menebaknya
// sekarang berarti menulis mesin filter untuk layar yang belum ada. Resource yang butuh filter
// membacanya sebagai @RequestParam biasa saat layarnya dibuat.
@Component
class RaQuery(private val json: ObjectMapper) {

    data class Page(val offset: Int, val limit: Int) {
        fun contentRange(resource: String, jumlah: Int, total: Long): String =
            "$resource $offset-${offset + (jumlah - 1).coerceAtLeast(0)}/$total"
    }

    data class Sort(val column: String, val ascending: Boolean)

    fun page(range: String?): Page {
        val nilai = range?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.readValue(it, Array<Int>::class.java) }.getOrNull()
        } ?: return Page(0, DEFAULT_LIMIT)
        if (nilai.size != 2) return Page(0, DEFAULT_LIMIT)
        val awal = nilai[0].coerceAtLeast(0)
        val akhir = nilai[1]
        // Batas atas ditegakkan server: klien yang meminta `[0, 999999]` (atau salah hitung) tak boleh
        // bisa menarik seluruh tabel dalam satu query.
        return Page(awal, (akhir - awal + 1).coerceIn(1, MAX_LIMIT))
    }

    // Kolom sort di-whitelist pemanggil — nama kolom masuk ke SQL sebagai teks (tak bisa di-bind
    // sebagai parameter), jadi daftar putih ini yang berdiri di antara klien dan injeksi SQL.
    fun sort(sort: String?, allowed: Set<String>, default: String = "id"): Sort {
        val nilai = sort?.takeIf { it.isNotBlank() }?.let {
            runCatching { json.readValue(it, Array<String>::class.java) }.getOrNull()
        } ?: return Sort(default, true)
        if (nilai.size != 2) return Sort(default, true)
        val kolom = nilai[0].takeIf { it in allowed } ?: default
        return Sort(kolom, !nilai[1].equals("DESC", ignoreCase = true))
    }

    private companion object {
        const val DEFAULT_LIMIT = 25
        const val MAX_LIMIT = 200
    }
}
