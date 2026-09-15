package pulse_api.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "deploy_event")
@Getter
@Setter
public class DeployEvent {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id")
    private UUID projectId;

    private String repo;

    @Column(nullable = false)
    private String sha;

    private String branch;

    private String message;

    private String environment;

    private String state;

    @Column(name = "deployed_at", nullable = false)
    private Instant deployedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Enums.DeploySource source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
