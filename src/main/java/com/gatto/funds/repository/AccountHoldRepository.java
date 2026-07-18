package com.gatto.funds.repository;


import com.gatto.funds.domain.AccountHold;
import com.gatto.funds.domain.HoldStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface AccountHoldRepository extends JpaRepository<AccountHold, UUID> {

    /** Idempotency lookup: is there already an ACTIVE hold for this order? */
    Optional<AccountHold> findByOrderIdAndStatus(UUID orderId, HoldStatus status);

    /** For settle / release: find the order's hold regardless of status. */
    Optional<AccountHold> findByOrderId(UUID orderId);

    /**
     * available_balance = balance - sum(active holds).
     * COALESCE so an account with no holds yields 0, not null.
     */
    @Query("""
           select coalesce(sum(h.amount), 0)
           from AccountHold h
           where h.accountId = :accountId and h.status = com.gatto.funds.domain.HoldStatus.ACTIVE
           """)
    BigDecimal sumActiveByAccount(@Param("accountId") Long accountId);
}
