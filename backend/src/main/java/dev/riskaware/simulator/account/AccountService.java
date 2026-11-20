package dev.riskaware.simulator.account;

import dev.riskaware.simulator.security.TokenService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class AccountService {
    private final UserRepository users;
    private final WalletRepository wallets;
    private final PasswordEncoder passwords;
    private final TokenService tokens;
    private final String dummyHash;

    AccountService(UserRepository users, WalletRepository wallets, PasswordEncoder passwords, TokenService tokens) {
        this.users = users;
        this.wallets = wallets;
        this.passwords = passwords;
        this.tokens = tokens;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AccountApi.Profile register(AccountApi.Credentials input) {
        validatePasswordBytes(input.password());
        AppUser user = new AppUser(input.email(), passwords.encode(input.password()));
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            if (ex.getCause() instanceof ConstraintViolationException violation
                    && "23505".equals(violation.getSQLState())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
            }
            throw ex;
        }
        Wallet wallet = wallets.save(new Wallet(user));
        return new AccountApi.Profile(user.id(), user.email(), wallet.cashUsd());
    }

    @Transactional(readOnly = true)
    public AccountApi.Session login(AccountApi.Credentials input) {
        validatePasswordBytes(input.password());
        var user = users.findByEmail(input.email());
        boolean matches = passwords.matches(input.password(), user.map(AppUser::passwordHash).orElse(dummyHash));
        if (!matches || user.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        return tokens.issue(user.get().id());
    }

    @Transactional(readOnly = true)
    public AccountApi.Profile profile(UUID id) {
        var user = users.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown account"));
        var wallet = wallets.findById(id).orElseThrow();
        return new AccountApi.Profile(id, user.email(), wallet.cashUsd());
    }

    private void validatePasswordBytes(String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password exceeds 72 UTF-8 bytes");
        }
    }
}
