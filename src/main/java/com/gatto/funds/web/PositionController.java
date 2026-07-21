package com.gatto.funds.web;

import com.gatto.funds.repository.PositionRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/positions")
public class PositionController {

    private final PositionRepository positions;

    public PositionController(PositionRepository positions) {
        this.positions = positions;
    }

    public record PositionView(Long accountId, String isin, BigDecimal totalAmount) {
    }

    @GetMapping("/{accountId}")
    public List<PositionView> byAccount(@PathVariable Long accountId) {
        return positions.findAll().stream()
            .filter(p -> p.getAccountId().equals(accountId))
            .map(p -> new PositionView(p.getAccountId(), p.getIsin(), p.getTotalAmount()))
            .toList();
    }
}
