package org.jibro.member;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jibro.global.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SavedNoticeService {
    public record Saved(UUID id, String title, String agency, String region, LocalDate endDate) {
    }

    private final SavedNoticeRepository repository;

    public SavedNoticeService(SavedNoticeRepository repository) {
        this.repository = repository;
    }

    public List<Saved> list(UUID user) {
        return repository.findOwned(user).stream().map(value -> new Saved(value.id(), value.title(), value.agency(), value.region(), value.endDate())).toList();
    }

    @Transactional
    public void set(UUID user, UUID notice, boolean saved) {
        if (!saved) {
            repository.remove(user, notice);
            return;
        }
        var version = repository.lockPublicVersion(notice).orElseThrow(ApiException::notFound);
        repository.save(user, notice, version);
    }
}
