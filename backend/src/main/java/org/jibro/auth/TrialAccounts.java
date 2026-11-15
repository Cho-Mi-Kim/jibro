package org.jibro.auth;

import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TrialAccounts {
    private final TrialAccountRepository repository;

    public TrialAccounts(TrialAccountRepository repository) {
        this.repository = repository;
    }

    UUID create() {
        var id = UUID.randomUUID();
        repository.insert(id);
        return id;
    }

    // The caller holds the account row lock. Only accounts explicitly marked as trials are removable.
    void remove(UUID id) {
        if (repository.lockTrial(id)) repository.deleteTrialAndPrivateData(id);
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void removeExpired() {
        for (var id : repository.lockExpiredTrials())
            remove(id);
    }
}
