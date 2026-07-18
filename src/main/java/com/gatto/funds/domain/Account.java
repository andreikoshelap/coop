package com.gatto.funds.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "account")
public class Account {

    @Id
    private Long id;

    @Column(nullable = false)
    private BigDecimal balance;

    @Column(nullable = false, length = 3)
    private String currency;

    protected Account() {
        // for JPA
    }

    public Long getId() {
        return id;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public String getCurrency() {
        return currency;
    }

    /**
     * Real debit — moves money out of the account. Called at settlement,
     * after the broker has confirmed the trade.
     */
    public void debit(BigDecimal amount) {
        if (balance.compareTo(amount) < 0) {
            // Should never happen if a hold guarded this amount, but the
            // invariant is cheap to assert and expensive to violate.
            throw new IllegalStateException("balance would go negative on debit");
        }
        this.balance = this.balance.subtract(amount);
    }
}
