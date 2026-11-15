package org.jibro.preparation;

import java.util.List;
import java.util.UUID;
import org.jibro.global.common.ApiException;
import org.jibro.notice.HousingModels.Preparation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PreparationHistoryService {
    private final PreparationHistoryRepository repository;

    public PreparationHistoryService(PreparationHistoryRepository repository) {
        this.repository = repository;
    }

    public record Entry(int version, String savedAt) {
    }

    public record History(String preparationId, String title, boolean archived, List<Entry> entries) {
    }

    public record Detail(int version, String savedAt, Preparation preparation) {
    }

    public History list(String user, UUID preparationId) {
        // Finished journeys retain the same ownership; they are not public archives.
        var owner = repository.findOwner(UUID.fromString(user), preparationId).orElseThrow(ApiException::notFound);
        var entries = repository.findEntries(preparationId).stream().map(entry -> new Entry(entry.version(), entry.savedAt())).toList();
        return new History(preparationId.toString(), owner.title(), owner.archived(), entries);
    }

    public Detail get(String user, UUID preparationId, int version) {
        var detail = repository.findOwnedVersion(UUID.fromString(user), preparationId, version).orElseThrow(ApiException::notFound);
        // Never decorate or assess this snapshot using today's notice, options or rules.
        return new Detail(detail.version(), detail.savedAt(), detail.snapshot().preparation());
    }
}
