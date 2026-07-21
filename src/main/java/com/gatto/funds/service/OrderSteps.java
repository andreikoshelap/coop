package com.gatto.funds.service;

import com.gatto.funds.domain.Order;
import com.gatto.funds.repository.OrderRepository;
import com.gatto.funds.service.record.OrderExecutedEvent;
import com.gatto.funds.service.record.ReserveCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The individual transactional steps of the saga, in their OWN bean (so Spring's @Transactional
 * proxy actually applies — self-invocation from the orchestrator would bypass it).
 */
@Service
public class OrderSteps {

    private final OrderRepository orders;
    private final ReservationService reservations;
    private final OutboxService outbox;

    public OrderSteps(OrderRepository orders, ReservationService reservations, OutboxService outbox) {
        this.orders = orders;
        this.reservations = reservations;
        this.outbox = outbox;
    }

    /**
     * Create the order AND reserve funds in ONE transaction.
     *
     * Both are local DB operations (no external call between them), so they belong in the same
     * transaction. If the reservation fails (e.g. insufficient funds), the whole thing rolls back
     * and NO order row is left behind. This is the fix for the orphaned-PENDING-order bug: a failed
     * reserve must not leave a half-created order that a later retry mistakes for "already done".
     *
     * The "no transaction across the broker call" rule applies to the broker call in the
     * orchestrator — not to these two purely-local steps.
     */
    @Transactional
    public void openAndReserve(UUID orderId, Long accountId, String isin,
                               BigDecimal amount, String currency) {
        Order order = orders.save(Order.pending(orderId, accountId, isin, amount, currency));
        reservations.reserve(new ReserveCommand(orderId, accountId, amount, currency));  // may throw → rolls back the insert
        order.markReserved();
    }

    @Transactional
    public void markSent(UUID orderId, String brokerRef) {
        load(orderId).markSent(brokerRef);
    }

    @Transactional
    public void markUnknown(UUID orderId) {
        // Deliberately do NOT release the hold. Funds stay frozen until reconciliation
        // learns the truth from the broker.
        load(orderId).markUnknown();
    }

    /**
     * Inbound execution report. Settling debits the account and closes the hold; flipping the
     * order to SETTLED and appending OrderExecuted all happen in ONE transaction (the outbox
     * guarantee — no state-changed-but-not-published gap). Idempotent on an already-settled order.
     */
    @Transactional
    public void settleFromExecution(UUID orderId, BigDecimal actualAmount) {
        Order order = load(orderId);
        if (order.isSettled()) {
            return;   // duplicate callback — ignore
        }
        reservations.settle(orderId, actualAmount);
        order.markSettled();

        UUID eventId = UUID.randomUUID();
        OrderExecutedEvent event = new OrderExecutedEvent(
            eventId, orderId, order.getAccountId(), order.getIsin(),
            actualAmount, order.getCurrency(), Instant.now());
        outbox.append(eventId, orderId, "OrderExecuted", event);
    }

    /** Compensation: release the hold and cancel the order, in one transaction. */
    @Transactional
    public void releaseAndCancel(UUID orderId) {
        reservations.release(orderId);
        load(orderId).markCancelled();
    }

    private Order load(UUID orderId) {
        return orders.findById(orderId)
            .orElseThrow(() -> new IllegalArgumentException("no order " + orderId));
    }
}
