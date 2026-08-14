package org.bgm.notificationservice.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "processed_event")
public class ProcessedEvent {
    @Id
    private String eventId;
    private Instant processedAt;
}
