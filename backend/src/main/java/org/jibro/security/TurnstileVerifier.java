package org.jibro.security;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;

import org.jibro.global.common.ApiException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class TurnstileVerifier {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Semaphore pending = new Semaphore(4);
    private final ObjectMapper json;
    private final VisitorVerification settings;

    public TurnstileVerifier(ObjectMapper json, VisitorVerification settings) {
        this.json = json;
        this.settings = settings;
    }

    public void verify(String token) {
        if (!pending.tryAcquire()) throw new ApiException(429, "VERIFICATION_BUSY", "잠시 후 다시 확인해 주세요.");
        try {
            var request = HttpRequest.newBuilder(URI.create("https://challenges.cloudflare.com/turnstile/v0/siteverify"))
                    .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("secret=" + encode(settings.secretKey) + "&response=" + encode(token))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw unavailable();
            validate(response.body());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (java.io.IOException failure) {
            throw unavailable();
        } finally {
            pending.release();
        }
    }

    void validate(String body) {
        try {
            var result = json.readTree(body);
            if (!result.path("success").asBoolean(false)
                    || !settings.hostname.equalsIgnoreCase(result.path("hostname").asString())
                    || !VisitorVerification.ACTION.equals(result.path("action").asString()))
                throw new ApiException(403, "HUMAN_VERIFICATION_FAILED", "사람 확인을 다시 진행해 주세요.");
        } catch (tools.jackson.core.JacksonException failure) {
            throw unavailable();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static ApiException unavailable() {
        return new ApiException(503, "VERIFICATION_UNAVAILABLE", "보안 확인 서비스에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
    }
}
