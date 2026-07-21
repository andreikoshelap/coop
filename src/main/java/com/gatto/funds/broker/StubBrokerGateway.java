package com.gatto.funds.broker;

import com.gatto.funds.exception.BrokerTimeoutException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A configurable fake broker. The mode decides how placeOrder behaves — and, crucially,
 * what the broker "really" did, which getOrderStatus reveals during reconciliation.
 *
 * The whole lesson lives in the gap between the two:
 *   TIMEOUT_THEN_EXECUTE — placeOrder throws (we get no answer), but the broker DID accept
 *                          and execute. getOrderStatus later returns EXECUTED.
 *   TIMEOUT_THEN_NOTHING — placeOrder throws, and the broker never got it.
 *                          getOrderStatus later returns NOT_FOUND.
 * A blind release on timeout would be correct for the second case and a disaster for the first.
 * That is why the truth must come from getOrderStatus, not from the timeout.
 */
@Component
public class StubBrokerGateway implements BrokerGateway {

    public enum Mode {
        ACCEPT_AND_EXECUTE,     // accepted, and an execution report will follow
        REJECT,                 // cleanly rejected
        TIMEOUT_THEN_EXECUTE,   // no answer, but it actually executed
        TIMEOUT_THEN_NOTHING    // no answer, and it never arrived
    }

    private volatile Mode mode = Mode.ACCEPT_AND_EXECUTE;

    // Orders the broker "really" holds as executed (used by getOrderStatus).
    private final Set<UUID> executed = ConcurrentHashMap.newKeySet();

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    @Override
    public BrokerAck placeOrder(UUID orderId, String isin, BigDecimal amount) {
        switch (mode) {
            case ACCEPT_AND_EXECUTE -> {
                executed.add(orderId);
                return BrokerAck.ACCEPTED;
            }
            case REJECT -> {
                return BrokerAck.REJECTED;
            }
            case TIMEOUT_THEN_EXECUTE -> {
                // The broker accepted and executed, but our call did not get the answer.
                executed.add(orderId);
                throw new BrokerTimeoutException(orderId);
            }
            case TIMEOUT_THEN_NOTHING -> {
                // The broker never received it. Nothing recorded.
                throw new BrokerTimeoutException(orderId);
            }
            default -> throw new IllegalStateException("unhandled mode " + mode);
        }
    }

    @Override
    public BrokerOrderStatus getOrderStatus(UUID orderId) {
        return executed.contains(orderId) ? BrokerOrderStatus.EXECUTED : BrokerOrderStatus.NOT_FOUND;
    }
}
