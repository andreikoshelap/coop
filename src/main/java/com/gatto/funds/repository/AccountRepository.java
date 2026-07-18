package com.gatto.funds.repository;

import com.gatto.funds.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * SELECT ... FOR UPDATE on the account row.
     *
     * This is GUARANTEE #2 (against the double-spend race): two concurrent
     * reservations on the SAME account are serialized here. The second one
     * blocks until the first commits, then reads the first one's hold and
     * sees the reduced available balance.
     *
     * Pessimistic, not optimistic, because a lost update here is a financial
     * loss, and retry-on-conflict is not acceptable latency in a money path.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> lockById(@Param("id") Long id);

    @Modifying
    @Transactional
    @Query("update Account a set a.balance = :balance where a.id = :id")
    void updateBalance(@Param("id") Long id, @Param("balance") BigDecimal balance);
}
