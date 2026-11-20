package dev.riskaware.simulator.account;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "app_users")
class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    private String email;
    private String passwordHash;
    private Instant createdAt;

    protected AppUser() {}

    AppUser(String email, String passwordHash) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.createdAt = Instant.now();
    }

    UUID id() { return id; }
    String email() { return email; }
    String passwordHash() { return passwordHash; }
}
