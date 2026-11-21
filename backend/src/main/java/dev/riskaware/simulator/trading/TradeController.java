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
@Tag(name = "Virtual trading", description = "Fixed mock prices in virtual USD. BTC 50,000 and ETH 2,500.")
@SecurityRequirement(name = "Bearer")
class TradeController {
    private final TradeService trading;

    TradeController(TradeService trading) { this.trading = trading; }

    @GetMapping("/prices/mock")
    List<Quote> prices() {
        return Arrays.stream(Asset.values()).map(MockPrices::quote).toList();
    }

    @PostMapping("/trades")
    Trade execute(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") UUID key,
                  @Valid @RequestBody Order order) {
        return trading.execute(UUID.fromString(jwt.getSubject()), key, order);
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
