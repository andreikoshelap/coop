package com.gatto.funds.service.record;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The OrderExecuted event payload — event-carried state transfer (section 3.5): it carries
 * everything a consumer needs so no one has to call back to the Order service for detail.
 *
 * eventId is the idempotency key for consumers. It equals the outbox row id, so the same
 * logical event always carries the same eventId across redeliveries.
 *
 * amount is a String on the wire on purpose (section 10): a JSON number would be parsed as a
 * double somewhere downstream and lose cents.
 */
public record OrderExecutedEvent(
    UUID eventId,
    UUID orderId,
    Long accountId,
    String isin,
    BigDecimal amount,
    String currency,
    Instant executedAt
) {
}
