package dev.riskaware.simulator.account;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WalletRepository extends JpaRepository<Wallet, UUID> {}
