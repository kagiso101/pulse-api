package pulse_api.dto;

import pulse_api.entity.Enums;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Contract §3 {@code ClientView}. */
public record ClientViewDto(String name, String siteUrl, Enums.UpState status, Double uptimePct30d, Traffic traffic,
                            Instant generatedAt) {

    public record Traffic(List<SeriesPoint> series, List<ProjectDashboardDto.TopPage> topPages) {}

    public record SeriesPoint(LocalDate date, long activeUsers, long sessions) {}
}
