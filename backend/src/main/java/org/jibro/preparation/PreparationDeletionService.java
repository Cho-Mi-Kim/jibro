package org.jibro.preparation;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

import org.jibro.auth.AccountMail;
import org.jibro.global.common.ApiException;
import org.jibro.preparation.PreparationDeletionRepository.Owner;
import org.jibro.preparation.PreparationDeletionRepository.Preparation;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Destruction has its own owner/revision-bound proof; account deletion tickets are never accepted. */
@Service
public class PreparationDeletionService {
    public record Details(String title, int revision, boolean trial, boolean passwordLogin, String mailMode) {}
    public record Confirmation(int revision, String confirmation, String password, String code) {}
    private final PreparationDeletionRepository repository;
    private final PasswordEncoder passwords;
    private final AccountMail mail;

    public PreparationDeletionService(PreparationDeletionRepository repository, PasswordEncoder passwords, AccountMail mail) {
        this.repository = repository;
        this.passwords = passwords;
        this.mail = mail;
    }

    @Transactional(readOnly = true)
    public Details details(UUID user, UUID preparation) {
        var owner = owner(user, false);
        var row = preparation(user, preparation, false);
        return new Details(row.title(), row.revision(), owner.trial(), owner.password() != null, mail.mode());
    }

    @Transactional
    public void requestProof(UUID user, UUID preparation, int revision) {
        var owner = owner(user, true);
        checkRevision(preparation(user, preparation, true), revision);
        if (owner.trial()) throw new ApiException(400, "PROOF_NOT_REQUIRED", "이 준비 건은 삭제 확인만으로 삭제할 수 있습니다.");
        if (repository.hasRecentProof(user))
            throw new ApiException(429, "PROOF_RATE_LIMIT", "확인 코드는 1분 뒤 다시 요청할 수 있습니다.");
        if (mail.mode().equals("unavailable")) throw new ApiException(503, "MAIL_UNAVAILABLE", "확인 메일 발송 설정이 필요합니다.");
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        mail.send(owner.email(), "집으로 · 준비 기록 삭제 확인", "선택한 준비 기록의 영구 삭제를 요청했다면 아래 확인 코드를 입력해 주세요.\n\n" + code + "\n\n10분 동안 선택한 준비 기록에 한 번만 사용할 수 있습니다. 기록이 변경되면 다시 요청해 주세요.");
        repository.removeExpiredProofs(user);
        repository.saveProof(preparation, user, revision, owner.version(), digest(code), Instant.now().plusSeconds(600));
    }

    // A failed password/code check must retain its attempt count after the error response.
    @Transactional(noRollbackFor = ApiException.class)
    public void delete(UUID user, UUID preparation, Confirmation confirmation) {
        if (confirmation == null || !"준비 삭제".equals(confirmation.confirmation))
            throw new ApiException(400, "DELETE_CONFIRMATION_REQUIRED", "삭제 확인 문구를 정확히 입력해 주세요.");
        var owner = owner(user, true);
        checkRevision(preparation(user, preparation, true), confirmation.revision);
        if (!owner.trial()) authenticate(user, preparation, owner, confirmation);
        // FK cascades remove saved historical answers and both directions of experience links.
        // The notice, other preparations, and bookmarks have no cascading edge from this row.
        if (!repository.deleteOwnedRevision(user, preparation, confirmation.revision))
            throw ApiException.conflict();
    }

    private void authenticate(UUID user, UUID preparation, Owner owner, Confirmation confirmation) {
        if (repository.isBlocked(user))
            throw new ApiException(429, "REAUTHENTICATION_LIMIT", "본인 확인을 여러 번 실패했습니다. 15분 뒤 다시 시도해 주세요.");
        repository.removeExpiredAttempts(user);
        boolean valid = false;
        if (confirmation.password != null && !confirmation.password.isBlank()) {
            valid = owner.password() != null && confirmation.password.getBytes(StandardCharsets.UTF_8).length <= 72 && passwords.matches(confirmation.password, owner.password());
        } else if (confirmation.code != null && !confirmation.code.isBlank() && confirmation.code.length() <= 100) {
            var hashes = repository.findValidProofHashes(user, preparation, confirmation.revision, owner.version());
            valid = !hashes.isEmpty() && MessageDigest.isEqual(hashes.get(0).getBytes(StandardCharsets.UTF_8), digest(confirmation.code.strip()).getBytes(StandardCharsets.UTF_8));
        }
        if (valid) {
            repository.clearAttempts(user);
            repository.removeProof(preparation);
            return;
        }
        repository.recordFailedAttempt(user);
        throw new ApiException(400, "REAUTHENTICATION_REQUIRED", "현재 비밀번호 또는 삭제 확인 코드를 다시 확인해 주세요.");
    }

    private Owner owner(UUID user, boolean lock) {
        return (lock ? repository.lockOwner(user) : repository.findOwner(user)).orElseThrow(ApiException::notFound);
    }

    private Preparation preparation(UUID user, UUID id, boolean lock) {
        return (lock ? repository.lockOwned(user, id) : repository.findOwned(user, id)).orElseThrow(ApiException::notFound);
    }

    private void checkRevision(Preparation row, int revision) {
        if (row.revision() != revision) throw ApiException.conflict();
    }

    private String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
