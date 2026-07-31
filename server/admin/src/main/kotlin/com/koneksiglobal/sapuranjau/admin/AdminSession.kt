package com.koneksiglobal.sapuranjau.admin

import com.koneksiglobal.sapuranjau.api.error.ApiException
import com.koneksiglobal.sapuranjau.api.error.ErrorCode
import com.koneksiglobal.sapuranjau.api.error.writeProblem
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.ObjectMapper // Jackson 3 — mapper default Spring Boot 4
import java.io.Serializable

// Identitas admin yang sedang login, disimpan di HttpSession. `Serializable` supaya sesi tetap utuh
// kalau kelak container mem-persist/mereplikasi sesi — bukan karena hari ini dibutuhkan.
data class AdminPrincipal(val id: Long, val username: String, val role: AdminRole) : Serializable

// Enrolment TOTP yang belum dikonfirmasi. Secret-nya HANYA hidup di sesi sampai operator membuktikan
// aplikasi authenticator-nya benar-benar menghasilkan kode yang cocok; menulisnya ke DB lebih awal
// berarti akun bisa berakhir memegang secret yang tak dimiliki siapa pun.
data class PendingTotp(val adminId: Long, val secretBase32: String) : Serializable

fun AdminPrincipal.require(vararg roles: AdminRole) {
    if (role !in roles) {
        throw ApiException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, "Peran '${role.dbValue}' tak berwenang untuk aksi ini.")
    }
}

// Gerbang panel admin. Sengaja BUKAN Spring Security: filter chain-nya auto-config global dan akan
// ikut menyentuh `/v1` milik pemain yang sudah punya gerbang sendiri (BearerAuthFilter, ADR-0035).
// Yang dibutuhkan di sini cuma "ada sesi valid atau tidak" — itu satu atribut sesi.
class AdminSessionFilter(private val mapper: ObjectMapper) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("$ADMIN_API/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        // CSRF (ADR-0013). Cookie sesi ber-`SameSite=Lax` sudah menghentikan POST lintas-situs karena
        // cookie-nya tak ikut terkirim; header custom ini lapis kedua — ia mustahil dipasang oleh
        // <form> atau <img> lintas-origin, hanya oleh XHR/fetch yang tunduk pada CORS (dan kita tak
        // membuka CORS di prod: SPA-nya se-origin). Tanpa token, tanpa state server.
        if (request.method !in AMAN && request.getHeader("X-Requested-With") != XHR) {
            response.writeProblem(
                mapper, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                "Permintaan ditolak: header X-Requested-With wajib (proteksi CSRF).",
            )
            return
        }

        if (request.requestURI !in TANPA_SESI) {
            val principal = request.getSession(false)?.getAttribute(ATTR_ADMIN)
            if (principal !is AdminPrincipal) {
                response.writeProblem(mapper, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, "Sesi admin tak ada atau kedaluwarsa.")
                return
            }
            request.setAttribute(ATTR_ADMIN, principal)
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val ADMIN_API = "/admin/api"
        const val ATTR_ADMIN = "sapuranjau.admin"
        const val ATTR_PENDING_TOTP = "sapuranjau.admin.pendingTotp"
        private const val XHR = "XMLHttpRequest"
        private val AMAN = setOf("GET", "HEAD", "OPTIONS")

        // Login jelas belum punya sesi; enrolment TOTP dijaga atribut `pendingTotp` di controller-nya
        // (sesi ada, tapi BELUM terautentikasi) — kalau ia dijaga filter ini, tak seorang pun bisa
        // menyelesaikan 2FA pertama kali.
        private val TANPA_SESI = setOf("$ADMIN_API/login", "$ADMIN_API/totp/enroll")
    }
}

// Konvensi HTTP panel admin (ADR-0013): semua controller paket `...admin` ter-prefix `/admin/api`
// — terpisah dari `/v1` (Bearer Firebase) dan otomatis terlindungi AdminSessionFilter. Default aman
// dengan pola yang sama seperti `/v1`: controller admin baru tak bisa lupa mendaftar lalu terbit
// telanjang di root.
@Configuration
class AdminWebConfig(private val mapper: ObjectMapper) : WebMvcConfigurer {

    override fun configurePathMatch(configurer: PathMatchConfigurer) {
        configurer.addPathPrefix(AdminSessionFilter.ADMIN_API) { it.packageName.startsWith(ADMIN_PACKAGE) }
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(AdminPrincipalResolver())
    }

    // SPA-nya duduk sebagai static resource `static/admin/index.html` (T-041). Spring hanya
    // menyajikan index.html secara otomatis untuk root ("/"), jadi tanpa dua baris ini `/admin`
    // membalas 404 padahal panelnya ADA di dalam jar — dan itu tak terlihat dari test mana pun
    // sampai ada manusia yang mengetik alamatnya di browser.
    //
    // Cukup dua alamat karena panel memakai hash router: setiap layar adalah `/admin/#/...`, yang
    // tetap satu permintaan HTTP untuk `/admin/`. Deep-link tak pernah menyentuh server.
    override fun addViewControllers(registry: ViewControllerRegistry) {
        listOf("/admin", "/admin/").forEach {
            registry.addViewController(it).setViewName("forward:/admin/index.html")
        }
    }

    @Bean
    fun adminSessionFilter(): FilterRegistrationBean<AdminSessionFilter> =
        FilterRegistrationBean(AdminSessionFilter(mapper)).apply { order = 1 }

    private companion object {
        const val ADMIN_PACKAGE = "com.koneksiglobal.sapuranjau.admin"
    }
}

// Injeksi AdminPrincipal ke parameter controller (pola CurrentUserResolver di `server/api`).
class AdminPrincipalResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(p: MethodParameter): Boolean = p.parameterType == AdminPrincipal::class.java

    override fun resolveArgument(
        p: MethodParameter,
        mav: ModelAndViewContainer?,
        req: NativeWebRequest,
        binder: WebDataBinderFactory?,
    ): Any? = req.getNativeRequest(HttpServletRequest::class.java)?.getAttribute(AdminSessionFilter.ATTR_ADMIN)
}
