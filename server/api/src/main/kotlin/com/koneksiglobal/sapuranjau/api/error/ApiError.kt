package com.koneksiglobal.sapuranjau.api.error

import jakarta.servlet.http.HttpServletResponse
import tools.jackson.databind.ObjectMapper // Jackson 3 — mapper default Spring Boot 4 / Spring 7
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

// Skema error standar (ADR-0035, follow-up ADR-0033): RFC 7807 `application/problem+json` (bawaan
// Spring `ProblemDetail`) + ekstensi `code` app-level yang stabil untuk klien.

// Kode error app-level (field `code`). Tambah anggota saat perlu; JANGAN rename lama (klien pegang).
// LOCKED/BANNED/CONSENT_REQUIRED (T-026) sengaja dibedakan dari CONFLICT/FORBIDDEN generik: klien
// memilih LAYAR dari kode ini — popup S&K, pesan ban, atau state "turnamen terkunci" (05 §3).
// INTEGRITY_REQUIRED (perlu attest dulu, klien tinggal memanggil POST /v1/integrity lalu mengulang)
// sengaja dibedakan dari INTEGRITY_FAILED (perangkat/APK ditolak Google — mengulang tak menolong).
// BAD_CREDENTIALS/TOTP_REQUIRED/TOO_MANY_ATTEMPTS/FORBIDDEN dipakai panel admin (T-040): SPA-nya
// memilih layar dari kode ini — form password, kotak kode authenticator, atau pesan "coba lagi nanti".
enum class ErrorCode {
    UNAUTHENTICATED, VALIDATION, NOT_FOUND, CONFLICT, INTERNAL,
    LOCKED, BANNED, CONSENT_REQUIRED, INTEGRITY_REQUIRED, INTEGRITY_FAILED,
    BAD_CREDENTIALS, TOTP_REQUIRED, TOO_MANY_ATTEMPTS, FORBIDDEN,
}

// Dilempar controller/service utk error terkendali → dipetakan ke ProblemDetail oleh advice.
class ApiException(val status: HttpStatus, val code: ErrorCode, override val message: String) : RuntimeException(message)

// Satu titik bangun ProblemDetail → bentuk error seragam di seluruh API.
fun problem(status: HttpStatus, code: ErrorCode, detail: String): ProblemDetail =
    ProblemDetail.forStatusAndDetail(status, detail).apply { setProperty("code", code.name) }

// Tulis ProblemDetail langsung ke response — utk filter auth yang jalan DI LUAR jangkauan
// @RestControllerAdvice. `mapper` = ObjectMapper Spring (punya mixin ProblemDetail → `code` ikut).
fun HttpServletResponse.writeProblem(mapper: ObjectMapper, status: HttpStatus, code: ErrorCode, detail: String) {
    this.status = status.value()
    contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
    mapper.writeValue(writer, problem(status, code, detail))
}

// Warisi ResponseEntityExceptionHandler: Spring sudah memetakan SEMUA exception MVC standar
// (404 rute salah, 400 body rusak/validasi, 405, 415, …) ke ProblemDetail dgn status benar. Tanpa itu,
// catch-all `Exception` di bawah menelan semuanya jadi 500 (ExceptionHandlerExceptionResolver menang
// atas DefaultHandlerExceptionResolver) — terbukti: GET /v1/tak-ada balas 500.
@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(e.status).body(problem(e.status, e.code, e.message))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ProblemDetail> {
        logger.error("Exception tak tertangani", e) // jangan 500 diam-diam: tanpa ini prod buta
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL, "Kesalahan tak terduga."))
    }

    // Stempel `code` (ADR-0035) ke ProblemDetail bawaan Spring → satu bentuk error di seluruh API.
    override fun createResponseEntity(
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any> {
        (body as? ProblemDetail)?.setProperty("code", codeOf(statusCode).name)
        return super.createResponseEntity(body, headers, statusCode, request)
    }

    private fun codeOf(status: HttpStatusCode): ErrorCode = when {
        status.value() == 404 -> ErrorCode.NOT_FOUND
        status.value() == 409 -> ErrorCode.CONFLICT
        status.is4xxClientError -> ErrorCode.VALIDATION
        else -> ErrorCode.INTERNAL
    }
}
