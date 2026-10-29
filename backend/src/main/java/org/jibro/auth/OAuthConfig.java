package org.jibro.auth;

import java.util.*;
import org.jibro.global.config.SecurityConfig;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.bind.annotation.*;

@Configuration
public class OAuthConfig {
    private final List<ClientRegistration> registrations=new ArrayList<>();
    private final String frontend;
    public OAuthConfig(Environment env) {
        frontend=env.getProperty("app.auth.frontend-url","http://127.0.0.1:5173");
        for(String provider:List.of("google","kakao")) {
            String id=env.getProperty(provider.toUpperCase(Locale.ROOT)+"_CLIENT_ID","");
            String secret=env.getProperty(provider.toUpperCase(Locale.ROOT)+"_CLIENT_SECRET","");
            if(id.isBlank() || secret.isBlank()) continue;
            var builder=ClientRegistration.withRegistrationId(provider).clientId(id).clientSecret(secret)
                    .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}").clientName(provider);
            if(provider.equals("google")) builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                    .scope("openid","profile","email").authorizationUri("https://accounts.google.com/o/oauth2/v2/auth")
                    .tokenUri("https://oauth2.googleapis.com/token").userInfoUri("https://openidconnect.googleapis.com/v1/userinfo")
                    .jwkSetUri("https://www.googleapis.com/oauth2/v3/certs").issuerUri("https://accounts.google.com").userNameAttributeName("sub");
            else builder.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                    .scope("profile_nickname","account_email").authorizationUri("https://kauth.kakao.com/oauth/authorize")
                    .tokenUri("https://kauth.kakao.com/oauth/token").userInfoUri("https://kapi.kakao.com/v2/user/me").userNameAttributeName("id");
            registrations.add(builder.build());
        }
    }
    public List<String> providers(){return registrations.stream().map(ClientRegistration::getRegistrationId).toList();}
    @Bean @Order(2)
    SecurityFilterChain oauthSecurity(HttpSecurity http,AuthService auth,AuthController controller) throws Exception {
        http.authorizeHttpRequests(a -> a.requestMatchers("/oauth2/**","/login/oauth2/**").permitAll().anyRequest().denyAll());
        if(!registrations.isEmpty()) {
            http.oauth2Login(o -> o.clientRegistrationRepository(new InMemoryClientRegistrationRepository(registrations))
                .successHandler((request,response,authentication) -> {
                    try {
                        var token=(OAuth2AuthenticationToken)authentication;
                        Map<String,Object> attrs=token.getPrincipal().getAttributes();
                        String provider=token.getAuthorizedClientRegistrationId();
                        String email; String name;
                        if(provider.equals("google")) {
                            email=Boolean.TRUE.equals(attrs.get("email_verified"))?(String)attrs.get("email"):null;
                            name=(String)attrs.get("name");
                        } else {
                            var account=attrs.get("kakao_account") instanceof Map<?,?> map?map:Map.of();
                            email=Boolean.TRUE.equals(account.get("is_email_valid")) && Boolean.TRUE.equals(account.get("is_email_verified"))?(String)account.get("email"):null;
                            name=account.get("profile") instanceof Map<?,?> profile?(String)profile.get("nickname"):null;
                        }
                        controller.session(auth.social(provider,token.getName(),name,email),response);
                        var session=request.getSession(false); if(session!=null) session.invalidate();
                        response.sendRedirect(frontend+"/auth/callback");
                    } catch(RuntimeException ex) {
                        var session=request.getSession(false); if(session!=null) session.invalidate();
                        response.sendRedirect(frontend+"/login?oauthError=account");
                    }
                }).failureHandler((request,response,exception) -> response.sendRedirect(frontend+"/login?oauthError=provider")));
        }
        http.exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> SecurityConfig.error(res,503,"PROVIDER_UNAVAILABLE","소셜 로그인 설정을 확인해 주세요.")));
        return http.build();
    }
    @RestController
    public static class ProvidersController {
        private final OAuthConfig config;
        public ProvidersController(OAuthConfig config){this.config=config;}
        @GetMapping("/api/v1/auth/providers") public List<String> providers(){return config.providers();}
    }
}
