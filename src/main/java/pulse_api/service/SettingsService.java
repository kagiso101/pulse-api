package pulse_api.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pulse_api.alerts.Notifier;
import pulse_api.ask.AnthropicClientFactory;
import pulse_api.connector.BillingConnector;
import pulse_api.connector.BookvasConnector;
import pulse_api.connector.CloudRunConnector;
import pulse_api.connector.Ga4DataConnector;
import pulse_api.connector.NetlifyConnector;
import pulse_api.dto.SettingsDtos.SettingsDto;
import pulse_api.entity.AppSetting;
import pulse_api.repository.AppSettingRepository;

import java.time.Instant;

/** Contract §2.11 — the single settings row plus each connector's configured() flag. */
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final AppSettingRepository settings;
    private final AuthService auth;
    private final Notifier notifier;
    private final Ga4DataConnector ga4;
    private final BookvasConnector bookvas;
    private final NetlifyConnector netlify;
    private final CloudRunConnector cloudRun;
    private final BillingConnector billing;
    private final AnthropicClientFactory anthropic;

    @Transactional(readOnly = true)
    public SettingsDto get() {
        AppSetting row = settings.findById(AppSetting.SINGLETON_ID).orElseGet(AppSetting::new);
        return new SettingsDto(auth.allowedEmailMasked(), row.getNotificationChannel(),
                notifier.whatsappConfigured(), notifier.emailConfigured(), ga4.configured(), bookvas.configured(),
                netlify.configured(), cloudRun.configured(), billing.configured(), anthropic.configured(),
                RangeResolver.ZONE.getId());
    }

    @Transactional
    public SettingsDto update(String notificationChannel) {
        AppSetting row = settings.findById(AppSetting.SINGLETON_ID).orElseGet(AppSetting::new);
        row.setId(AppSetting.SINGLETON_ID);
        row.setNotificationChannel(notificationChannel);
        row.setUpdatedAt(Instant.now());
        settings.save(row);
        return get();
    }
}
