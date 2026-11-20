package dev.riskaware.simulator.account;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

public final class AccountApi {
    private AccountApi() {}

    public record Credentials(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 12, max = 72) String password) {
        public Credentials {
            if (email != null) email = email.strip().toLowerCase(Locale.ROOT);
        }
    }

    public record Profile(UUID id, String email, BigDecimal virtualCashUsd) {}
    public record Session(String accessToken, String tokenType, long expiresInSeconds) {}
}
