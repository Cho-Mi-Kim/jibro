package org.jibro.auth;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.web.client.RestClientResponseException;

final class OAuthFailureDiagnostics {
    private OAuthFailureDiagnostics() {
    }

    static String summarize(Throwable failure) {
        String code = "unknown";
        String providerCode = "none";
        String reason = "unspecified";
        String causeType = failure.getClass().getSimpleName();
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            causeType = cause.getClass().getSimpleName();
            if (cause instanceof OAuth2AuthenticationException oauth) {
                String value = oauth.getError().getErrorCode();
                if (value != null && value.matches("[a-z_]{1,64}")) code = value;
            }
            // Only emit the provider's documented error identifier, never exception text or URLs.
            String message = cause.getMessage();
            if (message != null) {
                var matcher = java.util.regex.Pattern.compile("\\bKOE\\d{3}\\b").matcher(message);
                if (matcher.find()) providerCode = matcher.group();
            }
            if (cause instanceof RestClientResponseException response) {
                String body = response.getResponseBodyAsString();
                var numericCode = java.util.regex.Pattern.compile("\"code\"\\s*:\\s*(-?\\d{1,5})\\b").matcher(body);
                if (numericCode.find()) providerCode = numericCode.group(1);
                if (body.contains("no authentication key")) reason = "authentication_header_missing";
                else if (body.contains("access token should not be null or empty")) reason = "access_token_empty";
                else if (body.contains("this access token does not exist")) reason = "access_token_invalid";
                else if (body.contains("ip mismatched")) reason = "ip_restricted";
                else if (body.contains("wrong appKey")) reason = "app_key_format";
            }
        }
        return "code=" + code + " providerCode=" + providerCode + " cause=" + causeType + " reason=" + reason;
    }
}
