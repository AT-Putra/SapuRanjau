package com.koneksiglobal.sapuranjau.api.web

import com.koneksiglobal.sapuranjau.api.auth.BearerAuthFilter
import com.koneksiglobal.sapuranjau.api.auth.TokenVerifier
import com.koneksiglobal.sapuranjau.api.auth.VerifiedUser
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import tools.jackson.databind.ObjectMapper // Jackson 3 — mapper default Spring Boot 4

// Konvensi HTTP edge (ADR-0035): (1) base path & versi = prefix `/v1` utk semua controller paket
// `...api` (bukan `/health` yang di paket app); (2) controller boleh terima `VerifiedUser` langsung.
@Configuration
class ApiWebConfig(
    private val verifier: TokenVerifier,
    private val mapper: ObjectMapper,
) : WebMvcConfigurer {

    override fun configurePathMatch(configurer: PathMatchConfigurer) {
        // Semua controller modul feature (sub-paket dari base) ter-prefix `/v1` → otomatis pula
        // terlindungi BearerAuthFilter yang menjaga `/v1/*`. Default AMAN: modul baru (game, lives, …)
        // tak bisa lupa mendaftar dan diam-diam terbit tanpa auth. Controller di paket base itu
        // sendiri (server/app: `/health`) TIDAK ter-prefix — memang publik.
        configurer.addPathPrefix("/v1") { it.packageName != BASE_PACKAGE && it.packageName.startsWith("$BASE_PACKAGE.") }
    }

    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers.add(CurrentUserResolver())
    }

    @Bean
    fun bearerAuthFilter(): FilterRegistrationBean<BearerAuthFilter> =
        FilterRegistrationBean(BearerAuthFilter(verifier, mapper)).apply { order = 1 }

    private companion object {
        const val BASE_PACKAGE = "com.koneksiglobal.sapuranjau"
    }
}

// Injeksi VerifiedUser ke param controller dari atribut request (diisi BearerAuthFilter). Endpoint
// ber-prefix /v1 dijamin sudah lewat filter → atribut selalu ada di controller.
class CurrentUserResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(p: MethodParameter): Boolean = p.parameterType == VerifiedUser::class.java

    override fun resolveArgument(
        p: MethodParameter,
        mav: ModelAndViewContainer?,
        req: NativeWebRequest,
        binder: WebDataBinderFactory?,
    ): Any? = req.getNativeRequest(HttpServletRequest::class.java)?.getAttribute(BearerAuthFilter.ATTR_USER)
}
