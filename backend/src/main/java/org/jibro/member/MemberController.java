package org.jibro.member;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/member")
public class MemberController {
    private final SavedNoticeService saved;
    private final AlertService alerts;
    private final ExperienceService experience;

    public MemberController(SavedNoticeService saved, AlertService alerts, ExperienceService experience) {
        this.saved = saved;
        this.alerts = alerts;
        this.experience = experience;
    }

    private UUID owner(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }

    @GetMapping("/saved")
    List<SavedNoticeService.Saved> saved(@AuthenticationPrincipal Jwt jwt) {
        return saved.list(owner(jwt));
    }

    @PutMapping("/saved/{id}")
    void save(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        saved.set(owner(jwt), id, true);
    }

    @DeleteMapping("/saved/{id}")
    void unsave(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        saved.set(owner(jwt), id, false);
    }

    @GetMapping("/alerts")
    AlertService.Inbox alerts(@AuthenticationPrincipal Jwt jwt) {
        return alerts.inbox(owner(jwt));
    }

    @PutMapping("/alert-settings")
    void settings(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AlertService.Settings settings) {
        alerts.configure(owner(jwt), settings);
    }

    public record Read(@NotNull @Size(max = 1000) List<@NotBlank @Size(max = 260) String> keys) {
    }

    @PutMapping("/alerts/read")
    void read(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Read read) {
        alerts.read(owner(jwt), read.keys());
    }

    @GetMapping("/archive")
    List<ExperienceService.Entry> archive(@AuthenticationPrincipal Jwt jwt) {
        return experience.list(owner(jwt));
    }

    public record Note(@NotNull @Size(max = 500) String note, @Min(1) int revision) {
    }

    @PutMapping("/archive/{id}")
    void archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Note note) {
        experience.archive(owner(jwt), id, note.note(), note.revision());
    }

    @DeleteMapping("/archive/{id}")
    void restore(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        experience.restore(owner(jwt), id);
    }

    @GetMapping("/preparations/{id}/references")
    List<ExperienceService.Entry> refs(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return experience.references(owner(jwt), id);
    }

    @PutMapping("/preparations/{id}/references/{source}")
    void reuse(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID source) {
        experience.attach(owner(jwt), id, source);
    }
}
