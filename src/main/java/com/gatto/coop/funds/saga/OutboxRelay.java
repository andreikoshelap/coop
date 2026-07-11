package com.gatto.coop.funds.saga;

import com.gatto.coop.funds.domain.OutboxEvent;
import com.gatto.coop.funds.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes outbound events from the outbox. In the real platform this ships them to Kafka
 * (or a CDC connector like Debezium does). Here it just logs and marks them published — the
 * mechanism is identical, only the sink is a log line instead of a topic.
 *
 * Delivery is at-least-once: if the process dies after publishing but before marking the row,
 * the event is re-published on the next pass. That is precisely why downstream consumers must
 * be idempotent.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;

    public OutboxRelay(OutboxRepository outbox) {
        this.outbox = outbox;
    }

    @Scheduled(fixedDelayString = "${outbox.interval-ms:2000}")
    @Transactional
    public void publishPending() {
        for (OutboxEvent event : outbox.findByPublishedAtIsNullOrderByCreatedAtAsc(Limit.of(100))) {
            // "publish" — in reality: producer.send(topic, event.getPayload())
            log.info("PUBLISH {} for {} :: {}",
                event.getEventType(), event.getAggregateId(), event.getPayload());
            event.markPublished();
        }
    }
}
