package com.gatto.coop;


import com.gatto.coop.funds.domain.HoldStatus;
import com.gatto.coop.funds.exception.InsufficientFundsException;
import com.gatto.coop.funds.repository.AccountHoldRepository;
import com.gatto.coop.funds.service.ReserveCommand;
import com.gatto.coop.funds.service.ReservationResult;
import com.gatto.coop.funds.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * These tests run against a REAL PostgreSQL (Testcontainers), not H2, because the
 * whole point — SELECT FOR UPDATE and the partial UNIQUE index — does not behave
 * the same on an in-memory database. This is the payoff: you can watch the two
 * races happen and watch the two guarantees stop them.
 *
 * Account #1 starts with 100.00 EUR (see V1__init.sql).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ReservationServiceConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16"));

    @DynamicPropertySource
    static void flyway(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    ReservationService service;

    @Autowired
    AccountHoldRepository holds;

    @BeforeEach
    void clearHolds() {
        holds.deleteAll();
    }

    /**
     * RACE #1 — double-spend. Two DIFFERENT orders, same account, each wants 80 EUR,
     * but the account only holds 100. Exactly one must succeed; the other must be
     * rejected for insufficient funds. The account-row lock is what enforces this.
     */
    @Test
    void twoOrdersCannotBothReserveMoreThanTheBalance() throws Exception {
        int threads = 2;
        var pool = Executors.newFixedThreadPool(threads);

        List<Callable<Boolean>> tasks = List.of(
            reserveTask(UUID.randomUUID(), new BigDecimal("80.00")),
            reserveTask(UUID.randomUUID(), new BigDecimal("80.00"))
        );

        int succeeded = 0;
        int rejected = 0;
        for (Future<Boolean> f : pool.invokeAll(tasks)) {
            if (f.get()) succeeded++;
            else rejected++;
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);   // only one 80 EUR hold fits into 100
        assertThat(rejected).isEqualTo(1);
        assertThat(holds.sumActiveByAccount(1L)).isEqualByComparingTo("80.00");
    }

    /**
     * RACE #2 — double-hold. The SAME order is submitted many times at once
     * (as happens on a network retry storm). There must end up with exactly ONE
     * hold, and every call must return the same reservationId. The idempotency
     * key + UNIQUE index enforce this.
     */
    @Test
    void sameOrderRetriedConcurrentlyCreatesExactlyOneHold() throws Exception {
        UUID orderId = UUID.randomUUID();
        int attempts = 16;
        var pool = Executors.newFixedThreadPool(attempts);

        List<Callable<ReservationResult>> tasks = new ArrayList<>();
        for (int i = 0; i < attempts; i++) {
            tasks.add(() -> service.reserve(
                new ReserveCommand(orderId, 1L, new BigDecimal("80.00"), "EUR")));
        }

        var reservationIds = new ArrayList<UUID>();
        for (Future<ReservationResult> f : pool.invokeAll(tasks)) {
            reservationIds.add(f.get().reservationId());
        }
        pool.shutdown();

        // Every one of the 16 concurrent calls returned the same reservation...
        assertThat(reservationIds).hasSize(attempts);
        assertThat(reservationIds).containsOnly(reservationIds.get(0));
        // ...and there is exactly one ACTIVE hold in the database.
        assertThat(holds.findByOrderIdAndStatus(orderId, HoldStatus.ACTIVE)).isPresent();
        assertThat(holds.sumActiveByAccount(1L)).isEqualByComparingTo("80.00");
    }

    private Callable<Boolean> reserveTask(UUID orderId, BigDecimal amount) {
        return () -> {
            try {
                service.reserve(new ReserveCommand(orderId, 1L, amount, "EUR"));
                return true;
            } catch (InsufficientFundsException e) {
                return false;
            }
        };
    }
}
