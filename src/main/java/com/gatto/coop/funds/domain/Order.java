package com.gatto.coop.funds.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false)
    private String isin;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "broker_ref")
    private String brokerRef;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Order() {
        // for JPA
    }

    private Order(UUID orderId, Long accountId, String isin, BigDecimal amount, String currency) {
        this.orderId = orderId;
        this.accountId = accountId;
        this.isin = isin;
        this.amount = amount;
        this.currency = currency;
        this.status = OrderStatus.PENDING;
    }

    public static Order pending(UUID orderId, Long accountId, String isin,
                                BigDecimal amount, String currency) {
        return new Order(orderId, accountId, isin, amount, currency);
    }

    // --- transitions. Each guards its allowed source states, so an illegal
    //     transition fails loudly instead of silently corrupting the saga. ---

    public void markReserved() {
        transition(OrderStatus.RESERVED, EnumSet.of(OrderStatus.PENDING));
    }

    public void markSent(String brokerRef) {
        transition(OrderStatus.SENT, EnumSet.of(OrderStatus.RESERVED));
        this.brokerRef = brokerRef;
    }

    public void markUnknown() {
        // From RESERVED (we timed out sending) — we did our part, the broker's answer is missing.
        transition(OrderStatus.UNKNOWN, EnumSet.of(OrderStatus.RESERVED, OrderStatus.SENT));
    }

    public void markSettled() {
        // Reachable from SENT (normal) or UNKNOWN (resolved by reconciliation).
        transition(OrderStatus.SETTLED, EnumSet.of(OrderStatus.SENT, OrderStatus.UNKNOWN));
    }

    public void markCancelled() {
        transition(OrderStatus.CANCELLED,
            EnumSet.of(OrderStatus.PENDING, OrderStatus.RESERVED, OrderStatus.UNKNOWN));
    }

    private void transition(OrderStatus target, Set<OrderStatus> allowedFrom) {
        if (!allowedFrom.contains(this.status)) {
            throw new IllegalStateException(
                "illegal transition " + this.status + " -> " + target);
        }
        this.status = target;
    }

    public boolean isSettled() {
        return status == OrderStatus.SETTLED;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getIsin() {
        return isin;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getBrokerRef() {
        return brokerRef;
    }
}
