package org.jibro.member;

import java.time.Instant;
import java.util.*;

import org.jibro.global.common.ApiException;
import org.jibro.notice.HousingModels.FollowUp;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExperienceService {
    public record Entry(UUID id, String title, int version, int progress, int houseStage, String note,
                        Instant archivedAt, List<ExperienceRepository.ArchivedSchedule> schedules, FollowUp followUp) {
    }

    private final ExperienceRepository repository;

    public ExperienceService(ExperienceRepository repository) {
        this.repository = repository;
    }

    private Entry view(ExperienceRepository.Entry entry) {
        return new Entry(entry.id(), entry.title(), entry.version(), entry.progress(), entry.houseStage(), entry.note(), entry.archivedAt(), entry.schedules(), entry.followUp());
    }

    public List<Entry> list(UUID user) {
        return repository.findArchived(user).stream().map(this::view).toList();
    }

    @Transactional
    public void archive(UUID user, UUID id, String note, int revision) {
        var current = repository.lockArchiveState(user, id).orElseThrow(ApiException::notFound);
        if (current.archived() || current.revision() != revision) throw ApiException.conflict();
        repository.archive(id, note.strip());
    }

    @Transactional
    public void restore(UUID user, UUID id) {
        var current = repository.lockArchiveState(user, id).orElseThrow(ApiException::notFound);
        if (!current.archived()) return;
        repository.restore(id);
        repository.removeReferences(id);
    }

    public List<Entry> references(UUID user, UUID target) {
        return repository.findReferences(user, target).stream().map(this::view).toList();
    }

    @Transactional
    public void attach(UUID user, UUID target, UUID source) {
        var rows = repository.lockLinkTargets(user, target, source);
        if (rows.size() != 2) throw ApiException.notFound();
        for (var row : rows)
            if (row.id().equals(source) != row.archived()) throw ApiException.conflict();
        repository.attach(target, source);
    }
}
