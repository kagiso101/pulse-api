package pulse_api.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pulse_api.alerts.Notifier;
import pulse_api.connector.FetchResult;
import pulse_api.entity.AppSetting;
import pulse_api.entity.DailySummary;
import pulse_api.entity.Enums;
import pulse_api.entity.Project;
import pulse_api.repository.AlertEventRepository;
import pulse_api.repository.AppSettingRepository;
import pulse_api.repository.DailySummaryRepository;
import pulse_api.repository.ProjectRepository;
import pulse_api.service.MetricQueryService;
import pulse_api.service.OverviewService;
import pulse_api.service.RangeResolver;
import pulse_api.service.UptimeStatusService;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The 07:00 SAST message (spec §6): "Yesterday: N bookings, R X in deposits, N portfolio visits,
 * N CV downloads. Sites: all up. Needs you: N." Bookings and deposits are Bookvas endpoint gaps
 * today and read "n/a". Stored in daily_summary and sent on the configured channel.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailySummaryService {

    public static final String SOURCE = "summary";

    private final ProjectRepository projects;
    private final MetricQueryService metrics;
    private final UptimeStatusService uptime;
    private final AlertEventRepository alertEvents;
    private final OverviewService overview;
    private final DailySummaryRepository summaries;
    private final AppSettingRepository settings;
    private final Notifier notifier;
    private final RangeResolver ranges;

    @Transactional
    public FetchResult run() {
        LocalDate today = ranges.today();
        LocalDate yesterday = today.minusDays(1);
        String body = build(yesterday, today);

        DailySummary row = summaries.findBySummaryDate(yesterday).orElseGet(DailySummary::new);
        row.setSummaryDate(yesterday);
        row.setBody(body);
        List<String> notes = new ArrayList<>();
        if (row.getSentAt() == null) {
            Enums.Channel channel = "whatsapp".equals(settings.findById(AppSetting.SINGLETON_ID)
                    .map(AppSetting::getNotificationChannel).orElse("email")) ? Enums.Channel.whatsapp : Enums.Channel.email;
            Notifier.Delivery delivery = notifier.notify(channel, "Pulse — " + yesterday, body);
            row.setChannel(delivery.channelUsed());
            if (delivery.delivered()) {
                row.setSentAt(Instant.now());
            } else {
                notes.add(SOURCE + ": stored but not sent (no notification channel configured)");
            }
        }
        summaries.save(row);
        return FetchResult.of(1, notes);
    }

    String build(LocalDate yesterday, LocalDate today) {
        var day = new RangeResolver.ResolvedRange("yesterday", yesterday, yesterday,
                ranges.startOfDay(yesterday), ranges.startOfDay(today), List.of(yesterday));
        List<Project> active = projects.findByActiveTrueOrderBySortOrderAscNameAsc();

        String visits = "n/a";
        String downloads = "n/a";
        for (Project p : active) {
            if (p.getKind() == Enums.ProjectKind.portfolio && metrics.hasGa4Data(p.getId())) {
                visits = String.valueOf(metrics.sum(p.getId(), MetricQueryService.ACTIVE_USERS, day));
                downloads = String.valueOf(metrics.sum(p.getId(), MetricQueryService.EVENT_PREFIX + "file_download", day));
            }
        }
        List<String> down = new ArrayList<>();
        for (Project p : active) {
            if (uptime.status(p) == Enums.UpState.down) {
                down.add(p.getName());
            }
        }
        String sites = down.isEmpty() ? "all up" : down.size() + " down (" + String.join(", ", down) + ")";
        long needsYou = alertEvents.countByAcknowledgedAtIsNull() + overview.overdueProspects(today).size();

        // bookings and deposits: Bookvas has no platform-wide endpoint yet (specs/BOOKVAS-API-GAPS.md)
        return "Yesterday: n/a bookings, R n/a in deposits, " + visits + " portfolio visits, " + downloads
                + " CV downloads. Sites: " + sites + ". Needs you: " + needsYou + ".";
    }
}
