package com.koneksiglobal.sapuranjau.api.auth

import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.api.error.writeProblem
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper // Jackson 3 — mapper default Spring Boot 4

// Gerbang auth: hanya lindungi jalur ber-prefix /v1 (endpoint bisnis, 05 §3); /health & lainnya
// lewat. Token valid → simpan VerifiedUser sbg atribut request (dibaca CurrentUserResolver).
class BearerAuthFilter(
    private val verifier: TokenVerifier,
    private val mapper: ObjectMapper,
) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/v1/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val header = request.getHeader("Authorization")
        val token = header?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
        val user = token?.let { verifier.verify(it) }
        if (user == null) {
            response.writeProblem(mapper, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, "Firebase ID token wajib & valid (Bearer).")
            return
        }
        request.setAttribute(ATTR_USER, user)
        chain.doFilter(request, response)
    }

    companion object {
        const val ATTR_USER = "sapuranjau.verifiedUser"
    }
}
