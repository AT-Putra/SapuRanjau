package com.koneksiglobal.sapuranjau.admin

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate

// Laporan penjualan & pemain (T-042 lanjutan). Ini alasan panel dipilih berbentuk SPA (ADR-0013):
// laporan yang dipresentasikan. Keduanya **baca saja** — tak ada satu pun tombol tulis di sini.
//
// UANG: `purchase.amount` ADA di skema (`08` §2.7) tapi **tak pernah diisi** — `BillingService`
// menyisipkan baris tanpa nilai dan tak ada yang meng-update-nya, karena harga sebenarnya hidup di
// Play Console (ADR-0022: harga BUKAN admin-config) dan Play Developer API tak mengembalikannya
// untuk produk sekali-beli. Jadi laporan ini menghitung TRANSAKSI & NYAWA, bukan rupiah, dan
// mengatakannya apa adanya di layar. Mengalikan jumlah transaksi dengan harga daftar akan
// menghasilkan angka yang tak bisa dipertanggungjawabkan siapa pun begitu ia masuk slide —
// promo, pajak, potongan Google, dan harga per-negara semuanya tak ada di sini.
// Kalau kelak dibutuhkan: (a) klien mengirim harga yang DITAMPILKAN saat beli (T-033) → isi
// `amount`, atau (b) rekonsiliasi dari laporan keuangan Play Console. Keduanya keputusan sendiri.
@RestController
class AdminReportController(
    private val jdbc: JdbcClient,
    private val ra: RaQuery,
) {

    // ── Laporan penjualan ───────────────────────────────────────────────────────────────────────

    data class SaleDto(
        val id: String,
        val createdAt: Instant,
        val userId: String,
        val displayName: String,
        val productId: String,
        val livesGranted: Int,
        val status: String,
        val verifiedAt: Instant?,
        val voidedAt: Instant?,
        val voidReason: String?,
    )

    data class SalesSummary(
        val transaksi: Long,
        val livesGranted: Long,
        val voided: Long,
        val perProduk: List<Map<String, Any?>>,
        val harian: List<Map<String, Any?>>, // deret untuk grafik: {tanggal, transaksi, lives}
        val uangTersedia: Boolean, // selalu false hari ini — lihat catatan kelas
        val catatanUang: String,
    )

    @GetMapping("/sales")
    fun sales(
        principal: AdminPrincipal,
        @RequestParam(required = false) range: String?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) filter: String?,
    ): ResponseEntity<List<SaleDto>> {
        principal.require(AdminRole.ADMIN, AdminRole.FINANCE) // laporan uang = peran uang (ARCH §10)
        val h = ra.page(range, max = MAX_LAPORAN)
        val s = ra.sort(sort, SALES_SORT, default = "created_at")
        val (where, nilai) = filterPenjualan(ra.filter(filter))

        val isi = jdbc.sql(
            """
            SELECT p.id, p.created_at, p.user_id, p.product_id, p.lives_granted, p.status,
                   p.verified_at, p.voided_at, p.void_reason, u.display_name
              FROM purchase p JOIN app_user u ON u.id = p.user_id
            $where ORDER BY p.${s.column} ${s.arah} OFFSET ? LIMIT ?
            """,
        ).params(nilai + listOf(h.offset, h.limit)).query { rs, _ ->
            SaleDto(
                rs.getString("id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getString("user_id"),
                rs.getString("display_name") ?: "Pemain #${rs.getString("user_id")}",
                rs.getString("product_id"),
                rs.getInt("lives_granted"),
                rs.getString("status"),
                rs.getTimestamp("verified_at")?.toInstant(),
                rs.getTimestamp("voided_at")?.toInstant(),
                rs.getString("void_reason"),
            )
        }.list()

        val total = jdbc.sql("SELECT count(*) FROM purchase p $where").params(nilai).query(Long::class.java).single()
        return ResponseEntity.ok().header("Content-Range", h.contentRange("sales", isi.size, total)).body(isi)
    }

    // Ringkasan + deret harian untuk grafik. Dipisah dari daftar karena keduanya punya umur berbeda:
    // daftar dipaginasi & diekspor, ringkasan selalu menghitung SELURUH rentang yang difilter.
    @GetMapping("/sales/summary")
    fun salesSummary(
        principal: AdminPrincipal,
        @RequestParam(required = false) filter: String?,
    ): SalesSummary {
        principal.require(AdminRole.ADMIN, AdminRole.FINANCE)
        val (where, nilai) = filterPenjualan(ra.filter(filter))

        val perProduk = jdbc.sql(
            """
            SELECT p.product_id,
                   count(*) AS transaksi,
                   count(*) FILTER (WHERE p.status = 'granted') AS granted,
                   count(*) FILTER (WHERE p.status = 'voided') AS voided,
                   coalesce(sum(p.lives_granted), 0) AS lives
              FROM purchase p $where GROUP BY p.product_id ORDER BY p.product_id
            """,
        ).params(nilai).query { rs, _ ->
            mapOf<String, Any?>(
                "produk" to rs.getString("product_id"),
                "transaksi" to rs.getLong("transaksi"),
                "granted" to rs.getLong("granted"),
                "voided" to rs.getLong("voided"),
                "lives" to rs.getLong("lives"),
            )
        }.list()

        // Zona setempat, bukan UTC: "penjualan tanggal 3" harus berarti tanggal 3 menurut orang yang
        // membaca laporannya (pola yang sama dengan cap kalender casual, T-024).
        val harian = jdbc.sql(
            """
            SELECT (p.created_at AT TIME ZONE '$ZONA')::date AS tanggal,
                   count(*) AS transaksi,
                   coalesce(sum(p.lives_granted), 0) AS lives
              FROM purchase p $where GROUP BY 1 ORDER BY 1
            """,
        ).params(nilai).query { rs, _ ->
            mapOf<String, Any?>(
                "tanggal" to rs.getDate("tanggal").toLocalDate().toString(),
                "transaksi" to rs.getLong("transaksi"),
                "lives" to rs.getLong("lives"),
            )
        }.list()

        return SalesSummary(
            transaksi = perProduk.sumOf { it["transaksi"] as Long },
            livesGranted = perProduk.sumOf { it["lives"] as Long },
            voided = perProduk.sumOf { it["voided"] as Long },
            perProduk = perProduk,
            harian = harian,
            uangTersedia = false,
            catatanUang = CATATAN_UANG,
        )
    }

    // ── Laporan pemain ──────────────────────────────────────────────────────────────────────────

    data class PlayerDto(
        val id: String,
        val displayName: String,
        val status: String, // app_user.status: active | banned (ADR-0020)
        val createdAt: Instant,
        val runs: Long,
        val bestScore: Long,
        val livesAvailable: Long,
        val purchases: Long, // hanya yang benar-benar granted
        val casualClaims: Long,
        val activeBan: Boolean, // ban turnamen yang belum diampuni (ADR-0025)
        val lastActivityAt: Instant?,
    )

    @GetMapping("/players")
    fun players(
        @RequestParam(required = false) range: String?,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) filter: String?,
    ): ResponseEntity<List<PlayerDto>> {
        val h = ra.page(range, max = MAX_LAPORAN)
        val s = ra.sort(sort, PLAYER_SORT, default = "id")
        val f = ra.filter(filter)

        val syarat = mutableListOf<String>()
        val nilai = mutableListOf<Any>()
        // Pencarian HANYA pada nama tampilan. Email & no. HP sengaja tak bisa dicari maupun
        // ditampilkan di sini: laporan ini dibuka semua peran, sedangkan PII punya pintunya sendiri
        // (layar pemenang, peran finance, tiap bacaan ber-audit — ADR-0020).
        f["q"]?.takeIf { it.isNotBlank() }?.let {
            syarat += "u.display_name ILIKE ?"
            nilai += "%$it%"
        }
        f["banned"]?.let {
            if (it.equals("true", true)) syarat += "EXISTS (SELECT 1 FROM tournament_ban b WHERE b.user_id = u.id AND b.forgiven_at IS NULL)"
        }
        val where = if (syarat.isEmpty()) "" else "WHERE ${syarat.joinToString(" AND ")}"

        // ponytail: agregat sebagai subquery skalar per baris. Benar & mudah dibaca; dengan paging
        // 25–200 baris ia tak pernah menyentuh seluruh tabel. Kalau `app_user` sudah ratusan ribu
        // DAN laporan ini jadi lambat, obatnya tabel ringkasan yang di-refresh berkala — bukan
        // mengoptimalkan SQL ini lebih dulu.
        val isi = jdbc.sql(
            """
            SELECT u.id, u.display_name, u.status, u.created_at,
                   (SELECT count(*) FROM run r WHERE r.user_id = u.id) AS runs,
                   (SELECT coalesce(max(r.total_score), 0) FROM run r WHERE r.user_id = u.id) AS best_score,
                   (SELECT count(*) FROM life_ledger l WHERE l.user_id = u.id AND l.status = 'available') AS lives_available,
                   (SELECT count(*) FROM purchase p WHERE p.user_id = u.id AND p.status = 'granted') AS purchases,
                   (SELECT count(*) FROM life_ledger l WHERE l.user_id = u.id AND l.source = 'earn_casual') AS casual_claims,
                   EXISTS (SELECT 1 FROM tournament_ban b WHERE b.user_id = u.id AND b.forgiven_at IS NULL) AS active_ban,
                   greatest(
                     (SELECT max(r.updated_at) FROM run r WHERE r.user_id = u.id),
                     (SELECT max(p.created_at) FROM purchase p WHERE p.user_id = u.id),
                     (SELECT max(l.created_at) FROM life_ledger l WHERE l.user_id = u.id)
                   ) AS last_activity_at
              FROM app_user u
            $where ORDER BY u.${s.column} ${s.arah} OFFSET ? LIMIT ?
            """,
        ).params(nilai + listOf(h.offset, h.limit)).query { rs, _ ->
            PlayerDto(
                rs.getString("id"),
                rs.getString("display_name") ?: "Pemain #${rs.getString("id")}",
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getLong("runs"),
                rs.getLong("best_score"),
                rs.getLong("lives_available"),
                rs.getLong("purchases"),
                rs.getLong("casual_claims"),
                rs.getBoolean("active_ban"),
                rs.getTimestamp("last_activity_at")?.toInstant(),
            )
        }.list()

        val total = jdbc.sql("SELECT count(*) FROM app_user u $where").params(nilai).query(Long::class.java).single()
        return ResponseEntity.ok().header("Content-Range", h.contentRange("players", isi.size, total)).body(isi)
    }

    // ── Helper ──────────────────────────────────────────────────────────────────────────────────

    // Filter penjualan dipakai daftar DAN ringkasan — ditulis sekali supaya angka ringkasan tak
    // pernah menjelaskan baris yang berbeda dari yang tampil di tabelnya.
    private fun filterPenjualan(f: Map<String, String>): Pair<String, List<Any>> {
        val syarat = mutableListOf<String>()
        val nilai = mutableListOf<Any>()
        f["status"]?.let {
            syarat += "p.status = ?"
            nilai += it
        }
        f["productId"]?.let {
            syarat += "p.product_id = ?"
            nilai += it
        }
        f["dateFrom"]?.let {
            syarat += "p.created_at >= ?"
            nilai += tanggal(it, "dateFrom").atStartOfDay(ZONE).toOffsetDateTime()
        }
        // `< tanggal+1` supaya "sampai tanggal 5" memuat seluruh tanggal 5, bukan berhenti di 00:00.
        f["dateTo"]?.let {
            syarat += "p.created_at < ?"
            nilai += tanggal(it, "dateTo").plusDays(1).atStartOfDay(ZONE).toOffsetDateTime()
        }
        return (if (syarat.isEmpty()) "" else "WHERE ${syarat.joinToString(" AND ")}") to nilai
    }

    private fun tanggal(nilai: String, field: String): LocalDate =
        runCatching { LocalDate.parse(nilai.take(10)) }.getOrElse {
            throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, "$field harus tanggal YYYY-MM-DD.")
        }

    private companion object {
        // Ekspor CSV react-admin meminta 1000 baris sekali jalan; batas 200 milik layar CRUD akan
        // memotongnya diam-diam. Di atas ini ekspor butuh pager sendiri.
        const val MAX_LAPORAN = 5000

        val SALES_SORT = setOf("id", "created_at", "status", "product_id", "lives_granted")
        val PLAYER_SORT = setOf("id", "created_at", "display_name", "status")

        // Zona laporan = zona pemain (WIB). Sama seperti cap kalender casual (T-024): tanggal dalam
        // laporan harus berarti tanggal menurut orang yang membacanya.
        const val ZONA = "Asia/Jakarta"
        val ZONE: java.time.ZoneId = java.time.ZoneId.of(ZONA)

        const val CATATAN_UANG =
            "Nilai rupiah tak tersedia: harga hidup di Play Console (ADR-0022) dan tak dikembalikan " +
                "Play Developer API untuk produk sekali-beli, jadi `purchase.amount` tak pernah terisi. " +
                "Angka di sini = jumlah transaksi & nyawa. Penerimaan bersih (setelah promo, pajak, " +
                "potongan Google) hanya sah dibaca dari laporan keuangan Play Console."
    }
}
