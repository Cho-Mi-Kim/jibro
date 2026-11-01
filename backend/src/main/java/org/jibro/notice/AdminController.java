package org.jibro.notice;

import static org.jibro.notice.HousingModels.*;

import java.util.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final NoticeService notices;

    public AdminController(NoticeService notices) {
        this.notices = notices;
    }

    public record ReviewUpdate(@Min(1) int revision, @NotBlank String basic, @NotBlank String guide,
                               @NotBlank @Size(max = 2000) String reason) {
    }

    public record ContentUpdate(@Min(1) int revision, @NotNull Content content) {
    }

    public record DraftUpdate(@Min(1) int revision, @NotNull NoticeService.Draft draft) {
    }

    @GetMapping("/reviews")
    List<Review> reviews() {
        return notices.reviews();
    }

    @PatchMapping("/reviews/{id}")
    Review review(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ReviewUpdate body) {
        return notices.review(id, jwt.getSubject(), body.revision(), body.basic(), body.guide(), body.reason());
    }

    @GetMapping("/reviews/{id}/content")
    Content content(@PathVariable UUID id) {
        return notices.content(id);
    }

    @PutMapping("/reviews/{id}/content")
    Review content(@PathVariable UUID id, @Valid @RequestBody ContentUpdate body) {
        return notices.updateContent(id, body.revision(), body.content());
    }

    @GetMapping("/reviews/{id}/draft")
    NoticeService.Draft draft(@PathVariable UUID id) {
        return notices.draft(id);
    }

    @PutMapping("/reviews/{id}/draft")
    Review draft(@PathVariable UUID id, @Valid @RequestBody DraftUpdate body) {
        return notices.updateDraft(id, body.revision(), body.draft());
    }
}
