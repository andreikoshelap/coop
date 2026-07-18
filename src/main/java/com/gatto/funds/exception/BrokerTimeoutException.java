package com.gatto.funds.exception;

import java.util.UUID;

/**
 * Thrown when the broker does not answer in time. Its meaning is precise and important:
 * it does NOT mean "the order failed". It means "we do not know". The order may have been
 * accepted and may still execute. Treat it as UNKNOWN, never as a reason to release funds.
 */
public class BrokerTimeoutException extends RuntimeException {
    public BrokerTimeoutException(UUID orderId) {
        super("broker did not answer for order " + orderId + " — state is UNKNOWN");
    }
}
