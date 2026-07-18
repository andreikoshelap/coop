package com.gatto.funds.broker;

import com.gatto.funds.exception.BrokerTimeoutException;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Anti-corruption layer to the external broker. In the real system this is the only
 * component that speaks the broker's protocol (FIX/REST). Here it is an interface with
 * a stub implementation so we can drive the three interesting behaviours: accept,
 * reject, and — the important one — time out.
 */
public interface BrokerGateway {

    /**
     * Place an order, synchronously. The orderId is OUR idempotency key, passed to the
     * broker as the client order id, so we can ask about this order later by the same id.
     *
     * @return ACCEPTED or REJECTED
     * @throws BrokerTimeoutException when the broker does not answer — meaning we DO NOT
     *         know whether it accepted the order or not. This is the whole point.
     */
    BrokerAck placeOrder(UUID orderId, String isin, BigDecimal amount);

    /**
     * Reconciliation query: ask the broker what actually happened to an order.
     * This is the only honest source of truth after a timeout.
     */
    BrokerOrderStatus getOrderStatus(UUID orderId);

    enum BrokerAck {
        ACCEPTED,
        REJECTED
    }

    enum BrokerOrderStatus {
        EXECUTED,    // the broker filled it
        NOT_FOUND,   // the broker never received it
        WORKING      // still in flight — decide later, do not resolve yet
    }
}
