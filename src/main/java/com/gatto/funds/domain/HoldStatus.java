package com.gatto.funds.domain;

public enum HoldStatus {
    ACTIVE,     // funds are reserved, not yet moved
    SETTLED,    // funds were debited — the hold did its job (like card clearing)
    RELEASED    // the order was cancelled — funds are available again
}
