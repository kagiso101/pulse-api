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
        Enums.Channel channel = resolve(requested);
        boolean delivered = false;
        String used = "in_app";
        if (channel == Enums.Channel.whatsapp) {
            delivered = whatsapp.send(title + "\n" + body);
            used = "whatsapp";
        } else if (channel == Enums.Channel.email) {
            delivered = email.send(title, body);
            used = "email";
        }
        if (alsoEmail && channel != Enums.Channel.email && email.configured()) {
            boolean emailed = email.send(title, body);
            if (emailed) {
                used = delivered ? used + "+email" : "email";
                delivered = true;
            }
        }
        return new Delivery(delivered, used);
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
