package pulse_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** Single row, id = 1 (seeded in V2). */
@Entity
@Table(name = "app_setting")
@Getter
@Setter
public class AppSetting {

    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id = SINGLETON_ID;

    /** "whatsapp" | "email" — stored as text so the CHECK constraint is the only vocabulary owner. */
    @Column(name = "notification_channel", nullable = false)
    private String notificationChannel = "email";

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
