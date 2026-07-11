package com.gatto.coop.funds.service;

import java.math.BigDecimal;
import java.util.UUID;

public record ReserveCommand(UUID orderId, Long accountId, BigDecimal amount, String currency) {
}
