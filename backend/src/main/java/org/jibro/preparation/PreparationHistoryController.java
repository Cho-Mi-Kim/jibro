package org.jibro.preparation;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/preparations/{preparationId}/history")
public class PreparationHistoryController {
    private final PreparationHistoryService history;

    public PreparationHistoryController(PreparationHistoryService history) {
        this.history = history;
    }

    @GetMapping
    PreparationHistoryService.History list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID preparationId) {
        return history.list(jwt.getSubject(), preparationId);
    }

    @GetMapping("/{version}")
    PreparationHistoryService.Detail get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID preparationId,
                                         @PathVariable int version) {
        return history.get(jwt.getSubject(), preparationId, version);
    }
}
