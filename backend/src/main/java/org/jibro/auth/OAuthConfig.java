package org.jibro.auth;

import java.util.*;

import org.jibro.global.config.SecurityConfig;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;

@Configuration
public class OAuthConfig {
    private final List<ClientRegistration> registrations = new ArrayList<>();
    private final String frontend;

    public OAuthConfig(Environment env) {
        var endpoints = OAuthEndpoints.from(env);
        frontend = endpoints.frontend();
        for (String provider : List.of("google", "kakao")) {
            String id = env.getProperty(provider.toUpperCase(Locale.ROOT) + "_CLIENT_ID", "");
            String secret = env.getProperty(provider.toUpperCase(Locale.ROOT) + "_CLIENT_SECRET", "");
            if (id.isBlank() && secret.isBlank()) continue;
            if (id.isBlank() || secret.isBlank())
                throw new IllegalStateException("OAuth provider requires both client ID and client secret: " + provider);
            var builder = ClientRegistration.withRegistrationId(provider).clientId(id).clientSecret(secret)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(endpoints.callbackBase() + "/login/oauth2/code/{registrationId}").clientName(provider);
            if (provider.equals("google"))
                builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .scope("openid", "profile", "email").authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                    .tokenUri("https://oauth2.googleapis.com/token").userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                    .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs").issuerUri("https://accounts.google.com").userNameAttributeName("sub");
            else builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .scope("profile_nickname", "account_email").authorizationUri("https://kauth.kakao.com/oauth/authorize")
                .tokenUri("https://kauth.kakao.com/oauth/token").userInfoUri("https://kapi.kakao.com/v2/user/me").userNameAttributeName("id");
            registrations.add(builder.build());
        }
    }

    public List<String> providers() {
        return registrations.stream().map(ClientRegistration::getRegistrationId).toList();
    }

    @Bean
    @Order(2)
    SecurityFilterChain oauthSecurity(HttpSecurity http, AuthService auth, RefreshCookie refreshCookie, AccountConnections connections) throws Exception {
        http.authorizeHttpRequests(a -> a.requestMatchers("/oauth2/**", "/login/oauth2/**").permitAll().anyRequest().denyAll());
        if (!registrations.isEmpty()) {
            var clients = new InMemoryClientRegistrationRepository(registrations);
            var flow = new ConnectionOAuthFlow(clients, connections);
            var completion = new OAuthCompletionHandler(auth, refreshCookie, connections, frontend);
            http.oauth2Login(o -> o.clientRegistrationRepository(clients)
                .authorizationEndpoint(a -> a.authorizationRequestResolver(flow).authorizationRequestRepository(flow))
                .successHandler(completion).failureHandler(completion));
        }
        http.exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> SecurityConfig.error(res, 503, "PROVIDER_UNAVAILABLE", "소셜 로그인 설정을 확인해 주세요.")));
        return http.build();
    }

    @RestController
    public static class ProvidersController {
        private final OAuthConfig config;

        public ProvidersController(OAuthConfig config) {
            this.config = config;
        }

        @GetMapping("/api/v1/auth/providers")
        public List<String> providers() {
            return config.providers();
        }
    }
}
