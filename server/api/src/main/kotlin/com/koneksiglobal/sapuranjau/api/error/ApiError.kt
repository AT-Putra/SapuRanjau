package com.koneksiglobal.sapuranjau.api.error

import jakarta.servlet.http.HttpServletResponse
import tools.jackson.databind.ObjectMapper // Jackson 3 — mapper default Spring Boot 4 / Spring 7
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

// Skema error standar (ADR-0035, follow-up ADR-0033): RFC 7807 `application/problem+json` (bawaan
// Spring `ProblemDetail`) + ekstensi `code` app-level yang stabil untuk klien.

// Kode error app-level (field `code`). Tambah anggota saat perlu; JANGAN rename lama (klien pegang).
enum class ErrorCode { UNAUTHENTICATED, VALIDATION, NOT_FOUND, CONFLICT, INTERNAL }

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

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(e.status).body(problem(e.status, e.code, e.message))

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ProblemDetail> =
        ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL, "Kesalahan tak terduga."))
}
