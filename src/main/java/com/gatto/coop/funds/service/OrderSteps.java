package com.gatto.coop.funds.service;

import com.gatto.coop.funds.domain.Order;
import com.gatto.coop.funds.repository.OrderRepository;
import com.gatto.coop.funds.service.record.ReserveCommand;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The individual transactional steps of the saga. They live in their OWN bean, separate
 * from the orchestrator, on purpose: Spring's @Transactional works through a proxy, and a
 * proxy is bypassed on self-invocation (this.method()). If these methods were called from
 * within the orchestrator via `this`, the transactions would silently not apply. Calling
 * them on an injected collaborator makes the proxy — and therefore the transaction — real.
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

    @Transactional
    public void createPending(UUID orderId, Long accountId, String isin,
                              BigDecimal amount, String currency) {
        orders.save(Order.pending(orderId, accountId, isin, amount, currency));
    }

    /** reserve() joins THIS transaction (same DB), so the hold and the status change commit together. */
    @Transactional
    public void reserveFunds(UUID orderId, Long accountId, BigDecimal amount, String currency) {
        reservations.reserve(new ReserveCommand(orderId, accountId, amount, currency));
        load(orderId).markReserved();
    }

    @Transactional
    public void markSent(UUID orderId, String brokerRef) {
        load(orderId).markSent(brokerRef);
    }

    @Transactional
    public void markUnknown(UUID orderId) {
        // NOTE: we deliberately do NOT release the hold here. The funds stay frozen
        // until reconciliation learns the truth from the broker.
        load(orderId).markUnknown();
    }

    /**
     * The inbound execution report. Settling debits the account and closes the hold;
     * flipping the order to SETTLED and appending OrderExecuted all happen in ONE
     * transaction (the outbox guarantee — no state-changed-but-not-published gap).
     *
     * Idempotent: a duplicate execution report on an already-settled order is a no-op.
     */
    @Transactional
    public void settleFromExecution(UUID orderId, BigDecimal actualAmount) {
        Order order = load(orderId);
        if (order.isSettled()) {
            return;   // duplicate callback — ignore
        }
        reservations.settle(orderId, actualAmount);
        order.markSettled();
        outbox.append(orderId, "OrderExecuted",
            Map.of("orderId", orderId.toString(), "amount", actualAmount.toPlainString()));
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
