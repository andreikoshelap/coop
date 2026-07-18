package com.gatto.funds.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatto.funds.domain.OutboxEvent;
import com.gatto.funds.repository.OutboxRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Appends outbound events. Always called INSIDE the same @Transactional method that
 * changed the state, so the state change and the event commit together — no dual write.
 *
 * The caller supplies the eventId so the same value is both the outbox row id and the
 * eventId inside the payload — one identity for the event, used by consumers to dedup.
 */
@Service
public class OutboxService {

    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public OutboxService(OutboxRepository outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    public void append(UUID eventId, UUID aggregateId, String eventType, Object payload) {
        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("cannot serialize event payload", e);
        }
        outbox.save(OutboxEvent.of(eventId, aggregateId, eventType, body));
    }
}
