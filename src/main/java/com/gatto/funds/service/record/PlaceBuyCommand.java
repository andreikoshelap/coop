package com.gatto.funds.service.record;

import java.math.BigDecimal;
import java.util.UUID;

public record PlaceBuyCommand(UUID orderId, Long accountId, String isin,
                              BigDecimal amount, String currency) {
}
