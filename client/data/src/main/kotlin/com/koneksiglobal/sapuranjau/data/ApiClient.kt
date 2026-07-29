package com.koneksiglobal.sapuranjau.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

// Alamat server saat dev: 10.0.2.2 = host mesin dev dilihat dari DALAM emulator Android.
// Perangkat fisik → ganti IP LAN mesin dev. URL produksi menyusul saat VPS berdiri (ADR-0015).
//
// DUA HAL yang wajib dikerjakan task pertama yang memasang modul ini ke APK (T-032), keduanya di
// `client/app` karena di sanalah manifest APK dirakit — casual masih offline penuh sampai saat itu:
//   1. `<uses-permission android:name="android.permission.INTERNET" />` di manifest utama;
//   2. `client/app/src/debug/AndroidManifest.xml` dengan `android:usesCleartextTraffic="true"` —
//      `http://` polos diblokir Android sejak API 28; varian debug saja, tak pernah ikut rilis.
const val DEV_BASE_URL = "http://10.0.2.2:8080"

// Kode error app-level server (ADR-0035; cerminan `ErrorCode` di server/api). Nama HARUS identik —
// klien memilih LAYAR dari kode ini: popup S&K, layar ban, state terkunci, layar integrity.
// `UNKNOWN` menampung kode yang versi klien ini belum kenal: APK terpasang tak bisa dipaksa update,
// jadi kode baru dari server harus berakhir sebagai pesan error biasa, bukan crash.
enum class ApiErrorCode {
    UNAUTHENTICATED, VALIDATION, NOT_FOUND, CONFLICT, INTERNAL,
    LOCKED, BANNED, CONSENT_REQUIRED, INTEGRITY_REQUIRED, INTEGRITY_FAILED,
    UNKNOWN,
}

/** Respons 4xx/5xx dari server, sudah diterjemahkan dari RFC 7807 (ADR-0035). */
class ApiException(val status: Int, val code: ApiErrorCode, val detail: String) : Exception(detail)

@Serializable
private data class Problem(val detail: String? = null, val code: String? = null)

// Transport: satu tempat yang tahu URL, Bearer token, dan bentuk error. Sengaja TANPA Retrofit —
// bentuk error RFC 7807 tetap harus diterjemahkan tangan, jadi Retrofit hanya menyisakan anotasi
// per-endpoint sementara `send()` di bawah sudah melayani semuanya.
class ApiClient(
    private val baseUrl: String,
    private val tokens: TokenProvider,
    private val http: OkHttpClient = defaultHttp(),
) {
    suspend fun <T> get(path: String, out: KSerializer<T>): T = send("GET", path, null, out)

    suspend fun <B, T> post(path: String, body: B, into: KSerializer<B>, out: KSerializer<T>): T =
        send("POST", path, json.encodeToString(into, body), out)

    // ponytail: `execute()` blocking di Dispatchers.IO — cancel coroutine tak memutus socket, jadi
    // batas atasnya = timeout di bawah. Pindah ke `enqueue` + suspendCancellableCoroutine kalau
    // nanti ada layar yang benar-benar perlu membatalkan request di tengah jalan.
    private suspend fun <T> send(method: String, path: String, body: String?, out: KSerializer<T>): T =
        withContext(Dispatchers.IO) {
            val token = tokens.idToken()
                ?: throw ApiException(401, ApiErrorCode.UNAUTHENTICATED, "Perlu masuk dulu untuk fitur ini.")
            val request = Request.Builder()
                .url(baseUrl + path)
                .header("Authorization", "Bearer $token")
                .method(method, body?.toRequestBody(JSON_TYPE))
                .build()
            http.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (!response.isSuccessful) throw problemOf(response.code, text)
                json.decodeFromString(out, text)
            }
        }

    // Error yang bukan problem+json (proxy, gateway, HTML) tetap harus jadi ApiException yang wajar —
    // pemain melihat pesan, bukan stack trace parser.
    private fun problemOf(status: Int, text: String): ApiException {
        val problem = runCatching { json.decodeFromString(Problem.serializer(), text) }.getOrNull()
        val code = ApiErrorCode.entries.firstOrNull { it.name == problem?.code } ?: ApiErrorCode.UNKNOWN
        return ApiException(status, code, problem?.detail ?: "Gagal menghubungi server (HTTP $status).")
    }

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()

        // ignoreUnknownKeys: server boleh menambah field tanpa mematikan APK lama.
        // coerceInputValues: nilai enum yang belum dikenal jatuh ke default (UNKNOWN) — alasan sama.
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
