package com.koneksiglobal.sapuranjau.admin

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.audit.Actor
import com.koneksiglobal.sapuranjau.audit.AuditService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

// Resource `admin-users` (T-040). Selain mengelola operator, ia juga **bukti kontrak transport**
// ADR-0013 di sisi server: bentuk list/`Content-Range`/`getOne`/create/update yang dipakai seluruh
// resource T-042 nanti dibuktikan sekali di sini, oleh test end-to-end.
//
// Akun TIDAK pernah dihapus (tak ada DELETE): `audit_event.actor_id` merujuk id ini, dan menghapus
// barisnya membuat jejak audit menunjuk ke ruang kosong. Menonaktifkan = `disabled_at`.
@RestController
@RequestMapping("/admin-users")
class AdminUserController(
    private val users: AdminUsers,
    private val service: AdminAuthService,
    private val ra: RaQuery,
    private val audit: AuditService,
) {

    data class AdminUserDto(
        val id: String,
        val username: String,
        val role: String,
        val disabled: Boolean,
        val totpEnrolled: Boolean, // panel perlu melihat akun mana yang 2FA-nya belum dipasang
    )

    data class CreateRequest(val username: String = "", val password: String = "", val role: String = "moderator")
    data class UpdateRequest(val role: String = "moderator", val disabled: Boolean = false, val password: String? = null)

    @GetMapping
    fun list(
        principal: AdminPrincipal,
        @RequestParam(required = false) range: String?,
        @RequestParam(required = false) sort: String?,
    ): ResponseEntity<List<AdminUserDto>> {
        principal.require(AdminRole.ADMIN)
        val halaman = ra.page(range)
        val urut = ra.sort(sort, KOLOM_SORT)
        val isi = users.page(urut.column, urut.ascending, halaman.offset, halaman.limit).map(::dto)
        return ResponseEntity.ok()
            .header("Content-Range", halaman.contentRange("admin-users", isi.size, users.count()))
            .body(isi)
    }

    @GetMapping("/{id}")
    fun one(principal: AdminPrincipal, @PathVariable id: Long): AdminUserDto {
        principal.require(AdminRole.ADMIN)
        return dto(users.byId(id) ?: tidakAda(id))
    }

    @PostMapping
    fun create(principal: AdminPrincipal, @RequestBody body: CreateRequest): AdminUserDto {
        principal.require(AdminRole.ADMIN)
        val id = service.createAdmin(principal, body.username, body.password, peran(body.role))
        return dto(users.byId(id)!!)
    }

    @PutMapping("/{id}")
    fun update(principal: AdminPrincipal, @PathVariable id: Long, @RequestBody body: UpdateRequest): AdminUserDto {
        principal.require(AdminRole.ADMIN)
        val target = users.byId(id) ?: tidakAda(id)
        val peranBaru = peran(body.role)

        // Dua pagar anti-kunci-diri-sendiri. Tanpa keduanya, satu klik keliru = panel tak bisa dimasuki
        // siapa pun lagi dan pemulihannya harus lewat SQL di server produksi.
        if (target.id == principal.id && (body.disabled || peranBaru != AdminRole.ADMIN)) {
            throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Tak bisa menonaktifkan atau menurunkan peran akun sendiri.")
        }
        val kehilanganAdmin = target.role == AdminRole.ADMIN && target.disabledAt == null &&
            (body.disabled || peranBaru != AdminRole.ADMIN)
        if (kehilanganAdmin && users.activeAdminCount() <= 1) {
            throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Ini satu-satunya akun peran 'admin' yang aktif.")
        }

        users.update(id, peranBaru, body.disabled)
        body.password?.takeIf { it.isNotBlank() }?.let {
            service.cekPassword(it)
            users.setPassword(id, service.hash(it))
            audit.record(Actor.ADMIN, principal.id, "admin_password_reset", "admin:${target.username}")
        }
        audit.record(
            Actor.ADMIN, principal.id, "admin_updated", "admin:${target.username}",
            mapOf("role" to peranBaru.dbValue, "disabled" to body.disabled),
        )
        return dto(users.byId(id)!!)
    }

    // Operator yang kehilangan authenticator-nya tak bisa login sama sekali (enrolTotp menolak akun
    // yang sudah terdaftar) — sebelum T-042 pemulihannya cuma SQL di server produksi.
    //
    // Akun SENDIRI sengaja tak bisa direset: pemiliknya yang kehilangan authenticator toh tak punya
    // sesi untuk menekan tombol ini, jadi satu-satunya yang terbantu adalah sesi curian yang ingin
    // memindahkan 2FA ke perangkatnya sendiri. Reset selalu dilakukan operator lain.
    @PostMapping("/{id}/reset-totp")
    fun resetTotp(principal: AdminPrincipal, @PathVariable id: Long): AdminUserDto {
        principal.require(AdminRole.ADMIN)
        val target = users.byId(id) ?: tidakAda(id)
        if (target.id == principal.id) {
            throw ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Reset 2FA akun sendiri harus dilakukan operator lain.")
        }
        users.clearTotpSecret(id)
        audit.record(Actor.ADMIN, principal.id, "admin_totp_reset", "admin:${target.username}")
        return dto(users.byId(id)!!)
    }

    private fun dto(u: AdminUser) = AdminUserDto(u.id.toString(), u.username, u.role.dbValue, u.disabledAt != null, u.totpEnrolled)

    private fun peran(nilai: String): AdminRole = runCatching { AdminRole.of(nilai.trim().lowercase()) }.getOrElse {
        throw ApiException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION, "Peran tak dikenal: '$nilai'.")
    }

    private fun tidakAda(id: Long): Nothing =
        throw ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Akun admin $id tak ada.")

    private companion object {
        val KOLOM_SORT = setOf("id", "username", "role", "created_at", "last_login_at")
    }
}
