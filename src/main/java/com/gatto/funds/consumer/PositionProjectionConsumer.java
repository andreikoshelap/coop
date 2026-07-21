package com.gatto.funds.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatto.funds.domain.Position;
import com.gatto.funds.domain.ProcessedEvent;
import com.gatto.funds.event.EventConsumer;
import com.gatto.funds.event.EventEnvelope;
import com.gatto.funds.repository.PositionRepository;
import com.gatto.funds.repository.ProcessedEventRepository;
import com.gatto.funds.service.record.OrderExecutedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the position read model from OrderExecuted events. Idempotent by eventId.
 *
 * Why dedup is mandatory here: the work is position.add(amount) — a DELTA. Applying it twice
 * doubles the position. Delivery is at-least-once, so duplicates WILL happen. The processed_event
 * ledger turns "apply the delta" into "apply the delta at most once per event".
 *
 * The existsBy... check is the optimization; the UNIQUE (consumer, event_id) constraint is the
 * guarantee. Same principle as the reservation's idempotency (section 6.4): DB guarantees,
 * application optimizes.
 */
@Component
public class PositionProjectionConsumer implements EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PositionProjectionConsumer.class);
    private static final String NAME = "position-projection";

    private final ProcessedEventRepository processed;
    private final PositionRepository positions;
    private final ObjectMapper json;

    public PositionProjectionConsumer(ProcessedEventRepository processed,
                                      PositionRepository positions,
                                      ObjectMapper json) {
        this.processed = processed;
        this.positions = positions;
        this.json = json;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    @Transactional
    public void handle(EventEnvelope envelope) {
        if (!"OrderExecuted".equals(envelope.type())) {
            return;   // not our event
        }

        // Dedup: if we've already processed this event, do nothing. The delta must not repeat.
        if (processed.existsByConsumerAndEventId(NAME, envelope.eventId())) {
            log.info("[{}] duplicate event {} ignored", NAME, envelope.eventId());
            return;
        }

        OrderExecutedEvent e = parse(envelope.payload());

        Position position = positions.findByAccountIdAndIsin(e.accountId(), e.isin())
            .orElseGet(() -> Position.zero(e.accountId(), e.isin()));
        position.add(e.amount());
        positions.save(position);

        // Record that we've handled it — in the SAME transaction as the position update,
        // so either both commit or neither does.
        processed.save(ProcessedEvent.of(NAME, envelope.eventId()));

        log.info("[{}] applied {} to position {}/{} -> {}",
            NAME, e.amount(), e.accountId(), e.isin(), position.getTotalAmount());
    }

    private OrderExecutedEvent parse(String payload) {
        try {
            return json.readValue(payload, OrderExecutedEvent.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException("bad OrderExecuted payload", ex);
        }
    }
}
