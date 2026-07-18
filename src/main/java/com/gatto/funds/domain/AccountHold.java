package com.gatto.funds.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account_hold")
public class AccountHold {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HoldStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected AccountHold() {
        // for JPA
    }

    private AccountHold(UUID orderId, Long accountId, BigDecimal amount,
                        String currency, Instant expiresAt) {
        this.id = UUID.randomUUID();
        this.orderId = orderId;
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
        this.status = HoldStatus.ACTIVE;
        this.expiresAt = expiresAt;
    }

    public static AccountHold active(UUID orderId, Long accountId, BigDecimal amount,
                                     String currency, Instant expiresAt) {
        return new AccountHold(orderId, accountId, amount, currency, expiresAt);
    }

    /** Card-authorization analogy: the authorization becomes a real charge. */
    public void settle() {
        requireActive();
        this.status = HoldStatus.SETTLED;
    }

    /** Card-authorization analogy: the authorization is dropped, funds freed. */
    public void release() {
        requireActive();
        this.status = HoldStatus.RELEASED;
    }

    private void requireActive() {
        if (status != HoldStatus.ACTIVE) {
            throw new IllegalStateException(
                "hold " + id + " is " + status + ", expected ACTIVE");
        }
    }

    public UUID getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
