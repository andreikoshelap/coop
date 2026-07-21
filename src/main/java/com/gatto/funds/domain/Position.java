package com.gatto.funds.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "position")
public class Position {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(nullable = false)
    private String isin;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    protected Position() {
        // for JPA
    }

    private Position(Long accountId, String isin) {
        this.id = UUID.randomUUID();
        this.accountId = accountId;
        this.isin = isin;
        this.totalAmount = BigDecimal.ZERO;
    }

    public static Position zero(Long accountId, String isin) {
        return new Position(accountId, isin);
    }

    /** Delta-apply. This is exactly the operation that is UNSAFE to run twice — hence dedup. */
    public void add(BigDecimal amount) {
        this.totalAmount = this.totalAmount.add(amount);
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getIsin() {
        return isin;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }
}
