package com.gatto.funds.web;


import com.gatto.funds.broker.StubBrokerGateway;
import com.gatto.funds.domain.Order;
import com.gatto.funds.domain.OrderStatus;
import com.gatto.funds.repository.OrderRepository;
import com.gatto.funds.service.OrderSagaService;
import com.gatto.funds.service.record.PlaceBuyCommand;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Lets you drive the whole saga by hand from Swagger:
 *   1. POST /orders/broker-mode  — choose how the fake broker behaves
 *   2. POST /orders/buy          — run the saga; watch the returned status
 *   3. POST /orders/{id}/execution  — simulate the broker's async fill (for ACCEPT mode)
 *   4. POST /orders/{id}/reconcile  — resolve an UNKNOWN order by asking the broker
 *   5. GET  /orders/{id}         — inspect the order's state at any time
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderSagaService saga;
    private final OrderRepository orders;
    private final StubBrokerGateway broker;

    public OrderController(OrderSagaService saga, OrderRepository orders, StubBrokerGateway broker) {
        this.saga = saga;
        this.orders = orders;
        this.broker = broker;
    }

    public record BuyRequest(UUID orderId, Long accountId, String isin,
                             BigDecimal amount, String currency) {
    }

    public record ExecutionRequest(BigDecimal actualAmount) {
    }

    public record BrokerModeRequest(StubBrokerGateway.Mode mode) {
    }

    public record OrderView(UUID orderId, OrderStatus status, BigDecimal amount, String brokerRef) {
        static OrderView of(Order o) {
            return new OrderView(o.getOrderId(), o.getStatus(), o.getAmount(), o.getBrokerRef());
        }
    }

    @PostMapping("/broker-mode")
    public String setBrokerMode(@RequestBody BrokerModeRequest req) {
        broker.setMode(req.mode());
        return "broker mode = " + req.mode();
    }

    @PostMapping("/buy")
    public OrderView buy(@RequestBody BuyRequest req) {
        OrderStatus status = saga.placeBuyOrder(new PlaceBuyCommand(
            req.orderId(), req.accountId(), req.isin(), req.amount(), req.currency()));
        return new OrderView(req.orderId(), status, req.amount(), null);
    }

    @PostMapping("/{orderId}/execution")
    public OrderView execution(@PathVariable UUID orderId, @RequestBody ExecutionRequest req) {
        saga.onExecutionReport(orderId, req.actualAmount());
        return OrderView.of(orders.findById(orderId).orElseThrow());
    }

    @PostMapping("/{orderId}/reconcile")
    public OrderView reconcile(@PathVariable UUID orderId) {
        saga.reconcile(orderId);
        return OrderView.of(orders.findById(orderId).orElseThrow());
    }

    @GetMapping("/{orderId}")
    public OrderView get(@PathVariable UUID orderId) {
        return OrderView.of(orders.findById(orderId).orElseThrow());
    }
}
