package com.gatto.coop.funds.repository;

import com.gatto.coop.funds.domain.Order;
import com.gatto.coop.funds.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    /** Reconciliation picks up everything stuck in UNKNOWN. */
    List<Order> findByStatus(OrderStatus status);
}
