package com.gatto.coop.funds.service;


import com.gatto.coop.funds.domain.Account;
import com.gatto.coop.funds.domain.AccountHold;
import com.gatto.coop.funds.domain.HoldStatus;
import com.gatto.coop.funds.exception.InsufficientFundsException;
import com.gatto.coop.funds.repository.AccountHoldRepository;
import com.gatto.coop.funds.repository.AccountRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class ReservationService {

    private static final Duration HOLD_TTL = Duration.ofMinutes(15);

    private final AccountRepository accounts;
    private final AccountHoldRepository holds;

    public ReservationService(AccountRepository accounts, AccountHoldRepository holds) {
        this.accounts = accounts;
        this.holds = holds;
    }

    /**
     * Reserve funds for an order. Idempotent by orderId.
     *
     * Two races are defended against here, by two different mechanisms:
     *   - double-spend (two orders, one account): the account row lock (FOR UPDATE)
     *   - double-hold  (one order, retried):      findByOrderId + the UNIQUE index
     */
    @Transactional
    public ReservationResult reserve(ReserveCommand cmd) {
        // Fast path (optimization): if we already reserved this order, return it.
        // Cheap, handles the overwhelming majority of retries without touching the lock.
        var existing = holds.findByOrderIdAndStatus(cmd.orderId(), HoldStatus.ACTIVE);
        if (existing.isPresent()) {
            return ReservationResult.from(existing.get());
        }

        // Lock the account row. Serializes all reservations on this account.
        Account account = accounts.lockById(cmd.accountId())
            .orElseThrow(() -> new IllegalArgumentException("no account " + cmd.accountId()));

        // Re-check under the lock: a concurrent retry of THIS order may have committed
        // while we were waiting for the lock. If so, return its hold — not "insufficient funds".
        existing = holds.findByOrderIdAndStatus(cmd.orderId(), HoldStatus.ACTIVE);
        if (existing.isPresent()) {
            return ReservationResult.from(existing.get());
        }

        // available_balance = balance - sum(active holds), computed under the lock.
        BigDecimal available = account.getBalance().subtract(holds.sumActiveByAccount(account.getId()));
        if (available.compareTo(cmd.amount()) < 0) {
            throw new InsufficientFundsException(cmd.orderId());
        }

        AccountHold hold = AccountHold.active(
            cmd.orderId(), account.getId(), cmd.amount(), cmd.currency(),
            Instant.now().plus(HOLD_TTL));

        try {
            holds.saveAndFlush(hold);   // flush now, so the UNIQUE index fires inside this method
        } catch (DataIntegrityViolationException race) {
            // Backstop: a concurrent retry beat us to the insert. The UNIQUE index rejected us.
            // Return the winner's hold instead of propagating the error.
            return ReservationResult.from(
                holds.findByOrderIdAndStatus(cmd.orderId(), HoldStatus.ACTIVE).orElseThrow());
        }

        return ReservationResult.from(hold);
    }

    /**
     * Settle a reservation: the trade executed, move the money and close the hold,
     * in one transaction. actualAmount lets us settle for less than reserved
     * (the excess is simply freed when the hold closes).
     */
    @Transactional
    public ReservationResult settle(UUID orderId, BigDecimal actualAmount) {
        AccountHold hold = holds.findByOrderId(orderId)
            .orElseThrow(() -> new IllegalArgumentException("no hold for order " + orderId));

        // Idempotent: settling an already-settled hold returns the same result.
        if (hold.getStatus() == HoldStatus.SETTLED) {
            return ReservationResult.from(hold);
        }
        if (actualAmount.compareTo(hold.getAmount()) > 0) {
            throw new IllegalArgumentException("cannot settle more than was reserved");
        }

        Account account = accounts.lockById(hold.getAccountId()).orElseThrow();
        account.debit(actualAmount);   // the real money movement, guarded by the hold
        hold.settle();
        return ReservationResult.from(hold);
    }

    /**
     * Release a reservation: the order was cancelled or rejected. Funds become
     * available again. This is the compensation step of the saga.
     */
    @Transactional
    public ReservationResult release(UUID orderId) {
        AccountHold hold = holds.findByOrderId(orderId)
            .orElseThrow(() -> new IllegalArgumentException("no hold for order " + orderId));

        // Idempotent: releasing an already-released hold returns the same result.
        if (hold.getStatus() == HoldStatus.RELEASED) {
            return ReservationResult.from(hold);
        }
        hold.release();
        return ReservationResult.from(hold);
    }
}
