package dev.riskaware.simulator.security;

import dev.riskaware.simulator.account.AccountApi.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    static final String ISSUER = "risk-aware-simulator";
    private static final Duration LIFETIME = Duration.ofMinutes(15);
    private final JwtEncoder encoder;

    public TokenService(JwtEncoder encoder) { this.encoder = encoder; }

    public Session issue(UUID userId) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(ISSUER).subject(userId.toString())
                .issuedAt(now).expiresAt(now.plus(LIFETIME)).build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new Session(token, "Bearer", LIFETIME.toSeconds());
    }
}
