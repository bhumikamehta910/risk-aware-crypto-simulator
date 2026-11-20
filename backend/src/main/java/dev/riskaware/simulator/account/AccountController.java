package dev.riskaware.simulator.account;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
class AccountController {
    private final AccountService accounts;

    AccountController(AccountService accounts) { this.accounts = accounts; }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    AccountApi.Profile register(@Valid @RequestBody AccountApi.Credentials input) {
        return accounts.register(input);
    }

    @PostMapping("/auth/login")
    AccountApi.Session login(@Valid @RequestBody AccountApi.Credentials input) {
        return accounts.login(input);
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "Bearer")
    AccountApi.Profile me(@AuthenticationPrincipal Jwt jwt) {
        return accounts.profile(UUID.fromString(jwt.getSubject()));
    }
}
