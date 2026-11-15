package org.jibro.notice;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/reviews/{id}/evidence-checks")
public class EvidenceChecksController {
    public record Request(@Min(1) int revision) {
    }

    private final EvidenceCheckService checks;

    public EvidenceChecksController(EvidenceCheckService checks) {
        this.checks = checks;
    }

    @PostMapping
    EvidenceCheckService.Report check(@PathVariable UUID id, @Valid @RequestBody Request request) {
        return checks.check(id, request.revision());
    }
}
