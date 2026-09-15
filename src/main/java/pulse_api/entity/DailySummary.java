package pulse_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "daily_summary")
@Getter
@Setter
public class DailySummary {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "summary_date", nullable = false, unique = true)
    private LocalDate summaryDate;

    @Column(nullable = false)
    private String body;

    private String channel;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
