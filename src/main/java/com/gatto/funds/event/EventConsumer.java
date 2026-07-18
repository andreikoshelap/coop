package com.gatto.funds.event;

/**
 * A consumer of published events. In production each of these is a separate service reading
 * from Kafka with its own offset; here they are beans the relay calls in-process. Each must
 * be idempotent, because delivery is at-least-once.
 */
public interface EventConsumer {

    /** Stable name, used as the scope for this consumer's processed-event ledger. */
    String name();

    void handle(EventEnvelope envelope);
}
