package com.gatto.coop.funds.web;


import com.gatto.coop.funds.exception.InsufficientFundsException;
import com.gatto.coop.funds.service.record.ReserveCommand;
import com.gatto.coop.funds.service.record.ReservationResult;
import com.gatto.coop.funds.service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/reservations")
public class ReservationController {

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    public record ReserveRequest(UUID orderId, Long accountId, BigDecimal amount, String currency) {
    }

    public record SettleRequest(UUID orderId, BigDecimal actualAmount) {
    }

    public record ReleaseRequest(UUID orderId) {
    }

    @PostMapping("/reserve")
    public ReservationResult reserve(@RequestBody ReserveRequest req) {
        return service.reserve(
            new ReserveCommand(req.orderId(), req.accountId(), req.amount(), req.currency()));
    }

    @PostMapping("/settle")
    public ReservationResult settle(@RequestBody SettleRequest req) {
        return service.settle(req.orderId(), req.actualAmount());
    }

    @PostMapping("/release")
    public ReservationResult release(@RequestBody ReleaseRequest req) {
        return service.release(req.orderId());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail onInsufficientFunds(InsufficientFundsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
