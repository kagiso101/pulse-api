package pulse_api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import pulse_api.entity.Enums;

import java.time.Instant;
import java.util.List;

/** Contract §2.6. */
public final class CostDtos {

    private CostDtos() {}

    public record CostReport(String month, long totalCents, List<ProviderRow> byProvider, List<TrendPoint> trend) {}

    public record ProviderRow(Enums.CostProvider provider, long amountCents, Enums.CostSource source, Instant capturedAt) {}

    public record TrendPoint(String month, long totalCents) {}

    public record ManualCostRequest(@NotNull Enums.CostProvider provider,
                                    @NotBlank @Pattern(regexp = "\\d{4}-\\d{2}", message = "must be YYYY-MM") String month,
                                    @NotNull @PositiveOrZero Long amountCents) {}
}
