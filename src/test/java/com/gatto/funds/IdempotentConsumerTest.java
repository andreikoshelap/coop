package com.gatto.funds;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gatto.funds.consumer.PositionProjectionConsumer;
import com.gatto.funds.event.EventEnvelope;
import com.gatto.funds.repository.PositionRepository;
import com.gatto.funds.repository.ProcessedEventRepository;
import com.gatto.funds.service.record.OrderExecutedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the consumer-side idempotency: delivering the SAME event twice (as at-least-once
 * delivery will) applies the position delta only once. Without the processed_event ledger,
 * the second delivery would double the position.
 */
@SpringBootTest(classes = FundsApplication.class)
@Import(IdempotentConsumerTest.Containers.class)
@Testcontainers(disabledWithoutDocker = true)
class IdempotentConsumerTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {
        @Container
        @ServiceConnection
        static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:16"));
    }

    @Autowired
    PositionProjectionConsumer consumer;

    @Autowired
    PositionRepository positions;

    @Autowired
    ProcessedEventRepository processed;

    @Autowired
    ObjectMapper json;

    @BeforeEach
    void resetProjection() {
        processed.deleteAll();
        positions.deleteAll();
    }

    @Test
    void deliveringTheSameEventTwiceAppliesThePositionOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        var payload = new OrderExecutedEvent(
            eventId, UUID.randomUUID(), 1L, "US0378331005",
            new BigDecimal("80.00"), "EUR", Instant.now());
        var envelope = new EventEnvelope(eventId, "OrderExecuted", json.writeValueAsString(payload));

        // First delivery applies the delta.
        consumer.handle(envelope);
        // Second delivery (a redelivery) must be ignored.
        consumer.handle(envelope);

        var position = positions.findByAccountIdAndIsin(1L, "US0378331005").orElseThrow();
        assertThat(position.getTotalAmount()).isEqualByComparingTo("80.00");   // not 160.00
    }
}
