package dev.riskaware.simulator.market;

import dev.riskaware.simulator.trading.TradingApi.Asset;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/market")
@SecurityRequirement(name = "Bearer")
class MarketController {
    private final MarketBook book;
    private final DailyHistory history;
    MarketController(MarketBook book, DailyHistory history) {
        this.book = book;
        this.history = history;
    }

    @GetMapping("/prices")
    MarketBook.Snapshot prices() { return book.snapshot(); }

    @GetMapping("/history/{asset}")
    DailyHistory.Series history(@PathVariable Asset asset, @RequestParam(defaultValue = "30") @Min(1) @Max(90) int days) {
        return history.history(asset, days);
    }
}
