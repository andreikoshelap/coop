package com.gatto.funds;


import com.gatto.funds.broker.StubBrokerGateway;
import com.gatto.funds.FundsApplication;
import com.gatto.funds.domain.HoldStatus;
import com.gatto.funds.domain.OrderStatus;
import com.gatto.funds.repository.AccountHoldRepository;
import com.gatto.funds.repository.AccountRepository;
import com.gatto.funds.repository.OrderRepository;
import com.gatto.funds.repository.OutboxRepository;
import com.gatto.funds.service.OrderSagaService;
import com.gatto.funds.service.record.PlaceBuyCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The payoff of the whole exercise: proving that a broker timeout does NOT release the
 * hold, that the order parks in UNKNOWN, and that reconciliation — not the timeout —
 * decides the outcome.
 *
 * Account #1 starts with 100.00 EUR (V1__init.sql).
 */
@SpringBootTest(classes = FundsApplication.class)
@Import(TestcontainersConfiguration.class)
class OrderSagaTest {

    @Autowired
    OrderSagaService saga;

    @Autowired
    AccountHoldRepository holds;

    @Autowired
    AccountRepository accounts;

    @Autowired
    OrderRepository orders;

    @Autowired
    OutboxRepository outbox;

    @Autowired
    StubBrokerGateway broker;

    @BeforeEach
    void resetData() {
        outbox.deleteAll();
        orders.deleteAll();
        holds.deleteAll();
        accounts.updateBalance(1L, new BigDecimal("100.00"));
    }

    private PlaceBuyCommand buy(UUID orderId) {
        return new PlaceBuyCommand(orderId, 1L, "US0378331005", new BigDecimal("80.00"), "EUR");
    }

    /**
     * Broker accepts and executes: the happy path. Order ends SETTLED, money moves,
     * balance drops to 20.
     */
    @Test
    void happyPathSettlesAndDebits() {
        broker.setMode(StubBrokerGateway.Mode.ACCEPT_AND_EXECUTE);
        UUID orderId = UUID.randomUUID();

        OrderStatus afterPlace = saga.placeBuyOrder(buy(orderId));
        assertThat(afterPlace).isEqualTo(OrderStatus.SENT);

        // the broker's async fill arrives
        saga.onExecutionReport(orderId, new BigDecimal("80.00"));

        assertThat(holds.findByOrderIdAndStatus(orderId, HoldStatus.ACTIVE)).isEmpty();
        assertThat(accounts.findById(1L).orElseThrow().getBalance()).isEqualByComparingTo("20.00");
    }

    /**
     * THE CENTRAL TEST. Broker times out but actually executed. On timeout the order must
     * be UNKNOWN and the hold must STILL BE ACTIVE — no blind release. Reconciliation then
     * asks the broker, learns it executed, and settles.
     */
    @Test
    void timeoutParksInUnknownWithoutReleasing_thenReconciliationSettles() {
        broker.setMode(StubBrokerGateway.Mode.TIMEOUT_THEN_EXECUTE);
        UUID orderId = UUID.randomUUID();

        OrderStatus afterPlace = saga.placeBuyOrder(buy(orderId));

        // The order did not fail and did not settle — it is UNKNOWN...
        assertThat(afterPlace).isEqualTo(OrderStatus.UNKNOWN);
        // ...and — this is the whole point — the funds are STILL held, not released.
        assertThat(holds.findByOrderIdAndStatus(orderId, HoldStatus.ACTIVE)).isPresent();
        assertThat(holds.sumActiveByAccount(1L)).isEqualByComparingTo("80.00");

        // Reconciliation asks the broker, discovers the fill, and settles.
        saga.reconcile(orderId);

        assertThat(holds.findByOrderIdAndStatus(orderId, HoldStatus.ACTIVE)).isEmpty();
        assertThat(accounts.findById(1L).orElseThrow().getBalance()).isEqualByComparingTo("20.00");
    }

    /**
     * Broker times out and never received the order. Reconciliation must RELEASE the hold
     * and cancel — the opposite outcome, from the same UNKNOWN starting point. This pair of
     * tests is why a blind release on timeout is wrong: the timeout alone cannot tell them apart.
     */
    @Test
    void timeoutWithNoOrderAtBroker_reconciliationReleases() {
        broker.setMode(StubBrokerGateway.Mode.TIMEOUT_THEN_NOTHING);
        UUID orderId = UUID.randomUUID();

        assertThat(saga.placeBuyOrder(buy(orderId))).isEqualTo(OrderStatus.UNKNOWN);
        assertThat(holds.sumActiveByAccount(1L)).isEqualByComparingTo("80.00");   // still held

        saga.reconcile(orderId);

        // Broker never had it -> release, funds available again, balance untouched.
        assertThat(holds.findByOrderIdAndStatus(orderId, HoldStatus.ACTIVE)).isEmpty();
        assertThat(holds.sumActiveByAccount(1L)).isEqualByComparingTo("0.00");
        assertThat(accounts.findById(1L).orElseThrow().getBalance()).isEqualByComparingTo("100.00");
    }

    /**
     * Clean rejection: compensate immediately, no UNKNOWN involved.
     */
    @Test
    void brokerRejectionReleasesImmediately() {
        broker.setMode(StubBrokerGateway.Mode.REJECT);
        UUID orderId = UUID.randomUUID();

        assertThat(saga.placeBuyOrder(buy(orderId))).isEqualTo(OrderStatus.CANCELLED);
        assertThat(holds.sumActiveByAccount(1L)).isEqualByComparingTo("0.00");
        assertThat(accounts.findById(1L).orElseThrow().getBalance()).isEqualByComparingTo("100.00");
    }
}
