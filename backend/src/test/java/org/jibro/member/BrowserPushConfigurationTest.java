package org.jibro.member;

import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;

import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BrowserPushConfigurationTest {
    @Test
    void derivesOnlyPublicSubscriptionKeyWithoutSendingPush() throws Exception {
        var privateKey = new ECKeyGenerator(Curve.P_256).generate();
        var push = new BrowserPush(privateKey.toJSONString(), "mailto:verification@example.invalid");
        byte[] publicPoint = Base64.getUrlDecoder().decode(push.publicKey());
        assertEquals(65, publicPoint.length);
        assertEquals(4, publicPoint[0]);
        assertArrayEquals(privateKey.getX().decode(), java.util.Arrays.copyOfRange(publicPoint, 1, 33));
        assertArrayEquals(privateKey.getY().decode(), java.util.Arrays.copyOfRange(publicPoint, 33, 65));
        assertNull(new BrowserPush("", "").publicKey());
    }

    @Test
    void rejectsPartialCredentialsAndMalformedContactUris() throws Exception {
        String key = new ECKeyGenerator(Curve.P_256).generate().toJSONString();
        for (String subject : new String[]{"", "mailto:", "mailto:not-an-email", "https://", "https://user:password@example.invalid"})
            assertThrows(IllegalStateException.class, () -> new BrowserPush(key, subject));
        assertThrows(IllegalStateException.class, () -> new BrowserPush("", "mailto:operator@example.invalid"));
        assertThrows(IllegalStateException.class, () -> new BrowserPush("not-a-private-key", "mailto:operator@example.invalid"));
    }
}
