package io.github.moyalshoham.runway.api;

import io.github.moyalshoham.runway.api.Dtos.BalanceDto;
import io.github.moyalshoham.runway.api.Dtos.ComparisonResponse;
import io.github.moyalshoham.runway.api.Dtos.ForecastResponse;
import io.github.moyalshoham.runway.api.Dtos.ScenarioRequest;
import io.github.moyalshoham.runway.engine.forecast.DailyPoint;
import io.github.moyalshoham.runway.engine.forecast.Forecast;
import io.github.moyalshoham.runway.service.ForecastService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/companies/{id}")
public class ForecastController {

    private final ForecastService forecasts;

    public ForecastController(ForecastService forecasts) {
        this.forecasts = forecasts;
    }

    @GetMapping("/forecast")
    public ForecastResponse forecast(
            @PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) @Min(7) @Max(180) Integer horizonDays) {
        return ForecastResponse.of(forecasts.forecast(id, asOf, horizonDays));
    }

    @PostMapping("/scenarios")
    public ComparisonResponse scenarios(@PathVariable long id, @Valid @RequestBody ScenarioRequest request) {
        ForecastService.Comparison c = forecasts.compare(
                id,
                request.asOf(),
                request.horizonDays(),
                request.scenarios().stream().map(Dtos.ScenarioItem::toScenario).toList());
        return new ComparisonResponse(
                ForecastResponse.of(c.baseline()),
                ForecastResponse.of(c.scenario()),
                c.scenario()
                        .days()
                        .getLast()
                        .p50()
                        .subtract(c.baseline().days().getLast().p50()),
                lowest(c.baseline()),
                lowest(c.scenario()));
    }

    @GetMapping("/history")
    public List<BalanceDto> history(
            @PathVariable long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue = "120") @Min(1) @Max(730) int days) {
        return forecasts.history(id, asOf, days).stream()
                .map(p -> new BalanceDto(p.date(), p.balance()))
                .toList();
    }

    private static BigDecimal lowest(Forecast f) {
        return f.days().stream()
                .map(DailyPoint::p50)
                .min(Comparator.naturalOrder())
                .orElseThrow();
    }
}
