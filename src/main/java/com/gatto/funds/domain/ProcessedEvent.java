package com.gatto.funds.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_event")
public class ProcessedEvent {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String consumer;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEvent() {
        // for JPA
    }

    private ProcessedEvent(String consumer, UUID eventId) {
        this.id = UUID.randomUUID();
        this.consumer = consumer;
        this.eventId = eventId;
        this.processedAt = Instant.now();
    }

    public static ProcessedEvent of(String consumer, UUID eventId) {
        return new ProcessedEvent(consumer, eventId);
    }
}
