package org.jibro.notice;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.jibro.notice.HousingModels.Evidence;
import org.jibro.notice.HousingModels.Review;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/reviews")
public class NoticeLifecycleController {
    private final NoticeLifecycleService lifecycle;

    public NoticeLifecycleController(NoticeLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    public record Change(@Min(1) int revision, @NotBlank String state, @NotBlank @Size(max = 2000) String reason,
                         @NotNull Evidence evidence, @AssertTrue boolean officialConfirmed) {
    }

    @PatchMapping("/{id}/lifecycle")
    Review change(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Change body) {
        return lifecycle.change(id, UUID.fromString(jwt.getSubject()), body.revision(), body.state(), body.reason(),
                body.evidence(), body.officialConfirmed());
    }
}
