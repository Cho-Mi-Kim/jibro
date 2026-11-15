package org.jibro.auth;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

import org.jibro.global.common.ApiException;
import org.jibro.auth.AccountConnectionRepository.Intent;
import org.jibro.auth.AccountConnectionRepository.Owner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account ownership is proven separately from ownership of the new provider identity.
 */
@Service
public class AccountConnections {
    public record Provider(String id, boolean connected, boolean available, boolean canDisconnect) {
    }

    public record Overview(boolean passwordLogin, String mailMode, List<Provider> providers) {
    }

    public record Proof(String password, String code) {
    }

    private final AccountConnectionRepository repository;
    private final PasswordEncoder passwords;
    private final AccountMail mail;
    private final OAuthConfig config;

    public AccountConnections(AccountConnectionRepository repository, PasswordEncoder passwords, AccountMail mail, OAuthConfig config) {
        this.repository = repository;
        this.passwords = passwords;
        this.mail = mail;
        this.config = config;
    }

    public Overview overview(UUID user) {
        var owner = owner(user, false);
        var available = config.providers();
        var connected = repository.connectedProviders(user);
        return new Overview(owner.password() != null, mail.mode(), List.of("google", "kakao").stream()
            .map(p -> new Provider(p, connected.contains(p), available.contains(p), connected.contains(p) && hasRemainingSignIn(owner, connected, available, p))).toList());
    }

    @Transactional
    public void requestProof(UUID user) {
        var owner = owner(user, true);
        if (repository.hasRecentProof(user))
            throw new ApiException(429, "PROOF_RATE_LIMIT", "확인 코드는 1분 뒤 다시 요청할 수 있습니다.");
        if (mail.mode().equals("unavailable")) throw new ApiException(503, "MAIL_UNAVAILABLE", "확인 메일 발송 설정이 필요합니다.");
        String code = random();
        mail.send(owner.email(), "집으로 · 로그인 수단 변경 확인", "로그인 수단 연결 또는 해제를 요청했다면 아래 확인 코드를 입력해 주세요.\n\n" + code + "\n\n10분 동안 한 번만 사용할 수 있습니다. 직접 요청하지 않았다면 사용하지 마세요.");
        repository.saveProof(user, digest(code), owner.version(), Instant.now().plusSeconds(600));
    }

    // Failed proofs and rejected callbacks stay consumed; failures must not roll those writes back.
    @Transactional(noRollbackFor = ApiException.class)
    public UUID begin(UUID user, String provider, String binding, Proof proof) {
        requireProvider(provider);
        var owner = owner(user, true);
        authenticate(user, owner, proof);
        if (repository.hasProvider(user, provider))
            throw new ApiException(409, "ALREADY_CONNECTED", "이미 연결된 로그인 수단입니다.");
        invalidate(user);
        UUID intent = UUID.randomUUID();
        repository.insertIntent(intent, user, provider, digest(binding), owner.version(), Instant.now().plusSeconds(600));
        return intent;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void authorize(UUID intent, String provider, String binding, String state) {
        var row = intent(intent);
        var user = row.user();
        var owner = owner(user, true);
        if (!valid(row, owner, provider, binding) || row.stateHash() != null) {
            repository.deleteIntent(intent);
            throw invalidIntent();
        }
        if (!repository.bindIntentState(intent, digest(state)))
            throw invalidIntent();
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void complete(UUID intent, String provider, String subject, String binding, String state) {
        var row = intent(intent);
        var user = row.user();
        var owner = owner(user, true);
        // The user lock serializes callbacks, disconnects and password changes. A stale read cannot replay.
        boolean consumed = repository.deleteIntent(intent);
        if (!consumed || !valid(row, owner, provider, binding) || !Objects.equals(row.stateHash(), digest(state)))
            throw invalidIntent();
        if (subject == null || subject.isBlank() || subject.length() > 255) throw invalidIntent();
        if (!repository.insertIdentityIfAvailable(user, provider, subject))
            throw new ApiException(409, "IDENTITY_UNAVAILABLE", "이미 연결된 소셜 계정입니다. 다른 계정을 선택해 주세요.");
        invalidate(user);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void disconnect(UUID user, String provider, Proof proof) {
        requireProvider(provider);
        var owner = owner(user, true);
        authenticate(user, owner, proof);
        var identities = repository.connectedProviders(user);
        if (!identities.contains(provider)) throw ApiException.notFound();
        if (!hasRemainingSignIn(owner, identities, config.providers(), provider))
            throw new ApiException(409, "LAST_SIGN_IN", "계정에 다시 로그인할 수 있도록 다른 로그인 수단을 먼저 연결해 주세요.");
        repository.deleteIdentity(user, provider);
        invalidate(user);
        repository.invalidateCredentials(user);
    }

    private boolean hasRemainingSignIn(Owner owner, List<String> identities, List<String> available, String removed) {
        return owner.password() != null || identities.stream().anyMatch(p -> !p.equals(removed) && available.contains(p));
    }

    public void cancel(UUID intent) {
        repository.deleteIntent(intent);
    }

    private void invalidate(UUID user) {
        repository.deletePendingConnections(user);
    }

    private Owner owner(UUID user, boolean lock) {
        return repository.findOwner(user, lock)
            .orElseThrow(() -> new ApiException(403, "ACCOUNT_REQUIRED", "일반 계정으로 로그인해 주세요."));
    }

    private void authenticate(UUID user, Owner owner, Proof proof) {
        if (repository.isReauthenticationBlocked(user))
            throw new ApiException(429, "REAUTHENTICATION_LIMIT", "본인 확인을 여러 번 실패했습니다. 15분 뒤 다시 시도해 주세요.");
        repository.deleteExpiredReauthenticationBlock(user);
        if (proof != null && proof.password != null && !proof.password.isBlank()) {
            if (owner.password() != null && proof.password.getBytes(StandardCharsets.UTF_8).length <= 72 && passwords.matches(proof.password, owner.password())) {
                repository.clearReauthenticationFailures(user);
                return;
            }
        } else if (proof != null && proof.code != null && !proof.code.isBlank() && proof.code.length() <= 100) {
            var hash = repository.findUsableProofHash(user, owner.version());
            if (hash.isPresent() && MessageDigest.isEqual(hash.get().getBytes(StandardCharsets.UTF_8), digest(proof.code.strip()).getBytes(StandardCharsets.UTF_8))) {
                repository.acceptEmailProof(user);
                return;
            }
            repository.incrementProofFailures(user);
        }
        repository.recordReauthenticationFailure(user);
        throw new ApiException(400, "REAUTHENTICATION_REQUIRED", "현재 비밀번호 또는 이메일 확인 코드를 다시 확인해 주세요.");
    }

    private Intent intent(UUID id) {
        return repository.findIntent(id).orElseThrow(AccountConnections::invalidIntent);
    }

    private boolean valid(Intent row, Owner owner, String provider, String binding) {
        return Objects.equals(row.provider(), provider) && Objects.equals(row.bindingHash(), digest(binding))
            && row.version() == owner.version() && row.expiresAt().isAfter(Instant.now());
    }

    private static void requireProvider(String provider) {
        if (!List.of("google", "kakao").contains(provider)) throw ApiException.notFound();
    }

    static ApiException invalidIntent() {
        return new ApiException(400, "INVALID_CONNECTION", "연결 요청이 만료되었거나 유효하지 않습니다. 계정 설정에서 다시 시작해 주세요.");
    }

    static String random() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
