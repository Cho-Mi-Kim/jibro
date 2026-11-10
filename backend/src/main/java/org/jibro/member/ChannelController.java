package org.jibro.member;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/member/channels")
public class ChannelController {
    private final ReminderDelivery delivery;

    public ChannelController(ReminderDelivery delivery) {
        this.delivery = delivery;
    }

    public record Email(boolean enabled) {
    }

    public record Device(@NotBlank @Size(max = 2000) String endpoint) {
    }

    @GetMapping
    ReminderDelivery.Channels get(@AuthenticationPrincipal Jwt jwt) {
        return delivery.channels(UUID.fromString(jwt.getSubject()));
    }

    @PutMapping("/email")
    void email(@AuthenticationPrincipal Jwt jwt, @RequestBody Email body) {
        delivery.email(UUID.fromString(jwt.getSubject()), body.enabled());
    }

    @PutMapping("/push")
    void push(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Device body) {
        delivery.subscribe(UUID.fromString(jwt.getSubject()), body.endpoint());
    }

    @PostMapping("/push/status")
    boolean subscribed(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Device body) {
        return delivery.subscribed(UUID.fromString(jwt.getSubject()), body.endpoint());
    }

    @PostMapping("/push/remove")
    void remove(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Device body) {
        delivery.unsubscribe(UUID.fromString(jwt.getSubject()), body.endpoint());
    }
}
