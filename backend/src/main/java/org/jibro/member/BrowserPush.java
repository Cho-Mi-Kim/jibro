package org.jibro.member;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import org.jibro.global.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BrowserPush {
    private final ECKey key;
    private final String publicKey;
    private final String subject;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    public BrowserPush(@Value("${JIBRO_VAPID_JWK:}") String jwk, @Value("${JIBRO_PUSH_SUBJECT:}") String subject) throws Exception {
        this.subject = subject;
        if (jwk.isBlank() || subject.isBlank()) {
            key = null;
            publicKey = null;
            return;
        }
        key = ECKey.parse(jwk);
        if (!Curve.P_256.equals(key.getCurve()) || !key.isPrivate() || !(subject.startsWith("mailto:") || subject.startsWith("https://")))
            throw new IllegalStateException("Invalid VAPID configuration");
        byte[] point = new byte[65];
        point[0] = 4;
        System.arraycopy(key.getX().decode(), 0, point, 1, 32);
        System.arraycopy(key.getY().decode(), 0, point, 33, 32);
        publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(point);
    }

    public String publicKey() {
        return publicKey;
    }

    public static URI endpoint(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        String host = uri.getHost();
        if (host == null || !"https".equals(uri.getScheme()) || uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() != -1 && uri.getPort() != 443)
            throw invalid();
        host = host.toLowerCase(Locale.ROOT);
        if (!(host.equals("fcm.googleapis.com") || host.equals("web.push.apple.com") || host.endsWith(".push.apple.com") || host.equals("updates.push.services.mozilla.com") || host.endsWith(".push.services.mozilla.com") || host.endsWith(".notify.windows.com")))
            throw invalid();
        return uri;
    }

    private static ApiException invalid() {
        return new ApiException(400, "INVALID_PUSH", "지원하는 브라우저의 푸시 구독 주소가 필요합니다.");
    }

    public int send(String device) throws Exception {
        if (key == null) throw new IllegalStateException("Push unavailable");
        URI uri = endpoint(device);
        var claims = new JWTClaimsSet.Builder().audience("https://" + uri.getHost()).subject(subject).expirationTime(Date.from(Instant.now().plusSeconds(3600))).build();
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).build(), claims);
        token.sign(new ECDSASigner(key));
        // A payload-free push keeps the notice, account and deadline out of the provider and lock screen.
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).header("TTL", "3600").header("Authorization", "vapid t=" + token.serialize() + ", k=" + publicKey)
            .POST(HttpRequest.BodyPublishers.noBody()).build();
        return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
