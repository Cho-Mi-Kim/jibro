package org.jibro.auth;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

import org.jibro.global.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountService {
    public record Profile(String name, String email, String region, boolean verified, boolean passwordLogin,
                          String mailMode) {
    }

    private final AccountRepository repository;
    private final PasswordEncoder encoder;
    private final AccountMail mail;
    private final String frontend;
    private final SecureRandom random = new SecureRandom();
    private final LoginAttemptLimiter attempts;
    private final TransactionTemplate passwordTransaction;

    public AccountService(AccountRepository repository, PasswordEncoder encoder, AccountMail mail, @Value("${app.auth.frontend-url}") String frontend,
                          LoginAttemptLimiter attempts, PlatformTransactionManager transactions) {
        this.repository = repository;
        this.encoder = encoder;
        this.mail = mail;
        this.frontend = frontend.replaceAll("/+$", "");
        this.attempts = attempts;
        this.passwordTransaction = new TransactionTemplate(transactions);
    }

    public Profile profile(UUID id) {
        var profile = repository.findProfile(id).orElseThrow(ApiException::notFound);
        return new Profile(profile.name(), profile.email(), profile.region(), profile.verified(), profile.passwordLogin(), mail.mode());
    }

    @Transactional
    public Profile update(UUID id, String name, String region) {
        name = name.strip();
        if (name.length() < 2 || name.length() > 30) throw invalid();
        repository.updateProfile(id, name, region.strip());
        return profile(id);
    }

    private static ApiException invalid() {
        return new ApiException(400, "INVALID_INPUT", "입력한 값을 다시 확인해 주세요.");
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void validatePassword(String password) {
        if (password.length() < 10 || password.getBytes(StandardCharsets.UTF_8).length > 72) throw invalid();
    }

    private void changeSecret(UUID id, String password) {
        validatePassword(password);
        repository.replacePasswordAndInvalidateCredentials(id, encoder.encode(password));
    }

    public void password(UUID id, String oldValue, String newValue, String remoteAddress) {
        validatePassword(newValue);
        String email = repository.findProfile(id).orElseThrow(ApiException::notFound).email();
        UUID attempt = attempts.begin(email, remoteAddress, LoginAttemptLimiter.Purpose.REAUTHENTICATION);
        boolean successful = false;
        try {
            passwordTransaction.executeWithoutResult(status -> {
                String hash = repository.lockPasswordCredential(id).map(AccountRepository.PasswordCredential::hash).orElse(null);
                if (hash == null || !encoder.matches(oldValue, hash))
                    throw new ApiException(400, "BAD_PASSWORD", "현재 비밀번호가 일치하지 않습니다.");
                changeSecret(id, newValue);
            });
            successful = true;
        } finally {
            // This commits independently after a failed credential transaction has rolled back.
            attempts.finish(attempt, successful);
        }
    }

    @Transactional
    public void requestReset(String email) {
        requireMail();
        repository.lockPasswordAccountByEmail(email.strip().toLowerCase(Locale.ROOT)).ifPresent(id -> issue(id, "reset"));
    }

    private void requireMail() {
        if (mail.mode().equals("unavailable")) throw new ApiException(503, "MAIL_UNAVAILABLE", "메일 발송 설정이 필요합니다.");
    }

    @Transactional
    public void issue(UUID id, String purpose) {
        requireMail();
        String email = repository.lockEmail(id).orElseThrow(ApiException::notFound);
        if (repository.hasRecentTicket(id, purpose))
            return;
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String label = switch (purpose) {
            case "reset" -> "비밀번호 재설정";
            case "verify" -> "이메일 확인";
            case "delete" -> "회원 탈퇴 확인";
            default -> throw invalid();
        };
        // A delivery failure must not distinguish a registered address at the recovery endpoint.
        try {
            mail.send(email, "집으로 · " + label, frontend + "/account/confirm#" + purpose + "=" + token + "\n\n30분 동안 한 번만 사용할 수 있습니다. 직접 요청했다면 페이지에서 확인해 주세요.");
        } catch (RuntimeException ex) {
            if (purpose.equals("reset")) return;
            throw new ApiException(503, "MAIL_FAILED", "메일을 준비하지 못했습니다. 잠시 후 다시 시도해 주세요.");
        }
        repository.saveTicket(id, purpose, digest(token), Instant.now().plusSeconds(1800));
    }

    private UUID consume(String purpose, String token, UUID requiredOwner) {
        UUID id = repository.findTicketOwner(purpose, digest(token)).orElseThrow(this::badLink);
        if (requiredOwner != null && !requiredOwner.equals(id)) throw badLink();
        if (!repository.lockActiveAccount(id))
            throw badLink();
        if (!repository.consumeTicket(id, purpose, digest(token)))
            throw badLink();
        return id;
    }

    private ApiException badLink() {
        return new ApiException(400, "INVALID_LINK", "만료되었거나 이미 사용된 링크입니다. 새 링크를 요청해 주세요.");
    }

    @Transactional
    public void reset(String token, String password) {
        changeSecret(consume("reset", token, null), password);
    }

    @Transactional
    public void verify(String token) {
        repository.markEmailVerified(consume("verify", token, null));
    }

    @Transactional
    public void delete(UUID user, String token, String confirmation) {
        if (!"회원 탈퇴".equals(confirmation)) throw invalid();
        consume("delete", token, user);
        repository.anonymizeAccountAndDeletePrivateData(user);
    }
}
