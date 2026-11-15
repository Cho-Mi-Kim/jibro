package org.jibro.preparation;

import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/preparations/{id}")
public class PreparationDeletionController {
    private final PreparationDeletionService service;
    public PreparationDeletionController(PreparationDeletionService service) { this.service = service; }
    public record ProofRequest(@Min(1) int revision) {}
    public record DeleteRequest(@Min(1) int revision, @NotBlank @Size(max=30) String confirmation,
                                @Size(max=72) String password, @Size(max=100) String code) {}

    @GetMapping("/deletion")
    PreparationDeletionService.Details details(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.details(UUID.fromString(jwt.getSubject()), id);
    }

    @PostMapping("/deletion-proof")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void proof(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody ProofRequest body) {
        service.requestProof(UUID.fromString(jwt.getSubject()), id, body.revision());
    }

    @DeleteMapping
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody DeleteRequest body) {
        service.delete(UUID.fromString(jwt.getSubject()), id, new PreparationDeletionService.Confirmation(body.revision(), body.confirmation(), body.password(), body.code()));
    }
}
