package dev.riskaware.simulator.account;

import tools.jackson.databind.ObjectMapper;
import dev.riskaware.simulator.SimulatorApplication;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = SimulatorApplication.class, properties = "market.mode=MOCK_FIXED")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountIntegrationTest {
    private static final String PASSWORD = "correct-laboratory-passphrase";
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired AccountService accounts;
    @Autowired JwtEncoder encoder;
    @MockitoSpyBean WalletRepository wallets;

    private String freshEmail() { return UUID.randomUUID() + "@example.test"; }

    private ResultActions credentials(String operation, String email, String password) throws Exception {
        return http.perform(post("/api/auth/" + operation).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("email", email, "password", password))));
    }

    private String register(String email) throws Exception {
        var result = credentials("register", email, PASSWORD).andExpect(status().isCreated())
                .andExpect(jsonPath("$.virtualCashUsd").value(10000))
                .andExpect(jsonPath("$.passwordHash").doesNotExist()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).get("id").asString();
    }

    @Test
    void bearerSubjectSelectsOwnerEvenWhenCallerSuppliesAnotherId() throws Exception {
        String alice = freshEmail();
        String aliceId = register(alice);
        String bobId = register(freshEmail());
        var response = credentials("login", " " + alice.toUpperCase(java.util.Locale.ROOT) + " ", PASSWORD)
                .andExpect(status().isOk()).andReturn().getResponse();
        String token = json.readTree(response.getContentAsString()).get("accessToken").asString();
        http.perform(get("/api/me").param("userId", bobId).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(aliceId))
                .andExpect(jsonPath("$.email").value(alice)).andExpect(jsonPath("$.virtualCashUsd").value(10000));
        assertThat(db.queryForObject("select password_hash from app_users where email=?", String.class, alice))
                .startsWith("$2").isNotEqualTo(PASSWORD);
        String[] parts = token.split("\\.");
        String badSignature = (parts[2].startsWith("A") ? "B" : "A") + parts[2].substring(1);
        http.perform(get("/api/me").header("Authorization", "Bearer " + parts[0] + "." + parts[1] + "." + badSignature))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateEmailCannotReceiveAnotherOpeningBalance() throws Exception {
        String email = freshEmail();
        register(email);
        credentials("register", email.toUpperCase(java.util.Locale.ROOT), PASSWORD).andExpect(status().isConflict());
        assertThat(db.queryForObject("select count(*) from wallets w join app_users u on u.id=w.user_id where u.email=?",
                Integer.class, email)).isEqualTo(1);
    }

    @Test
    void invalidCredentialsAndMissingTokenAreRejected() throws Exception {
        String email = freshEmail();
        register(email);
        credentials("login", email, "incorrect-password").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid credentials"));
        credentials("login", freshEmail(), PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid credentials"));
        http.perform(get("/api/me")).andExpect(status().isUnauthorized());
        http.perform(get("/api/me").header("Authorization", "Bearer invalid")).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenRejectedEvenWithValidSignature() throws Exception {
        Instant past = Instant.now().minusSeconds(3600);
        var claims = JwtClaimsSet.builder().issuer("risk-aware-simulator").subject(register(freshEmail()))
                .issuedAt(past).expiresAt(past.plusSeconds(900)).build();
        var token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims));
        http.perform(get("/api/me").header("Authorization", "Bearer " + token.getTokenValue()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidRegistrationAndOversizedUtf8PasswordRejected() throws Exception {
        credentials("register", "bad-email", PASSWORD).andExpect(status().isBadRequest());
        credentials("register", freshEmail(), "short").andExpect(status().isBadRequest());
        credentials("register", freshEmail(), "é".repeat(37)).andExpect(status().isBadRequest());
    }

    @Test
    void walletFailureRollsBackFlushedUser() {
        String email = freshEmail();
        doThrow(new IllegalStateException("simulated wallet failure")).when(wallets).save(any(Wallet.class));
        assertThatThrownBy(() -> accounts.register(new AccountApi.Credentials(email, PASSWORD)))
                .isInstanceOf(IllegalStateException.class).hasMessage("simulated wallet failure");
        assertThat(db.queryForObject("select count(*) from app_users where email=?", Integer.class, email)).isZero();
    }

    @Test
    void openApiIsAvailableWithoutAuthentication() throws Exception {
        http.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/me'].get.security[0].Bearer").isArray());
    }
}
