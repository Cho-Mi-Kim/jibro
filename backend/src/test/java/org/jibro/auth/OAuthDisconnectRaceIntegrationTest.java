package org.jibro.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;

import org.jibro.global.common.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Uses committed fixtures and independent transactions, not an enclosing rollback-only test.
 */
@SpringBootTest
@ActiveProfiles("test")
class OAuthDisconnectRaceIntegrationTest {
    @Autowired
    AuthService auth;
    @Autowired
    AccountConnections connections;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    JdbcTemplate db;
    @MockitoSpyBean
    AuthRepository repository;
    private static final String PASSWORD = "race-test-password-123";

    @Test
    void loginThatReadTheIdentityBeforeDisconnectCannotIssueNewTokensAfterDisconnectCommits() throws Exception {
        String email = UUID.randomUUID() + "@example.invalid", subject = "race-" + UUID.randomUUID();
        var setup = new TransactionTemplate(transactions);
        UUID user = setup.execute(status -> {
            auth.signup("동시성 테스트", email, PASSWORD);
            var tokens = auth.login(email, PASSWORD);
            UUID id = UUID.fromString(tokens.user().id());
            db.update("INSERT INTO login_identity(provider,subject,user_id) VALUES('google',?,?)", subject, id);
            return id;
        });
        var identityRead = new CountDownLatch(1);
        var continueLogin = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try {
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                assertEquals(Optional.of(user), result);
                identityRead.countDown();
                if (!continueLogin.await(15, TimeUnit.SECONDS))
                    throw new IllegalStateException("Test callback did not resume");
                return result;
            }).when(repository).findIdentityOwner("google", subject);
            var login = worker.submit(() -> auth.social("google", subject, "동시성 테스트", email));
            assertTrue(identityRead.await(10, TimeUnit.SECONDS));
            connections.disconnect(user, "google", new AccountConnections.Proof(PASSWORD, null));
            continueLogin.countDown();
            var error = assertThrows(ExecutionException.class, () -> login.get(10, TimeUnit.SECONDS));
            assertInstanceOf(ApiException.class, error.getCause());
            assertEquals(401, ((ApiException) error.getCause()).status);
            assertEquals(0, db.queryForObject("SELECT count(*) FROM refresh_token WHERE user_id=?", Integer.class, user));
        } finally {
            continueLogin.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
            remove(user);
        }
    }

    @Test
    void matchingEmailNeverAutomaticallyMergesAnUnlinkedProvider() {
        String email = UUID.randomUUID() + "@example.invalid", subject = "unlinked-" + UUID.randomUUID();
        auth.signup("이메일 일치", email, PASSWORD);
        UUID user = UUID.fromString(auth.login(email, PASSWORD).user().id());
        try {
            assertThrows(DataIntegrityViolationException.class, () -> auth.social("google", subject, "다른 계정", email));
            assertEquals(0, db.queryForObject("SELECT count(*) FROM login_identity WHERE subject=?", Integer.class, subject));
            assertEquals(1, db.queryForObject("SELECT count(*) FROM app_user WHERE email=?", Integer.class, email));
        } finally {
            remove(user);
        }
    }

    private void remove(UUID user) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            db.update("DELETE FROM login_identity WHERE user_id=?", user);
            db.update("DELETE FROM refresh_token WHERE user_id=?", user);
            db.update("DELETE FROM app_user WHERE id=?", user);
        });
    }
}
