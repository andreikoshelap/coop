package com.gatto.coop.funds.domain;

public enum OrderStatus {
    PENDING,     // created, nothing reserved yet
    RESERVED,    // funds held in the core
    SENT,        // accepted by the broker, awaiting execution
    SETTLED,     // executed and settled — terminal success
    CANCELLED,   // released and cancelled — terminal failure
    UNKNOWN      // broker did not answer; state must be resolved by reconciliation
}
