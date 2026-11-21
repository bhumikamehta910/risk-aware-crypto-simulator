package dev.riskaware.simulator.trading;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import static dev.riskaware.simulator.trading.TradingApi.*;

@RestController
@RequestMapping("/api")
@Tag(name = "Virtual trading", description = "Executions use the server's configured market mode. The legacy /prices/mock route always shows demonstration values.")
@SecurityRequirement(name = "Bearer")
class TradeController {
    private final TradeService trading;

    private final PortfolioService portfolios;

    TradeController(TradeService trading, PortfolioService portfolios) {
        this.trading = trading;
        this.portfolios = portfolios;
    }

    @GetMapping("/portfolio")
    PortfolioService.Portfolio portfolio(@AuthenticationPrincipal Jwt jwt) {
        return portfolios.load(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/prices/mock")
    List<Quote> prices() {
        return Arrays.stream(Asset.values()).map(MockPrices::quote).toList();
    }

    @PostMapping("/trades")
    Trade execute(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") UUID key,
                  @Valid @RequestBody Order order) {
        return trading.execute(UUID.fromString(jwt.getSubject()), key, order);
    }

    @PostMapping("/trades/preview")
    OrderAssessment preview(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Order order) {
        return trading.preview(UUID.fromString(jwt.getSubject()), order);
    }

    @GetMapping("/risk/limits")
    RiskLimits limits(@AuthenticationPrincipal Jwt jwt) { return trading.limits(UUID.fromString(jwt.getSubject())); }

    @PutMapping("/risk/limits")
    RiskLimits limits(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RiskLimits limits) {
        return trading.updateLimits(UUID.fromString(jwt.getSubject()), limits);
    }

    @GetMapping("/holdings")
    List<Holding> holdings(@AuthenticationPrincipal Jwt jwt) {
        return trading.holdings(UUID.fromString(jwt.getSubject()));
    }

    @GetMapping("/trades")
    History history(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Asset asset,
                    @RequestParam(required = false) Side side, @RequestParam(required = false) Instant from,
                    @RequestParam(required = false) Instant to, @RequestParam(defaultValue = "0") @Min(0) int page,
                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return trading.history(UUID.fromString(jwt.getSubject()), asset, side, from, to, page, size);
    }
}
