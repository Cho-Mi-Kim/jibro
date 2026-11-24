package org.jibro.collection;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/collections/{runId}/responses")
public class CollectionResponsesController {
    private final CollectionResponseService service;

    public CollectionResponsesController(CollectionResponseService service) {
        this.service = service;
    }

    @GetMapping
    List<CollectionResponseRepository.Receipt> list(@PathVariable UUID runId) {
        return service.list(runId);
    }

    @GetMapping("/{responseId}")
    CollectionResponseRepository.ArchivedResponse find(@PathVariable UUID runId, @PathVariable UUID responseId) {
        return service.find(runId, responseId);
    }
}
