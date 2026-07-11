package com.gatto.coop.funds.exception;

import java.util.UUID;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(UUID orderId) {
        super("insufficient available funds for order " + orderId);
    }
}
