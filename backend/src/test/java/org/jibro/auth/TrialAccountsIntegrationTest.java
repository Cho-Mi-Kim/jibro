package org.jibro.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.jibro.global.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TrialAccountsIntegrationTest {
    @Autowired AuthService auth;
    @Autowired TrialAccounts trials;
    @Autowired JdbcTemplate db;
    @Autowired JwtDecoder decoder;

    @Test
    void guestsAreDistinctAndRefreshAndLogoutAffectOnlyTheirOwner() {
        var a = auth.startTrial(null);
        var b = auth.startTrial(null);
        assertTrue(a.user().trial());
        assertNotEquals(a.user().id(), b.user().id());
        assertEquals("TRIAL", decoder.decode(a.accessToken()).getClaimAsString("role"));
        var resumed = auth.startTrial(a.refresh());
        assertEquals(a.user().id(), resumed.user().id());
        auth.logout(a.refresh()); // A previously rotated token cannot terminate the active trial.
        decoder.decode(resumed.accessToken());
        auth.logout(resumed.refresh());
        assertThrows(JwtException.class, () -> decoder.decode(resumed.accessToken()));
        assertThrows(ApiException.class, () -> auth.refresh(resumed.refresh()));
        assertEquals(b.user().id(), auth.refresh(b.refresh()).user().id());
    }

    @Test
    void expirationRejectsAccessAndRefreshBeforeCleanupAndKeepsPermanentUsers() {
        var email = UUID.randomUUID() + "@example.invalid";
        auth.signup("일반 사용자", email, "test-password-123");
        var member = auth.login(email, "test-password-123");
        assertEquals(409, assertThrows(ApiException.class, () -> auth.startTrial(member.refresh())).status);
        var g = auth.startTrial(null);
        var id = UUID.fromString(g.user().id());
        db.update("UPDATE app_user SET trial_expires_at=now()-interval '1 second' WHERE id=?", id);
        assertThrows(JwtException.class, () -> decoder.decode(g.accessToken()));
        assertThrows(ApiException.class, () -> auth.refresh(g.refresh()));
        trials.removeExpired();
        assertEquals(0, db.queryForObject("SELECT count(*) FROM app_user WHERE id=?", Integer.class, id));
        assertFalse(auth.refresh(member.refresh()).user().trial());
    }
}
