package pulse_api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pulse_api.dto.DailySummaryDto;
import pulse_api.dto.OverviewDto;
import pulse_api.entity.AlertEvent;
import pulse_api.entity.Enums;
import pulse_api.entity.Project;
import pulse_api.entity.Prospect;
import pulse_api.repository.AlertEventRepository;
import pulse_api.repository.DailySummaryRepository;
import pulse_api.repository.MetricSnapshotRepository;
import pulse_api.repository.ProjectRepository;
import pulse_api.repository.ProspectRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static pulse_api.service.MetricQueryService.*;

/** The "All" view (contract §2.2), computed purely from tables — never from live sources. */
@Service
@RequiredArgsConstructor
public class OverviewService {

    private final ProjectRepository projects;
    private final MetricSnapshotRepository snapshots;
    private final MetricQueryService metrics;
    private final UptimeStatusService uptime;
    private final AlertEventRepository alertEvents;
    private final ProspectRepository prospects;
    private final DailySummaryRepository summaries;
    private final RangeResolver ranges;

    @Transactional(readOnly = true)
    public OverviewDto overview(String range) {
        var r = ranges.resolve(range);
        List<Project> active = projects.findByActiveTrueOrderBySortOrderAscNameAsc();

        List<OverviewDto.ProjectCard> cards = new ArrayList<>();
        int sitesTotal = 0;
        int sitesUp = 0;
        for (Project p : active) {
            Enums.UpState status = uptime.status(p);
            if (uptime.primaryTarget(p) != null) {
                sitesTotal++;
                if (status == Enums.UpState.up) {
                    sitesUp++;
                }
            }
            cards.add(card(p, r, status));
        }

        return new OverviewDto(r.range(), headline(active, r, sitesUp, sitesTotal), cards, needsYou(),
                summaries.findFirstByOrderBySummaryDateDesc().map(DailySummaryDto::from).orElse(null),
                snapshots.maxCapturedAt());
    }

    private OverviewDto.Headline headline(List<Project> active, RangeResolver.ResolvedRange r, int sitesUp, int sitesTotal) {
        List<UUID> ga4Projects = active.stream().filter(p -> metrics.hasGa4Data(p.getId())).map(Project::getId).toList();
        Long visitors = ga4Projects.isEmpty() ? null
                : snapshots.sumForProjects(ga4Projects, ACTIVE_USERS, r.startDay(), r.endDay()).longValue();

        Long cvDownloads = null;
        for (Project p : active) {
            if (p.getKind() == Enums.ProjectKind.portfolio && metrics.hasGa4Data(p.getId())) {
                cvDownloads = (cvDownloads == null ? 0 : cvDownloads) + metrics.sum(p.getId(), EVENT_PREFIX + "file_download", r);
            }
        }

        Long seatsUsed = null;
        Long seatsTotal = null;
        for (Project p : active) {
            if (p.getKind() == Enums.ProjectKind.product) {
                Long used = metrics.latestValue(p.getId(), "founder_seats_used");
                Long total = metrics.latestValue(p.getId(), "founder_seats_total");
                if (used != null) {
                    seatsUsed = used;
                    seatsTotal = total;
                    break;
                }
            }
        }
        // bookingsThisWeek and depositsCents are Bookvas endpoint gaps (contract §5) → null
        return new OverviewDto.Headline(visitors, null, null, seatsUsed, seatsTotal, cvDownloads, sitesUp, sitesTotal);
    }

    private OverviewDto.ProjectCard card(Project p, RangeResolver.ResolvedRange r, Enums.UpState status) {
        boolean ga4 = metrics.hasGa4Data(p.getId());
        List<OverviewDto.CardNumber> numbers = switch (p.getKind()) {
            case product -> List.of(
                    new OverviewDto.CardNumber("tenants", "Tenants", metrics.latestValue(p.getId(), "tenants_total"), "count"),
                    new OverviewDto.CardNumber("founder_seats", "Founder seats", metrics.latestValue(p.getId(), "founder_seats_used"), "count"),
                    new OverviewDto.CardNumber("visitors", "Visitors", ga4 ? metrics.sum(p.getId(), ACTIVE_USERS, r) : null, "count"));
            case portfolio -> List.of(
                    new OverviewDto.CardNumber("visitors", "Visitors", ga4 ? metrics.sum(p.getId(), ACTIVE_USERS, r) : null, "count"),
                    new OverviewDto.CardNumber("cv_downloads", "CV downloads", ga4 ? metrics.sum(p.getId(), EVENT_PREFIX + "file_download", r) : null, "count"),
                    new OverviewDto.CardNumber("sessions", "Sessions", ga4 ? metrics.sum(p.getId(), SESSIONS, r) : null, "count"));
            default -> List.of(
                    new OverviewDto.CardNumber("visitors", "Visitors", ga4 ? metrics.sum(p.getId(), ACTIVE_USERS, r) : null, "count"),
                    new OverviewDto.CardNumber("sessions", "Sessions", ga4 ? metrics.sum(p.getId(), SESSIONS, r) : null, "count"),
                    new OverviewDto.CardNumber("page_views", "Page views", ga4 ? metrics.sum(p.getId(), PAGE_VIEWS, r) : null, "count"));
        };
        return new OverviewDto.ProjectCard(p.getId(), p.getSlug(), p.getName(), p.getKind(), p.getColor(), numbers,
                sparkline(p.getId(), r), status, alertEvents.countByProjectIdAndAcknowledgedAtIsNull(p.getId()));
    }

    /** Daily activeUsers for 7d/30d; 24 hourly points for today (spec §5.2). */
    public List<Long> sparkline(UUID projectId, RangeResolver.ResolvedRange r) {
        if (RangeResolver.TODAY.equals(r.range())) {
            return metrics.hourlySeries(projectId, ACTIVE_USERS, r.endDay(), ranges.now().getHour());
        }
        return new ArrayList<>(metrics.dailySeries(projectId, ACTIVE_USERS, r).values());
    }

    public List<OverviewDto.NeedsYouItem> needsYou() {
        List<OverviewDto.NeedsYouItem> items = new ArrayList<>();
        for (AlertEvent e : alertEvents.findByAcknowledgedAtIsNullOrderByFiredAtDesc(PageRequest.of(0, 50))) {
            items.add(new OverviewDto.NeedsYouItem("alert", e.getId(), e.getTitle(), e.getDetail(), e.getFiredAt(),
                    e.getProjectId(), "/alerts/" + e.getId()));
        }
        LocalDate today = ranges.today();
        for (Prospect p : overdueProspects(today)) {
            String detail = (p.getNextAction() == null ? "Follow up" : p.getNextAction()) + " · due " + p.getNextActionDate();
            items.add(new OverviewDto.NeedsYouItem("prospect", p.getId(),
                    p.getName() + (p.getBusiness() == null ? "" : " — " + p.getBusiness()), detail,
                    ranges.startOfDay(p.getNextActionDate()), null, "/prospects/" + p.getId()));
        }
        return items;
    }

    public List<Prospect> overdueProspects(LocalDate today) {
        return prospects.findByNextActionDateBeforeAndStatusNotInOrderByNextActionDateAsc(today,
                EnumSet.of(Enums.ProspectStatus.tenant, Enums.ProspectStatus.declined));
    }
}
