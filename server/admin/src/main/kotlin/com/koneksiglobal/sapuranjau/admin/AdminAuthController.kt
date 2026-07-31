package com.koneksiglobal.sapuranjau.admin

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.audit.Actor
import com.koneksiglobal.sapuranjau.audit.AuditService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

// Auth panel admin (T-040, ADR-0013). Path efektif = `/admin/api/...` (prefix paket, AdminWebConfig).
//
// Bentuknya mengikuti apa yang dibutuhkan `authProvider` React-Admin: login / logout / checkAuth
// (`/me`) — tiga panggilan, tak lebih.
@RestController
class AdminAuthController(
    private val service: AdminAuthService,
    private val audit: AuditService,
) {

    data class LoginRequest(val username: String = "", val password: String = "", val code: String? = null)

    // `status` = OK | TOTP_SETUP_REQUIRED. Enrolment bukan kegagalan, jadi ia 200 dengan langkah
    // berikutnya, bukan 4xx yang harus ditebak artinya oleh SPA.
    data class LoginResponse(
        val status: String,
        val username: String? = null,
        val role: String? = null,
        val secret: String? = null,
        val otpauthUri: String? = null,
    )

    data class EnrollRequest(val code: String = "")
    data class MeResponse(val id: String, val username: String, val role: String)

    @PostMapping("/login")
    fun login(request: HttpServletRequest, @RequestBody body: LoginRequest): LoginResponse =
        when (val hasil = service.login(body.username, body.password, body.code)) {
            is LoginOutcome.Ok -> {
                masuk(request, hasil.principal)
                LoginResponse("OK", hasil.principal.username, hasil.principal.role.dbValue)
            }

            is LoginOutcome.SetupTotp -> {
                // Sesi ini BELUM terautentikasi — ia cuma memegang secret yang menunggu dikonfirmasi.
                sesiBaru(request).setAttribute(AdminSessionFilter.ATTR_PENDING_TOTP, hasil.pending)
                LoginResponse("TOTP_SETUP_REQUIRED", secret = hasil.pending.secretBase32, otpauthUri = hasil.otpauthUri)
            }
        }

    @PostMapping("/totp/enroll")
    fun enroll(request: HttpServletRequest, @RequestBody body: EnrollRequest): LoginResponse {
        val pending = request.getSession(false)?.getAttribute(AdminSessionFilter.ATTR_PENDING_TOTP) as? PendingTotp
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, "Tak ada enrolment yang menunggu — login ulang.")
        val principal = service.enrollTotp(pending, body.code)
        masuk(request, principal)
        return LoginResponse("OK", principal.username, principal.role.dbValue)
    }

    @PostMapping("/logout")
    fun logout(request: HttpServletRequest): ResponseEntity<Void> {
        (request.getSession(false)?.getAttribute(AdminSessionFilter.ATTR_ADMIN) as? AdminPrincipal)?.let {
            audit.record(Actor.ADMIN, it.id, "admin_logout", "admin:${it.username}")
        }
        request.getSession(false)?.invalidate()
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/me")
    fun me(principal: AdminPrincipal): MeResponse =
        MeResponse(principal.id.toString(), principal.username, principal.role.dbValue)

    // Sesi lama selalu dibuang sebelum yang baru dibuat: tanpa ini, id sesi yang sudah dipegang
    // penyerang (session fixation) berlanjut menjadi sesi yang terautentikasi.
    private fun masuk(request: HttpServletRequest, principal: AdminPrincipal) {
        sesiBaru(request).setAttribute(AdminSessionFilter.ATTR_ADMIN, principal)
    }

    private fun sesiBaru(request: HttpServletRequest) = run {
        request.getSession(false)?.invalidate()
        request.getSession(true)
    }
}
