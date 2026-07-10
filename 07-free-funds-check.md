# 7. Free-funds check before a securities purchase

> Question: *Customer money lives in the bank core, while the investment service must be
> standalone. How do you check available funds before buying securities, given that the money
> does not move to the broker instantly and other transactions may hit the account at any moment?*

## 7.1 Why a plain balance check is wrong

The naive approach — `GET /accounts/{id}/balance`, compare, then place the order — is a
time-of-check to time-of-use race. Between the check and the settlement the customer may pay a
bill, swipe a card, or place a second investment order in the other channel. The order reaches
the broker, the trade executes, and the account has no money to settle it. The bank is now
exposed: the trade is legally binding, the funds are gone.

Two properties are non-negotiable:

1. **Funds must be made unavailable at the moment of the check, not at settlement.**
2. **Every actor that can move money must see that unavailability.**

The second property is the one that kills most "clean" microservice designs.

## 7.2 Solution: reservation (hold / earmark)

We introduce an explicit reservation on the account:

```
available_balance = balance − Σ(active holds)
```

The flow (see sequence diagram *Buy security*):

| # | Step | Consistency |
|---|------|-------------|
| 1 | Channel sends `buy(orderId)`; `orderId` is the idempotency key | — |
| 2 | Order service asks core: `hold(orderId, amount, ttl)` | **ACID, in core** |
| 3 | Core returns `reservationId`; order goes to `RESERVED` | local ACID |
| 4 | Broker Gateway places the order at the external broker | no transaction |
| 5–6 | Broker acknowledges, later reports the fill (async) | at-least-once |
| 7 | Order service asks core: `settle(reservationId, actualAmount)` | **ACID, in core** |
| 8–9 | `OrderExecuted` event → Portfolio → channels | eventual |
| 10 | On reject / expiry / timeout: `release(reservationId)` | **ACID, in core** |

This is a **saga with compensation**, orchestrated by the Order service. There is no 2PC and no
XA transaction anywhere in the chain — see section 6.

Reserved amount = `price × quantity × (1 + slippage buffer) + fees`. For market orders the buffer
matters; for limit orders the limit price is the cap. The final `settle` uses the actual executed
amount, and the difference is released back.

## 7.3 Who owns the hold — the central trade-off

### Option A — hold inside the core

The core stays the single source of truth for money. All existing modules (payments, cards, fees)
already read the account through the core, so `available_balance` is enforced for free.

* **Pro:** one database, one transaction, no distributed consistency where money is concerned.
* **Con:** we must modify the monolith, whose business logic lives in the database. This is exactly
  what the architects want to avoid.

### Option B — a standalone Reservation / Wallet service

* **Pro:** granularity, modern stack, the monolith is untouched.
* **Con, and it is fatal:** *a hold the core cannot see is not a hold.* If the core's payment module
  debits the account without knowing about the reservation, we are back to the race of 7.1. Making
  the core call an external service on every debit means (a) the same amount of monolith surgery
  and (b) a network hop in the critical path of every payment in the bank.

### Chosen: hybrid (A now, B′ later)

**Now.** The hold is physically owned by the core — the minimal irreducible change: a `holds`
table and an `available_balance` check on every debit. In front of it we place a thin
**Funds API** in the new stack: idempotency, TTL, retries, metrics, circuit breaker. The interface
is already extracted from the monolith; the implementation is not yet. This is the strangler-fig
pattern.

**Later (B′).** The customer transfers money to a dedicated **investment cash account** owned by
the platform's Wallet service. Holds move out of the core entirely; the core sees only ordinary
transfers. This is how most banks end up. It also fits the regulatory requirement to segregate
client investment funds.

Stating the target state, not just the immediate one, is deliberate: the design must be defensible
both on day one and after the monolith is decomposed.

## 7.4 Making the hold atomic

```sql
CREATE TABLE account_hold (
    id              UUID PRIMARY KEY,
    account_id      BIGINT      NOT NULL REFERENCES account(id),
    order_id        UUID        NOT NULL UNIQUE,   -- idempotency key
    amount          NUMERIC(19,4) NOT NULL CHECK (amount > 0),
    currency        CHAR(3)     NOT NULL,
    status          TEXT        NOT NULL,          -- ACTIVE | SETTLED | RELEASED
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_hold_active_order
    ON account_hold (order_id) WHERE status = 'ACTIVE';
```

The debit path locks the account row and re-computes availability inside the same transaction:

```sql
BEGIN;
SELECT balance FROM account WHERE id = :accountId FOR UPDATE;

SELECT balance - COALESCE(SUM(amount) FILTER (WHERE status = 'ACTIVE'), 0)
  FROM account LEFT JOIN account_hold ON account_hold.account_id = account.id
 WHERE account.id = :accountId
 GROUP BY balance;
-- reject if < :amount
COMMIT;
```

`SELECT ... FOR UPDATE` (pessimistic locking) is the right tool here rather than optimistic
locking: contention on a single account is low, but a lost update is a financial loss, and
retry-on-conflict is not acceptable latency behaviour in a payment path.

Money is `NUMERIC(19,4)` in the database and `BigDecimal` in Java — never `double`, never a
floating-point type anywhere in the chain, including JSON serialization (serialize as string).

## 7.5 Idempotency of the Funds API

Every reservation call carries `orderId` as the idempotency key. A retry after a network timeout
must return the original reservation, not create a second hold:

```java
@Transactional
public Reservation reserve(ReserveCommand cmd) {
    return holdRepository.findByOrderId(cmd.orderId())
        .map(this::toReservation)                       // replay: same answer
        .orElseGet(() -> createHold(cmd));              // first call
}

private Reservation createHold(ReserveCommand cmd) {
    Account account = accountRepository.lockById(cmd.accountId());   // SELECT FOR UPDATE
    BigDecimal available = account.balance().subtract(holdRepository.activeSum(account.id()));
    if (available.compareTo(cmd.amount()) < 0) {
        throw new InsufficientFundsException(cmd.orderId());
    }
    return toReservation(holdRepository.save(Hold.active(cmd, ttl)));
}
```

The unique partial index on `(order_id) WHERE status = 'ACTIVE'` is the last line of defence: if
two concurrent retries both pass the `findByOrderId` check, the database rejects the second insert.
Application-level checks are an optimization; the constraint is the guarantee.

## 7.6 The hard case: broker timeout

If `placeOrder` times out, we do **not** know whether the broker accepted the order.

* Releasing the hold is unsafe — the order may still execute.
* Holding forever is unacceptable — the customer's money is frozen.

The order enters status `UNKNOWN`. A reconciliation worker polls the broker
(`GET /orders/{orderId}`, keyed by *our* idempotency key, which is why the broker contract must
accept a client order id) until the order's true state is known, then settles or releases. The TTL
on the hold is a backstop, not the primary mechanism: it must be longer than the reconciliation
window, and its expiry must raise an operational alert rather than silently release funds.

Nightly reconciliation against the broker's position and cash statements is the final safety net.
Any discrepancy between our holds, our positions and the broker's records is a break that a human
must clear. In a securities platform reconciliation is not an operational nicety — it is a
regulatory expectation.

## 7.7 Sell orders

Selling is the mirror image and is often forgotten: the hold is on the **security position**, not
on cash. The customer must not be able to sell the same 100 shares twice in two channels. The
Portfolio service therefore owns position holds with exactly the same semantics — reserve, settle,
release, idempotency key, TTL. Cash arrives afterwards, asynchronously, via a credit to the core.

## 7.8 Summary

* Reservation, not balance check.
* The core owns the hold today, because a hold invisible to the money-mover is not a hold.
* Funds API is the seam along which the core will later be split (strangler-fig).
* Saga with compensation, never 2PC.
* Idempotency key = `orderId`, enforced by a database constraint.
* Pessimistic locking on the account row; `BigDecimal` / `NUMERIC` for money.
* Timeout ⇒ `UNKNOWN` ⇒ reconciliation, never a blind release.
* Sell orders reserve the position, not the cash.
