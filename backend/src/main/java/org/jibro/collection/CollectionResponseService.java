package org.jibro.collection;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jibro.global.common.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectionResponseService {
    private final CollectionResponseRepository repository;

    public CollectionResponseService(CollectionResponseRepository repository) { this.repository = repository; }

    @Transactional
    public UUID begin(CollectionRunStore.Work work, int page, CollectionQueryPlan.Query query) { return repository.begin(work, page, query, Instant.now()); }

    @Transactional
    public void complete(UUID id, SourceResponse response, String outcome) { repository.complete(id, response, outcome, Instant.now()); }

    public List<CollectionResponseRepository.Receipt> list(UUID run) { return repository.list(run); }

    public CollectionResponseRepository.ArchivedResponse find(UUID run, UUID id) {
        return repository.find(run, id).orElseThrow(ApiException::notFound);
    }
}
