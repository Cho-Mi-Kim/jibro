package org.jibro.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/security/visitor")
public class VisitorController {
    private final VisitorVerification visitor;
    private final TurnstileVerifier verifier;

    public VisitorController(VisitorVerification visitor, TurnstileVerifier verifier) {
        this.visitor = visitor;
        this.verifier = verifier;
    }

    public record Status(boolean enabled, boolean verified, String siteKey) {
    }

    public record Challenge(@NotBlank @Size(max = 2048) String token) {
    }

    @GetMapping
    Status status(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return new Status(visitor.enabled, visitor.verified(request), visitor.enabled ? visitor.siteKey : "");
    }

    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void verify(@Valid @RequestBody Challenge challenge, HttpServletRequest request, HttpServletResponse response) {
        if (!visitor.enabled) return;
        verifier.verify(challenge.token());
        visitor.admit(request, response);
    }
}
