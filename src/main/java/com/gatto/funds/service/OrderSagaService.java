package com.gatto.funds.service;

import com.gatto.funds.broker.BrokerGateway;
import com.gatto.funds.broker.BrokerGateway.BrokerAck;
import com.gatto.funds.broker.BrokerGateway.BrokerOrderStatus;
import com.gatto.funds.domain.Order;
import com.gatto.funds.domain.OrderStatus;
import com.gatto.funds.exception.BrokerTimeoutException;
import com.gatto.funds.repository.OrderRepository;
import com.gatto.funds.service.record.PlaceBuyCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * The saga orchestrator. NOT @Transactional: each step commits on its own (via OrderSteps),
 * and the broker call happens BETWEEN transactions, never inside one.
 *
 *   (create + reserve, one TX) -> RESERVED --placeOrder--> SENT --executed--> SETTLED
 *                                                       \--reject--> CANCELLED
 *                                                       \--timeout--> UNKNOWN (reconcile later)
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

    public OrderStatus placeBuyOrder(PlaceBuyCommand cmd) {
        Optional<Order> existing = orders.findById(cmd.orderId());

        if (existing.isEmpty()) {
            // Fresh order: create and reserve atomically. If the reserve fails (insufficient
            // funds), this throws and NOTHING is persisted — no orphan order.
            steps.openAndReserve(cmd.orderId(), cmd.accountId(), cmd.isin(), cmd.amount(), cmd.currency());
        } else {
            OrderStatus status = existing.get().getStatus();
            // Already past the reservation stage → idempotent no-op, return current state.
            // Only a RESERVED order that never got sent (e.g. a crash before the broker call)
            // is resumed by falling through to sendToBroker.
            if (status != OrderStatus.RESERVED) {
                return status;
            }
        }

        return sendToBroker(cmd.orderId(), cmd.isin(), cmd.amount());
    }

    private OrderStatus sendToBroker(UUID orderId, String isin, BigDecimal amount) {
        BrokerAck ack;
        try {
            ack = broker.placeOrder(orderId, isin, amount);   // external call, no transaction held
        } catch (BrokerTimeoutException e) {
            steps.markUnknown(orderId);   // we don't know if it was accepted — never release here
            log.warn("order {} -> UNKNOWN (broker timeout)", orderId);
            return OrderStatus.UNKNOWN;
        }

        if (ack == BrokerAck.ACCEPTED) {
            steps.markSent(orderId, "broker-ref-" + orderId);
            return OrderStatus.SENT;
        } else {
            steps.releaseAndCancel(orderId);   // known rejection -> compensate
            log.info("order {} -> CANCELLED (broker rejected)", orderId);
            return OrderStatus.CANCELLED;
        }
    }

    /** Inbound async execution report from the broker (idempotent). */
    public void onExecutionReport(UUID orderId, BigDecimal actualAmount) {
        steps.settleFromExecution(orderId, actualAmount);
    }

    /** Resolve ONE order stuck in UNKNOWN by asking the broker what really happened. */
    public void reconcile(UUID orderId) {
        Order order = orders.findById(orderId).orElseThrow();
        if (order.getStatus() != OrderStatus.UNKNOWN) {
            return;
        }

        BrokerOrderStatus status = broker.getOrderStatus(orderId);   // the honest source of truth
        switch (status) {
            case EXECUTED -> {
                steps.settleFromExecution(orderId, order.getAmount());
                log.info("order {} reconciled -> SETTLED", orderId);
            }
            case NOT_FOUND -> {
                steps.releaseAndCancel(orderId);
                log.info("order {} reconciled -> CANCELLED (broker never had it)", orderId);
            }
            case WORKING -> log.info("order {} still WORKING at broker; will retry", orderId);
        }
    }
}
