package com.gatto.funds.web;

import com.gatto.funds.exception.InsufficientFundsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handling. Without this, a business rejection thrown deep inside the saga
 * (e.g. InsufficientFundsException from the reservation) surfaces as a bare HTTP 500, which
 * hides the real cause. @RestControllerAdvice applies to every controller, unlike a local
 * @ExceptionHandler which only covers its own controller — that was why /orders/buy returned
 * 500 while /reservations/reserve returned a clean 409.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail onInsufficientFunds(InsufficientFundsException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        // e.g. "no order ...", "no hold for order ..." — the caller asked for something invalid.
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail onIllegalState(IllegalStateException e) {
        // e.g. an illegal saga transition — the request is out of order for the current state.
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
