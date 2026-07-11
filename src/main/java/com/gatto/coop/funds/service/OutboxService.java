package com.gatto.coop.funds.service;

import com.gatto.coop.funds.domain.OutboxEvent;
import com.gatto.coop.funds.repository.OutboxRepository;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Appends outbound events. Always called INSIDE the same @Transactional method that
 * changed the state, so the state change and the event commit together — no dual write.
 */
@Service
public class OutboxService {

    private final OutboxRepository outbox;
    private final ObjectMapper json;

    public OutboxService(OutboxRepository outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    public void append(UUID aggregateId, String eventType, Object payload) {
        String body;
        try {
            body = json.writeValueAsString(payload);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("cannot serialize event payload", e);
        }
        outbox.save(OutboxEvent.of(aggregateId, eventType, body));
    }
}
