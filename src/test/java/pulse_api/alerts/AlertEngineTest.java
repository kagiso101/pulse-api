package pulse_api.alerts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import pulse_api.entity.AlertEvent;
import pulse_api.entity.AlertRule;
import pulse_api.entity.AlertState;
import pulse_api.entity.BookvasTenantCache;
import pulse_api.entity.Enums;
import pulse_api.entity.Enums.AlertKind;
import pulse_api.entity.MetricSnapshot;
import pulse_api.entity.Project;
import pulse_api.entity.Prospect;
import pulse_api.entity.UptimeCheck;
import pulse_api.repository.AlertEventRepository;
import pulse_api.repository.AlertRuleRepository;
import pulse_api.repository.AlertStateRepository;
import pulse_api.repository.BookvasTenantCacheRepository;
import pulse_api.repository.MetricSnapshotRepository;
import pulse_api.repository.ProjectRepository;
import pulse_api.repository.ProspectRepository;
import pulse_api.repository.UptimeCheckRepository;
import pulse_api.service.RangeResolver;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Each rule kind fires exactly once for one condition: the second (and third) evaluation of the
 * same state produces no new event. Repositories are Mockito mocks with in-memory alert_state and
 * alert_event behaviour so the engine's own memory is what is under test.
 */
class AlertEngineTest {

    private final AlertRuleRepository rules = Mockito.mock(AlertRuleRepository.class);
    private final AlertEventRepository events = Mockito.mock(AlertEventRepository.class);
    private final AlertStateRepository states = Mockito.mock(AlertStateRepository.class);
    private final MetricSnapshotRepository snapshots = Mockito.mock(MetricSnapshotRepository.class);
    private final UptimeCheckRepository uptime = Mockito.mock(UptimeCheckRepository.class);
    private final BookvasTenantCacheRepository tenants = Mockito.mock(BookvasTenantCacheRepository.class);
    private final ProspectRepository prospects = Mockito.mock(ProspectRepository.class);
    private final ProjectRepository projects = Mockito.mock(ProjectRepository.class);
    private final Notifier notifier = Mockito.mock(Notifier.class);
    private final RangeResolver ranges = new RangeResolver(Clock.fixed(Instant.parse("2026-09-15T08:00:00Z"), ZoneOffset.UTC));

    private final Map<String, AlertState> stateStore = new HashMap<>();
    private final List<AlertEvent> fired = new ArrayList<>();
    private final Project bookvas = new Project();
    private final LocalDate today = LocalDate.of(2026, 9, 15);

    private AlertEngine engine;

    @BeforeEach
    void setUp() {
        bookvas.setId(UUID.randomUUID());
        bookvas.setSlug("bookvas");
        bookvas.setName("Bookvas");
        bookvas.setKind(Enums.ProjectKind.product);
        bookvas.setSiteUrl("https://rt-bookings.netlify.app");
        bookvas.setActive(true);

        when(states.findById(anyString())).thenAnswer(i -> Optional.ofNullable(stateStore.get(i.getArgument(0, String.class))));
        when(states.save(any(AlertState.class))).thenAnswer(i -> {
            AlertState s = i.getArgument(0);
            stateStore.put(s.getStateKey(), s);
            return s;
        });
        Mockito.doAnswer(i -> stateStore.remove(i.getArgument(0, String.class))).when(states).deleteById(anyString());

        when(events.existsByDedupeKeyAndAcknowledgedAtIsNull(anyString())).thenAnswer(i ->
                fired.stream().anyMatch(e -> e.getAcknowledgedAt() == null && i.getArgument(0, String.class).equals(e.getDedupeKey())));
        when(events.save(any(AlertEvent.class))).thenAnswer(i -> {
            AlertEvent e = i.getArgument(0);
            fired.add(e);
            return e;
        });
        when(notifier.notify(any(), anyString(), any(), anyBoolean())).thenReturn(new Notifier.Delivery(false, "in_app"));

        when(projects.findById(bookvas.getId())).thenReturn(Optional.of(bookvas));
        when(projects.findByActiveTrueAndKind(Enums.ProjectKind.product)).thenReturn(List.of(bookvas));
        when(projects.findByActiveTrueOrderBySortOrderAscNameAsc()).thenReturn(List.of(bookvas));

        engine = new AlertEngine(rules, events, states, snapshots, uptime, tenants, prospects, projects, notifier, ranges);
    }

    private AlertRule rule(AlertKind kind, Enums.Channel channel, BigDecimal threshold, boolean scoped) {
        AlertRule r = new AlertRule();
        r.setId(UUID.randomUUID());
        r.setKind(kind);
        r.setLabel(kind.name());
        r.setChannel(channel);
        r.setThreshold(threshold);
        r.setEnabled(true);
        r.setProjectId(scoped ? bookvas.getId() : null);
        when(rules.findByEnabledTrue()).thenReturn(List.of(r));
        return r;
    }

    private MetricSnapshot snapshot(String key, long value, LocalDate day) {
        MetricSnapshot s = new MetricSnapshot();
        s.setProjectId(bookvas.getId());
        s.setSource("x");
        s.setMetricKey(key);
        s.setPeriod("day");
        s.setPeriodStart(day);
        s.setMetricValue(BigDecimal.valueOf(value));
        s.setCapturedAt(Instant.now());
        return s;
    }

    @Test
    void depositPaidFiresOnceForAnIncreaseAfterBaseline() {
        rule(AlertKind.deposit_paid, Enums.Channel.whatsapp, null, true);
        AtomicReference<Long> purchases = new AtomicReference<>(2L);
        when(snapshots.existsByProjectIdAndMetricKeyAndPeriodStartBetween(eq(bookvas.getId()), eq("event:purchase"), eq(today), eq(today))).thenReturn(true);
        when(snapshots.findByProjectIdAndMetricKeyAndPeriodStart(eq(bookvas.getId()), eq("event:purchase"), eq(today)))
                .thenAnswer(i -> List.of(snapshot("event:purchase", purchases.get(), today)));

        assertThat(engine.evaluate(EnumSet.of(AlertKind.deposit_paid))).isZero(); // baseline, no alert for history
        purchases.set(3L);
        assertThat(engine.evaluate(EnumSet.of(AlertKind.deposit_paid))).isEqualTo(1);
        assertThat(engine.evaluate(EnumSet.of(AlertKind.deposit_paid))).isZero();
        assertThat(fired).hasSize(1);
        assertThat(fired.get(0).getPayload()).containsEntry("newPurchases", 1L);
    }

    @Test
    void founderSeatClaimedFiresOnceOnIncrement() {
        rule(AlertKind.founder_seat_claimed, Enums.Channel.whatsapp, null, true);
        AtomicReference<Long> used = new AtomicReference<>(5L);
        when(snapshots.findFirstByProjectIdAndMetricKeyOrderByCapturedAtDesc(bookvas.getId(), "founder_seats_used"))
                .thenAnswer(i -> Optional.of(snapshot("founder_seats_used", used.get(), today)));
        when(snapshots.findFirstByProjectIdAndMetricKeyOrderByCapturedAtDesc(bookvas.getId(), "founder_seats_total"))
                .thenReturn(Optional.of(snapshot("founder_seats_total", 20, today)));

        engine.evaluate(EnumSet.of(AlertKind.founder_seat_claimed));
        used.set(6L);
        assertThat(engine.evaluate(EnumSet.of(AlertKind.founder_seat_claimed))).isEqualTo(1);
        assertThat(engine.evaluate(EnumSet.of(AlertKind.founder_seat_claimed))).isZero();
        assertThat(fired).hasSize(1);
        assertThat(fired.get(0).getDetail()).isEqualTo("Founder seats used: 6 / 20");
    }

    @Test
    void siteDownFiresOnceAfterThreeFailuresAndAgainOnlyAfterRecovery() {
        rule(AlertKind.site_down, Enums.Channel.whatsapp, BigDecimal.valueOf(3), false);
        List<UptimeCheck> down = List.of(check(false), check(false), check(false));
        List<UptimeCheck> up = List.of(check(true), check(false), check(false));
        AtomicReference<List<UptimeCheck>> recent = new AtomicReference<>(List.of(check(false), check(false), check(true)));
        when(uptime.findTop3ByProjectIdAndTargetOrderByCheckedAtDesc(bookvas.getId(), Enums.UptimeTarget.site))
                .thenAnswer(i -> recent.get());

        assertThat(engine.evaluate(EnumSet.of(AlertKind.site_down))).isZero(); // only 2 consecutive failures
        recent.set(down);
        assertThat(engine.evaluate(EnumSet.of(AlertKind.site_down))).isEqualTo(1);
        assertThat(engine.evaluate(EnumSet.of(AlertKind.site_down))).isZero();
        recent.set(up);
        assertThat(engine.evaluate(EnumSet.of(AlertKind.site_down))).isZero();
        fired.get(0).setAcknowledgedAt(Instant.now());
        recent.set(down);
        assertThat(engine.evaluate(EnumSet.of(AlertKind.site_down))).isEqualTo(1);
        assertThat(fired).hasSize(2);
        Mockito.verify(notifier, Mockito.times(2)).notify(eq(Enums.Channel.whatsapp), anyString(), any(), eq(true));
    }

    @Test
    void emailFailuresFiresOnceAboveThresholdAndResetsBelowIt() {
        rule(AlertKind.email_failures, Enums.Channel.email, BigDecimal.valueOf(3), true);
        AtomicReference<Long> failed = new AtomicReference<>(5L);
        when(snapshots.findFirstByProjectIdAndMetricKeyOrderByCapturedAtDesc(bookvas.getId(), "email_failed_24h"))
                .thenAnswer(i -> Optional.of(snapshot("email_failed_24h", failed.get(), today)));

        assertThat(engine.evaluate(EnumSet.of(AlertKind.email_failures))).isEqualTo(1);
        assertThat(engine.evaluate(EnumSet.of(AlertKind.email_failures))).isZero();
        failed.set(1L);
        assertThat(engine.evaluate(EnumSet.of(AlertKind.email_failures))).isZero();
        fired.get(0).setAcknowledgedAt(Instant.now());
        failed.set(4L);
        assertThat(engine.evaluate(EnumSet.of(AlertKind.email_failures))).isEqualTo(1);
        assertThat(fired).hasSize(2);
    }

    @Test
    void tenantGraceFiresOnceOnTransitionToGrace() {
        rule(AlertKind.tenant_grace, Enums.Channel.email, null, true);
        BookvasTenantCache t = new BookvasTenantCache();
        t.setTenantId(UUID.randomUUID());
        t.setSlug("nails");
        t.setBusinessName("Blaauwberg Nails");
        t.setSubscriptionStatus("ACTIVE");
        when(tenants.findAll()).thenReturn(List.of(t));

        assertThat(engine.evaluate(EnumSet.of(AlertKind.tenant_grace))).isZero();
        t.setSubscriptionStatus("GRACE");
        assertThat(engine.evaluate(EnumSet.of(AlertKind.tenant_grace))).isEqualTo(1);
        assertThat(engine.evaluate(EnumSet.of(AlertKind.tenant_grace))).isZero();
        assertThat(fired).hasSize(1);
        assertThat(fired.get(0).getTitle()).isEqualTo("Blaauwberg Nails entered grace");
    }

    @Test
    void prospectOverdueFiresOnceInAppOnly() {
        rule(AlertKind.prospect_overdue, Enums.Channel.in_app, null, false);
        Prospect p = new Prospect();
        p.setId(UUID.randomUUID());
        p.setName("Thandi");
        p.setStatus(Enums.ProspectStatus.contacted);
        p.setNextAction("Call back");
        p.setNextActionDate(today.minusDays(2));
        when(prospects.findByNextActionDateBeforeAndStatusNotInOrderByNextActionDateAsc(eq(today), any())).thenReturn(List.of(p));

        assertThat(engine.evaluate(EnumSet.of(AlertKind.prospect_overdue))).isEqualTo(1);
        assertThat(engine.evaluate(EnumSet.of(AlertKind.prospect_overdue))).isZero();
        assertThat(fired).hasSize(1);
        assertThat(fired.get(0).isDelivered()).isFalse();
        assertThat(fired.get(0).getChannel()).isEqualTo("in_app");
        Mockito.verify(notifier).notify(eq(Enums.Channel.in_app), anyString(), any(), eq(false));
    }

    private static UptimeCheck check(boolean ok) {
        UptimeCheck c = new UptimeCheck();
        c.setOk(ok);
        c.setError(ok ? null : "HTTP 503");
        return c;
    }
}
