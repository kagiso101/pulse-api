package pulse_api.alerts;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Plain-text email through the Brevo SMTP relay (same env names as bookr-api). Spring only creates
 * a JavaMailSender when spring.mail.host is set, so without SMTP config this is "not configured".
 */
@Slf4j
@Component
public class EmailNotifier {

    private final ObjectProvider<JavaMailSender> mailSender;
    private final String from;
    private final String to;

    public EmailNotifier(ObjectProvider<JavaMailSender> mailSender,
                         @Value("${app.mail.from}") String from,
                         @Value("${app.mail.to:}") String to) {
        this.mailSender = mailSender;
        this.from = from;
        this.to = to == null ? "" : to.trim();
    }

    public boolean configured() {
        return mailSender.getIfAvailable() != null && !to.isBlank();
    }

    /** Never throws — a broken relay must not stop the job that triggered it. */
    public boolean send(String subject, String body) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || to.isBlank()) {
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            sender.send(message);
            return true;
        } catch (Exception e) {
            log.warn("Email notification failed: {}", e.toString());
            return false;
        }
    }
}
