package pulse_api.alerts;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pulse_api.entity.AppSetting;
import pulse_api.entity.Enums;
import pulse_api.repository.AppSettingRepository;

/**
 * Routes a message to a channel and falls back when that channel is not configured:
 * whatsapp → (settings default) → email → in-app only. Records what was actually used so
 * {@code alert_event.delivered} is truthful.
 */
@Component
@RequiredArgsConstructor
public class Notifier {

    public record Delivery(boolean delivered, String channelUsed) {}

    private final EmailNotifier email;
    private final WhatsAppNotifier whatsapp;
    private final AppSettingRepository settings;

    public boolean emailConfigured() {
        return email.configured();
    }

    public boolean whatsappConfigured() {
        return whatsapp.configured();
    }

    public Delivery notify(Enums.Channel requested, String title, String body) {
        return notify(requested, title, body, false);
    }

    /** {@code alsoEmail} = the spec's "WhatsApp + email" for site-down alerts. */
    public Delivery notify(Enums.Channel requested, String title, String body, boolean alsoEmail) {
        return notify(requested, AlertMessage.plain(title, body), alsoEmail);
    }

    /** A structured alert: WhatsApp gets the text, email gets the rendered card. */
    public Delivery notify(Enums.Channel requested, AlertMessage message, boolean alsoEmail) {
        Enums.Channel channel = resolve(requested);
        boolean delivered = false;
        String used = "in_app";
        if (channel == Enums.Channel.whatsapp) {
            delivered = whatsapp.send(message.asText());
            used = "whatsapp";
        } else if (channel == Enums.Channel.email) {
            delivered = sendEmail(message);
            used = "email";
        }
        if (alsoEmail && channel != Enums.Channel.email && email.configured()) {
            boolean emailed = sendEmail(message);
            if (emailed) {
                used = delivered ? used + "+email" : "email";
                delivered = true;
            }
        }
        return new Delivery(delivered, used);
    }

    private boolean sendEmail(AlertMessage message) {
        // a plain message (no kind) keeps its caller-chosen subject; an alert gets the severity prefix
        return message.kind() == null ? email.send(message.title(), message.detail()) : email.sendAlert(message);
    }

    Enums.Channel resolve(Enums.Channel requested) {
        if (requested == null || requested == Enums.Channel.in_app) {
            return Enums.Channel.in_app;
        }
        if (requested == Enums.Channel.whatsapp) {
            if (whatsapp.configured()) {
                return Enums.Channel.whatsapp;
            }
            String preferred = settings.findById(AppSetting.SINGLETON_ID).map(AppSetting::getNotificationChannel).orElse("email");
            if ("whatsapp".equals(preferred) && whatsapp.configured()) {
                return Enums.Channel.whatsapp;
            }
            return email.configured() ? Enums.Channel.email : Enums.Channel.in_app;
        }
        return email.configured() ? Enums.Channel.email : Enums.Channel.in_app;
    }
}
