package pulse_api.alerts;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Email through the Brevo SMTP relay (same env names as bookr-api). Spring only creates a
 * JavaMailSender when spring.mail.host is set, so without SMTP config this is "not configured".
 *
 * Every message goes out as multipart text+HTML through {@link AlertEmailTemplate}, so alerts
 * and the daily summary share one branded look (OPS-VISIBILITY, 2026-09-30).
 */
@Slf4j
@Component
public class EmailNotifier {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;
    private final String to;
    private final String webBaseUrl;

    public EmailNotifier(ObjectProvider<JavaMailSender> mailSender,
                         @Value("${app.mail.from}") String from,
                         @Value("${app.mail.to:}") String to,
                         @Value("${app.web.base-url:}") String webBaseUrl) {
        this.mailSender = mailSender;
        this.from = from;
        this.to = to == null ? "" : to.trim();
        this.webBaseUrl = webBaseUrl == null ? "" : webBaseUrl.trim();
    }

    public boolean configured() {
        return mailSender.getIfAvailable() != null && !to.isBlank();
    }

    /** A non-alert email (the daily summary): the body is shown as-is inside the card. */
    public boolean send(String subject, String body) {
        return sendAlert(AlertMessage.plain(subject, body), subject);
    }

    /** An alert, rendered with severity colour, facts table and a button into Pulse. */
    public boolean sendAlert(AlertMessage message) {
        return sendAlert(message, AlertEmailTemplate.subject(message));
    }

    /** Never throws — a broken relay must not stop the job that triggered it. */
    private boolean sendAlert(AlertMessage message, String subject) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || to.isBlank()) {
            return false;
        }
        try {
            MimeMessage mime = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(AlertEmailTemplate.plain(message, webBaseUrl), AlertEmailTemplate.html(message, webBaseUrl));
            sender.send(mime);
            return true;
        } catch (Exception e) {
            log.warn("Email notification failed: {}", e.toString());
            return false;
        }
    }
}
