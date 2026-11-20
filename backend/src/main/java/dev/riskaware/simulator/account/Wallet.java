package dev.riskaware.simulator.account;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "wallets")
class Wallet {
    @Id
    private UUID userId;
    @MapsId @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private AppUser owner;
    @Column(precision = 19, scale = 2)
    private BigDecimal cashUsd;

    protected Wallet() {}

    Wallet(AppUser owner) {
        this.owner = owner;
        this.cashUsd = new BigDecimal("10000.00");
    }

    BigDecimal cashUsd() { return cashUsd; }
}
