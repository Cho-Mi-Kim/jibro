package org.jibro.auth;

import java.util.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AccountController {
    private final AccountService service;
    private final AccountMail mail;

    public AccountController(AccountService service, AccountMail mail) {
        this.service = service;
        this.mail = mail;
    }

    public record Edit(@NotBlank @Size(max = 30) String name, @NotNull @Size(max = 50) String region) {
    }

    public record Password(@NotBlank @Size(max = 72) String current,
                           @NotBlank @Size(min = 10, max = 72) String password) {
    }

    public record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
    }

    public record Ticket(@NotBlank @Size(max = 100) String token) {
    }

    public record Reset(@NotBlank @Size(max = 100) String token, @NotBlank @Size(min = 10, max = 72) String password) {
    }

    public record Delete(@NotBlank @Size(max = 100) String token, @NotBlank String confirmation) {
    }

    @GetMapping("/auth/mail-mode")
    Map<String, String> mode() {
        return Map.of("mode", mail.mode());
    }

    @GetMapping("/member/profile")
    AccountService.Profile profile(@AuthenticationPrincipal Jwt jwt) {
        return service.profile(UUID.fromString(jwt.getSubject()));
    }

    @PutMapping("/member/profile")
    AccountService.Profile edit(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Edit body) {
        return service.update(UUID.fromString(jwt.getSubject()), body.name(), body.region());
    }

    @PutMapping("/member/password")
    void password(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Password body) {
        service.password(UUID.fromString(jwt.getSubject()), body.current(), body.password());
    }

    @PostMapping("/auth/reset/request")
    void request(@Valid @RequestBody EmailRequest body) {
        service.requestReset(body.email());
    }

    @PostMapping("/auth/reset/complete")
    void reset(@Valid @RequestBody Reset body) {
        service.reset(body.token(), body.password());
    }

    @PostMapping("/member/verify")
    void requestVerify(@AuthenticationPrincipal Jwt jwt) {
        service.issue(UUID.fromString(jwt.getSubject()), "verify");
    }

    @PostMapping("/auth/verify")
    void verify(@Valid @RequestBody Ticket body) {
        service.verify(body.token());
    }

    @PostMapping("/member/closure/request")
    void requestDelete(@AuthenticationPrincipal Jwt jwt) {
        service.issue(UUID.fromString(jwt.getSubject()), "delete");
    }

    @PostMapping("/member/closure/complete")
    void delete(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Delete body) {
        service.delete(UUID.fromString(jwt.getSubject()), body.token(), body.confirmation());
    }
}
