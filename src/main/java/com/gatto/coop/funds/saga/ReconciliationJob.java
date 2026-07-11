package com.gatto.coop.funds.saga;

import com.gatto.coop.funds.domain.Order;
import com.gatto.coop.funds.domain.OrderStatus;
import com.gatto.coop.funds.repository.OrderRepository;
import com.gatto.coop.funds.service.OrderSagaService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically resolves orders stuck in UNKNOWN by reconciling each against the broker.
 *
 * In production this job MUST run exactly once across the cluster — otherwise two
 * instances reconcile the same order concurrently. That is what ShedLock is for; adding
 * it is the next step. For the single-instance prototype, a plain @Scheduled is enough.
 */
@Component
public class ReconciliationJob {

    private final OrderRepository orders;
    private final OrderSagaService saga;

    public ReconciliationJob(OrderRepository orders, OrderSagaService saga) {
        this.orders = orders;
        this.saga = saga;
    }

    @Scheduled(fixedDelayString = "${reconciliation.interval-ms:5000}")
    public void run() {
        for (Order order : orders.findByStatus(OrderStatus.UNKNOWN)) {
            saga.reconcile(order.getOrderId());
        }
    }
}
