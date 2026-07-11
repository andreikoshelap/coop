package com.gatto.coop.funds.service;


import com.gatto.coop.funds.broker.BrokerGateway;
import com.gatto.coop.funds.domain.Order;
import com.gatto.coop.funds.domain.OrderStatus;
import com.gatto.coop.funds.exception.BrokerTimeoutException;
import com.gatto.coop.funds.repository.OrderRepository;
import com.gatto.coop.funds.service.record.PlaceBuyCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The saga orchestrator.
 *
 * It is deliberately NOT @Transactional: each step commits on its own (via OrderSteps),
 * and the broker call happens BETWEEN transactions, never inside one. Holding a database
 * transaction open across a network call to a third party is exactly the pattern we ruled
 * out in the design (no locks held across the broker call).
 *
 * State flow:
 *   PENDING --reserve--> RESERVED --placeOrder--> SENT --executed--> SETTLED
 *                                              \--reject--> CANCELLED (compensate)
 *                                              \--timeout--> UNKNOWN (reconcile later)
 */
@Service
public class OrderSagaService {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaService.class);

    private final OrderSteps steps;
    private final BrokerGateway broker;
    private final OrderRepository orders;

    public OrderSagaService(OrderSteps steps, BrokerGateway broker, OrderRepository orders) {
        this.steps = steps;
        this.broker = broker;
        this.orders = orders;
    }

    /** Drive the buy saga up to SENT / UNKNOWN / CANCELLED. */
    public OrderStatus placeBuyOrder(PlaceBuyCommand cmd) {
        // Idempotency at the saga level: re-driving an existing order returns its state.
        var existing = orders.findById(cmd.orderId());
        if (existing.isPresent()) {
            return existing.get().getStatus();
        }

        steps.createPending(cmd.orderId(), cmd.accountId(), cmd.isin(), cmd.amount(), cmd.currency());
        steps.reserveFunds(cmd.orderId(), cmd.accountId(), cmd.amount(), cmd.currency());

        // --- the external call happens here, outside any transaction ---
        BrokerGateway.BrokerAck ack;
        try {
            ack = broker.placeOrder(cmd.orderId(), cmd.isin(), cmd.amount());
        } catch (BrokerTimeoutException e) {
            // We do NOT know whether the broker accepted the order. Go to UNKNOWN and
            // let reconciliation resolve it. Never release the hold on a timeout.
            steps.markUnknown(cmd.orderId());
            log.warn("order {} -> UNKNOWN (broker timeout)", cmd.orderId());
            return OrderStatus.UNKNOWN;
        }

        if (ack == BrokerGateway.BrokerAck.ACCEPTED) {
            steps.markSent(cmd.orderId(), "broker-ref-" + cmd.orderId());
            return OrderStatus.SENT;
        } else {
            steps.releaseAndCancel(cmd.orderId());   // clean, known rejection -> compensate
            log.info("order {} -> CANCELLED (broker rejected)", cmd.orderId());
            return OrderStatus.CANCELLED;
        }
    }

    /** Inbound async execution report from the broker (idempotent). */
    public void onExecutionReport(UUID orderId, BigDecimal actualAmount) {
        steps.settleFromExecution(orderId, actualAmount);
    }

    /**
     * Resolve ONE order stuck in UNKNOWN by asking the broker what really happened.
     * The broker query is done OUTSIDE the transaction; only the resulting decision is
     * applied transactionally.
     */
    public void reconcile(UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow();
        if (order.getStatus() != OrderStatus.UNKNOWN) {
            return;   // nothing to reconcile
        }

        BrokerGateway.BrokerOrderStatus status = broker.getOrderStatus(orderId);   // the honest source of truth
        switch (status) {
            case EXECUTED -> {
                steps.settleFromExecution(orderId, order.getAmount());
                log.info("order {} reconciled -> SETTLED", orderId);
            }
            case NOT_FOUND -> {
                steps.releaseAndCancel(orderId);
                log.info("order {} reconciled -> CANCELLED (broker never had it)", orderId);
            }
            case WORKING -> {
                // Still in flight. Leave it UNKNOWN and try again on the next pass.
                log.info("order {} still WORKING at broker; will retry", orderId);
            }
        }
    }
}
