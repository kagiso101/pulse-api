package pulse_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "prospect")
@Getter
@Setter
public class Prospect {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String business;

    private String phone;

    private String area;

    @Column(name = "has_website")
    private Boolean hasWebsite;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Enums.ProspectStatus status = Enums.ProspectStatus.to_contact;

    @Column(name = "next_action")
    private String nextAction;

    @Column(name = "next_action_date")
    private LocalDate nextActionDate;

    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}
