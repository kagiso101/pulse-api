package pulse_api.alerts;

import org.junit.jupiter.api.Test;
import pulse_api.entity.Enums.AlertKind;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The one email look: branded, severity-coloured, facts readable, nothing injectable. */
class AlertEmailTemplateTest {

    private static AlertMessage stalled() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bookvasEventId", "3f0a-…");
        payload.put("tenant", "Thandi's Nails <script>alert(1)</script>");
        payload.put("eventType", "CHECKOUT_STALLED");
        payload.put("happenedAt", "2026-09-30T18:36:01Z");
        return AlertMessage.of(AlertKind.checkout_stalled, "Bookvas checkout stalled — Thandi's Nails",
                "Subscription checkout of R20.00 has had no PayFast confirmation for 20 minutes.", payload, "Bookvas");
    }

    @Test
    void htmlCarriesTitleDetailFactsAndButton() {
        String html = AlertEmailTemplate.html(stalled(), "https://rogue-pulse.netlify.app/");

        assertThat(html).contains("Bookvas checkout stalled");
        assertThat(html).contains("no PayFast confirmation for 20 minutes");
        assertThat(html).contains("Worth a look");                       // warning badge
        assertThat(html).contains("#B54708");                            // warning colour bar
        assertThat(html).contains("href=\"https://rogue-pulse.netlify.app/all\"");
        assertThat(html).contains(">Tenant<").contains(">Event type<"); // humanised labels
        assertThat(html).doesNotContain("bookvasEventId");               // ids are not facts
    }

    @Test
    void everyValueIsEscaped() {
        String html = AlertEmailTemplate.html(stalled(), null);

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("Thandi&#39;s Nails");
        assertThat(html).doesNotContain("Open Pulse");                    // no base url, no button
    }

    @Test
    void severityDrivesSubjectAndColour() {
        AlertMessage down = AlertMessage.of(AlertKind.site_down, "bookvas.co.za is down", "3 failures", Map.of(), "Bookvas");
        assertThat(AlertEmailTemplate.subject(down)).isEqualTo("[Pulse] URGENT: bookvas.co.za is down");
        assertThat(AlertEmailTemplate.html(down, "")).contains("#B42318").contains("Needs attention now");

        AlertMessage plain = AlertMessage.plain("Pulse — 2026-09-30", "Line one\n\nLine two");
        assertThat(AlertEmailTemplate.subject(plain)).isEqualTo("[Pulse] Pulse — 2026-09-30");
        assertThat(AlertEmailTemplate.html(plain, "")).contains("<p style=").contains("Line one").contains("Line two");
    }

    @Test
    void plainAlternativeReadsTopToBottom() {
        String text = AlertEmailTemplate.plain(stalled(), "https://rogue-pulse.netlify.app");

        assertThat(text).startsWith("Bookvas checkout stalled — Thandi's Nails\n");
        assertThat(text).contains("Bookvas · checkout stalled");
        assertThat(text).contains("Tenant: Thandi's Nails");
        assertThat(text).contains("Open Pulse: https://rogue-pulse.netlify.app/all");
    }

    @Test
    void labelsAreHumanised() {
        assertThat(AlertEmailTemplate.label("stalledLastHour")).isEqualTo("Stalled last hour");
        assertThat(AlertEmailTemplate.label("founder_seats_used")).isEqualTo("Founder seats used");
    }
}
