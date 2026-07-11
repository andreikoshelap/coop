package com.gatto.coop.funds.service;

import com.gatto.coop.funds.domain.AccountHold;
import com.gatto.coop.funds.domain.HoldStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record ReservationResult(UUID reservationId, UUID orderId, BigDecimal amount, HoldStatus status) {
    public static ReservationResult from(AccountHold hold) {
        return new ReservationResult(hold.getId(), hold.getOrderId(), hold.getAmount(), hold.getStatus());
    }
}
