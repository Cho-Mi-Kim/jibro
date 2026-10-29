package org.jibro.preparation;

import static org.jibro.notice.HousingModels.*;

import java.util.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/preparations")
public class PreparationController {
    private final PreparationService service;

    public PreparationController(PreparationService service) {
        this.service = service;
    }

    public record Start(@NotNull UUID noticeId, @Min(1) int version) {
    }

    public record Answers(@Min(1) int revision, @NotNull Map<String, Answer> answers) {
    }

    public record DocumentUpdate(@Min(1) int revision, @NotNull DocumentState state,
                                 @NotNull @Size(max = 2000) String note) {
    }

    public record ScheduleUpdate(@Min(1) int revision, @NotNull Boolean recorded) {
    }

    @GetMapping
    List<Preparation> list(@AuthenticationPrincipal Jwt jwt) {
        return service.list(jwt.getSubject());
    }

    @GetMapping("/{id}")
    Preparation get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return service.get(jwt.getSubject(), id);
    }

    @PostMapping
    Preparation start(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Start body) {
        return service.start(jwt.getSubject(), body.noticeId(), body.version());
    }

    @PutMapping("/{id}/answers")
    Preparation answers(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Answers body) {
        return service.answers(jwt.getSubject(), id, body.revision(), body.answers());
    }

    @PatchMapping("/{id}/documents/{documentId}")
    Preparation document(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable String documentId, @Valid @RequestBody DocumentUpdate body) {
        return service.document(jwt.getSubject(), id, documentId, body.revision(), body.state(), body.note());
    }

    @PatchMapping("/{id}/schedules/{scheduleId}")
    Preparation schedule(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable String scheduleId, @Valid @RequestBody ScheduleUpdate body) {
        return service.schedule(jwt.getSubject(), id, scheduleId, body.revision(), body.recorded());
    }
}
