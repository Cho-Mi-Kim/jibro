package org.jibro.global.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.jdbc.core.JdbcTemplate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    SecretKeySpec jwtKey(@Value("${app.auth.jwt-secret}") String secret) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes");
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKeySpec key) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKeySpec key, JdbcTemplate db) {
        var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer("jibro"),
            new JwtClaimValidator<java.util.List<String>>("aud", value -> value != null && value.contains("jibro-web")),
            (Jwt jwt) -> {
                Number version = jwt.getClaim("sv");
                boolean valid = jwt.getSubject() != null && Boolean.TRUE.equals(db.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM app_user WHERE id::text=? AND NOT disabled AND (trial_expires_at IS NULL OR trial_expires_at>now()) AND security_version=?)", Boolean.class,
                    jwt.getSubject(), version == null ? 0 : version.intValue()));
                return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
            }));
        return decoder;
    }

    @Bean
    @Order(1)
    SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
        var csrf = new CookieCsrfTokenRepository();
        csrf.setHeaderName("X-CSRF-TOKEN");
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return http.securityMatcher("/api/**")
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.GET, "/api/v1/health", "/api/v1/auth/csrf", "/api/v1/auth/providers", "/api/v1/meta", "/api/v1/auth/mail-mode", "/api/v1/notices/**", "/api/v1/guides").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/trial", "/api/v1/auth/signup", "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout", "/api/v1/auth/reset/request", "/api/v1/auth/reset/complete", "/api/v1/auth/verify").permitAll()
                .requestMatchers("/api/v1/member/verify", "/api/v1/member/closure/**", "/api/v1/member/password", "/api/v1/member/channels", "/api/v1/member/channels/**").hasAnyRole("USER", "ADMIN")
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN").anyRequest().authenticated())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(c -> c.csrfTokenRepository(csrf))
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))
                .authenticationEntryPoint((req, res, e) -> error(res, 401, "UNAUTHORIZED", "로그인이 필요합니다.")))
            .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> error(res, 401, "UNAUTHORIZED", "로그인이 필요합니다."))
                .accessDeniedHandler((req, res, ex) -> error(res, 403, "FORBIDDEN", "권한 또는 요청 인증 정보를 확인해 주세요.")))
            .requestCache(c -> c.disable()).build();
    }

    public static void error(jakarta.servlet.http.HttpServletResponse res, int status, String code, String message) throws java.io.IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"fieldErrors\":[],\"timestamp\":\"" + java.time.Instant.now() + "\"}");
    }
}
