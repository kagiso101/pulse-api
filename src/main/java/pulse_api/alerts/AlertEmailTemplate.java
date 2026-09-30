package pulse_api.alerts;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The one email look for everything Pulse sends: a narrow card, a severity-coloured bar, the
 * title, the detail in plain English, the payload as a small facts table, and a button into
 * Pulse. Inline styles only — mail clients strip everything else. Every value is escaped.
 */
public final class AlertEmailTemplate {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")
            .withZone(ZoneId.of("Africa/Johannesburg"));
    private static final Pattern CAMEL = Pattern.compile("([a-z0-9])([A-Z])");

    // Pulse brand: deep green, with amber and red for the two escalations
    private static final String GREEN = "#0E5A45";
    private static final String AMBER = "#B54708";
    private static final String RED = "#B42318";
    private static final String INK = "#1c201e";
    private static final String MUTED = "#5f6663";
    private static final String LINE = "#e3e6e2";
    private static final String PAPER = "#f4f5f3";

    private AlertEmailTemplate() {}

    public static String subject(AlertMessage m) {
        String prefix = switch (m.severity()) {
            case critical -> "[Pulse] URGENT: ";
            case warning -> "[Pulse] ";
            case info -> "[Pulse] ";
        };
        return prefix + m.title();
    }

    public static String html(AlertMessage m, String webBaseUrl) {
        String colour = switch (m.severity()) {
            case critical -> RED;
            case warning -> AMBER;
            case info -> GREEN;
        };
        String badge = switch (m.severity()) {
            case critical -> "Needs attention now";
            case warning -> "Worth a look";
            case info -> "For the record";
        };
        String kind = m.kind() == null ? "" : m.kind().name().replace('_', ' ');
        String where = m.projectName() == null ? "" : m.projectName();
        String eyebrow = esc(join(" · ", where, kind));
        String link = webBaseUrl == null || webBaseUrl.isBlank() ? null : webBaseUrl.replaceAll("/+$", "") + "/all";

        StringBuilder sb = new StringBuilder(4096);
        sb.append("<!doctype html><html><body style=\"margin:0;padding:0;background:").append(PAPER)
          .append(";font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;color:").append(INK).append(";\">")
          .append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"background:").append(PAPER).append(";padding:32px 12px;\"><tr><td align=\"center\">")
          .append("<table role=\"presentation\" width=\"560\" cellspacing=\"0\" cellpadding=\"0\" style=\"max-width:560px;width:100%;background:#ffffff;border:1px solid ").append(LINE).append(";border-radius:14px;overflow:hidden;\">")
          // brand row
          .append("<tr><td style=\"padding:18px 28px 0 28px;\">")
          .append("<span style=\"display:inline-block;font-weight:700;font-size:15px;letter-spacing:0.02em;color:").append(GREEN).append(";\">Pulse</span>")
          .append("<span style=\"display:inline-block;margin-left:10px;font-size:12px;color:").append(MUTED).append(";\">by ROGUETECHNOLOGIES</span>")
          .append("</td></tr>")
          // severity bar + badge
          .append("<tr><td style=\"padding:16px 28px 0 28px;\">")
          .append("<div style=\"height:4px;border-radius:4px;background:").append(colour).append(";\"></div>")
          .append("<div style=\"margin-top:12px;font-size:11px;font-weight:700;letter-spacing:0.08em;text-transform:uppercase;color:").append(colour).append(";\">").append(esc(badge)).append("</div>")
          .append("</td></tr>")
          // title + eyebrow
          .append("<tr><td style=\"padding:10px 28px 0 28px;\">")
          .append("<h1 style=\"margin:0;font-size:22px;line-height:1.3;font-weight:700;color:").append(INK).append(";\">").append(esc(m.title())).append("</h1>");
        if (!eyebrow.isBlank()) {
            sb.append("<div style=\"margin-top:6px;font-size:13px;color:").append(MUTED).append(";\">").append(eyebrow).append("</div>");
        }
        sb.append("</td></tr>");
        // detail
        if (!m.detail().isBlank()) {
            sb.append("<tr><td style=\"padding:18px 28px 0 28px;font-size:15px;line-height:1.55;color:").append(INK).append(";\">")
              .append(paragraphs(m.detail()))
              .append("</td></tr>");
        }
        // facts table
        Map<String, String> facts = facts(m);
        if (!facts.isEmpty()) {
            sb.append("<tr><td style=\"padding:20px 28px 0 28px;\">")
              .append("<table role=\"presentation\" width=\"100%\" cellspacing=\"0\" cellpadding=\"0\" style=\"border-top:1px solid ").append(LINE).append(";\">");
            for (Map.Entry<String, String> f : facts.entrySet()) {
                sb.append("<tr>")
                  .append("<td style=\"padding:9px 0;border-bottom:1px solid ").append(LINE).append(";font-size:12px;text-transform:uppercase;letter-spacing:0.04em;color:").append(MUTED).append(";width:44%;vertical-align:top;\">").append(esc(f.getKey())).append("</td>")
                  .append("<td style=\"padding:9px 0;border-bottom:1px solid ").append(LINE).append(";font-size:14px;color:").append(INK).append(";vertical-align:top;word-break:break-word;\">").append(esc(f.getValue())).append("</td>")
                  .append("</tr>");
            }
            sb.append("</table></td></tr>");
        }
        // button
        if (link != null) {
            sb.append("<tr><td style=\"padding:24px 28px 0 28px;\">")
              .append("<a href=\"").append(esc(link)).append("\" style=\"display:inline-block;background:").append(GREEN)
              .append(";color:#ffffff;text-decoration:none;font-weight:600;font-size:14px;padding:11px 18px;border-radius:10px;\">Open Pulse</a>")
              .append("</td></tr>");
        }
        // footer
        sb.append("<tr><td style=\"padding:26px 28px 22px 28px;font-size:12px;line-height:1.5;color:").append(MUTED).append(";\">")
          .append(esc(WHEN.format(m.at()))).append(" SAST · ")
          .append("You get this because a Pulse alert rule is on the email channel. Change it under Settings in Pulse.")
          .append("</td></tr>")
          .append("</table></td></tr></table></body></html>");
        return sb.toString();
    }

    /** The text/plain alternative — same content, no markup. */
    public static String plain(AlertMessage m, String webBaseUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.title()).append('\n');
        if (m.projectName() != null || m.kind() != null) {
            sb.append(join(" · ", m.projectName() == null ? "" : m.projectName(),
                    m.kind() == null ? "" : m.kind().name().replace('_', ' '))).append('\n');
        }
        sb.append('\n');
        if (!m.detail().isBlank()) {
            sb.append(m.detail()).append("\n\n");
        }
        for (Map.Entry<String, String> f : facts(m).entrySet()) {
            sb.append(f.getKey()).append(": ").append(f.getValue()).append('\n');
        }
        if (webBaseUrl != null && !webBaseUrl.isBlank()) {
            sb.append('\n').append("Open Pulse: ").append(webBaseUrl.replaceAll("/+$", "")).append("/all\n");
        }
        sb.append('\n').append(WHEN.format(m.at())).append(" SAST — Pulse by ROGUETECHNOLOGIES\n");
        return sb.toString();
    }

    /** Payload entries as readable label/value pairs, skipping ids nobody reads in an email. */
    static Map<String, String> facts(AlertMessage m) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.payload().entrySet()) {
            String key = e.getKey();
            if (key == null || e.getValue() == null) continue;
            if (key.endsWith("Id") || key.equals("id") || key.equals("projectId")) continue;
            String value = String.valueOf(e.getValue());
            if (value.isBlank() || "null".equals(value)) continue;
            out.put(label(key), value);
        }
        return out;
    }

    static String label(String key) {
        String spaced = CAMEL.matcher(key).replaceAll("$1 $2").replace('_', ' ').toLowerCase();
        return spaced.isEmpty() ? key : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private static String paragraphs(String text) {
        StringBuilder sb = new StringBuilder();
        for (String para : text.split("\\n\\s*\\n")) {
            sb.append("<p style=\"margin:0 0 10px 0;\">").append(esc(para).replace("\n", "<br>")).append("</p>");
        }
        return sb.toString();
    }

    private static String join(String sep, String a, String b) {
        if (a.isBlank()) return b;
        if (b.isBlank()) return a;
        return a + sep + b;
    }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
