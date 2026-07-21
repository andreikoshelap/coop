# 6. ACID boundaries in a granular service landscape

> Question: *Since we want a granular, service-oriented view, in which chains might we trip over
> ACID problems? Describe how you would solve them.*

## 6.1 The core statement

ACID does not disappear — it **shrinks**. In a monolith the transaction boundary and the business
operation boundary coincide. Once the operation spans several services, each service keeps a local
ACID transaction against its own database, and the space *between* the services has no
transaction at all.

The design task is therefore not "how do we get ACID back", but:

1. Decide which invariants genuinely require strong consistency. (Very few. Money is one.)
2. Place those invariants **inside a single service and a single database**.
3. Connect everything else with sagas, events and reconciliation, and make each step idempotent.

Everything below follows from those three sentences.

## 6.2 The chains that break

### Chain A — Buy security (the critical one)

```
order created → contract checked → funds reserved → order sent to broker
              → execution reported → settlement → position updated → notification
```

Three local transactions (TX1 in Order service, TX2 and TX3 in the core) and four transaction-free
gaps. Failure modes:

| Gap | What can go wrong | Mitigation |
|-----|-------------------|------------|
| After TX1, before reserve | Order stuck in `PENDING` | TTL + saga recovery job resumes or fails the order |
| After reserve, before broker call | Funds frozen, no order | TTL on the hold, compensating `release` |
| Broker call times out | **We do not know if the order exists** | Status `UNKNOWN`, poll `GET /orders/{orderId}`, never blind-release |
| After execution, before settle | Trade exists, money not moved | Retry `settle` (idempotent); the hold still guarantees the funds |
| After settle, before position update | Money gone, position invisible | Outbox guarantees the event is eventually delivered |

The order of steps is itself a design decision: **the contract check comes before the reservation**
because a failure there requires no compensation. Push the cheap, side-effect-free validations to
the front of the saga.

### Chain B — Sell security

The mirror image, and the one candidates usually forget. The scarce resource is not cash but the
**position**: the customer must not sell the same 100 shares twice from two channels. Portfolio
service therefore owns *position holds* with the same semantics — reserve, settle, release,
idempotency key, TTL. Cash is credited to the core afterwards, asynchronously.

### Chain C — Contract signing

`sign → activate → publish ContractActivated → core / CRM / DWH build read models`

No money moves, so eventual consistency is fine — with one caveat: an order must never slip through
between "signed" and "projection replicated". This is why the Order service asks the **Contract
service** synchronously (step 2 of the sequence), not a replicated copy in the core. The
authoritative check hits the owner of the data; the projections are for display only.

### Chain D — Regulatory reporting

Here the requirement is not consistency but **completeness**. A missing event is a regulatory
breach, not a UI glitch. Eventual consistency is acceptable (the report runs at night); silent
event loss is not. See section 8 — this is the argument for the reporting service owning its own
store rather than reading the DWH.

### Chain E — Prices

No ACID, no transaction, no durability requirement. A lost tick is replaced by the next tick 200 ms
later. Recognising that a chain needs *no* guarantees is as much part of the design as protecting
the ones that do.

## 6.3 Why not 2PC / XA

Two-phase commit across the bank core, the Order service and an **external broker** is not on the
table:

* The broker is a third party. It will never enlist in our transaction manager.
* XA holds locks in the core for the duration of a network call to an external counterparty —
  a hostile pattern in a system that also processes payments.
* The coordinator becomes a single point of failure, and in-doubt transactions require manual
  intervention exactly when the system is already degraded.

The trade is not atomic in the database sense and cannot be made so. It is atomic in the *business*
sense, and that is achieved with a saga.

## 6.4 The toolkit

### Saga with compensation, orchestrated

The Order service is the **orchestrator**: it holds the state machine
(`PENDING → CONTRACT_OK → RESERVED → SENT → UNKNOWN? → EXECUTED → SETTLED / CANCELLED`) and issues
compensations (`release`) when a step fails.

Orchestration is chosen over choreography for the order flow because the flow touches money, is
short, and must be auditable — a single place that answers "why is this order stuck" is worth more
than loose coupling. Fan-out *after* execution (Portfolio, Notifications, AML, DWH, CRM) is pure
choreography: consumers subscribe to `OrderExecuted` and the Order service does not know they exist.

Compensation is not rollback. Releasing a hold is a new, forward-moving business fact; the audit
trail keeps both.

### Transactional outbox

The classic dual-write bug: commit to the database, then publish to Kafka, and crash in between.
The state changed and no one was told. Solution — write the event into the same database, in the
same transaction:

```sql
CREATE TABLE outbox (
    id             UUID PRIMARY KEY,
    aggregate_id   UUID        NOT NULL,
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ
);
```

```java
@Transactional
public void onExecution(ExecutionReport report) {
    Order order = orderRepository.findById(report.orderId()).orElseThrow();
    order.markExecuted(report);                      // state change
    outbox.append(OrderExecuted.from(order));        // event — same transaction
}                                                     // one commit, no dual write
```

A separate publisher (polling, or CDC via Debezium) moves rows from `outbox` to Kafka. Delivery is
**at-least-once**. There is no exactly-once across a network boundary, and pretending otherwise is
the mistake.

### Idempotent consumers

At-least-once delivery only works if consumers are idempotent. Two ways, in order of preference:

1. **Naturally idempotent operations.** `position.setQuantity(150)` is safe to apply twice;
   `position.add(50)` is not. Prefer state-setting over delta-applying wherever the event carries
   enough context.
2. **Deduplication table**, when the operation cannot be made naturally idempotent:

```java
@Transactional
public void handle(OrderExecuted event) {
    if (!processedEvents.markProcessed(event.eventId())) {   // UNIQUE constraint
        return;                                              // duplicate, ignore
    }
    portfolio.apply(event);
}
```

The `UNIQUE` constraint on `event_id` is the guarantee; the `if` is the optimization. Same principle
as section 7.5: **the database enforces, the application optimizes.**

### Reconciliation

Sagas, outboxes and idempotent consumers reduce the probability of divergence. They do not eliminate
it. A nightly reconciliation compares our holds, our positions and our cash movements against the
broker's statements. Every break is investigated by a human.

In a securities platform reconciliation is not a fallback for bad engineering — it is a regulatory
expectation and the last honest answer to "what if all of the above fails".

## 6.5 The consistency the user actually sees

Eventual consistency has a UX cost that is easy to overlook: the customer taps *buy*, the app
refreshes, and the position is not there yet.

The answer is not faster replication — it is **modelling the pending state explicitly**. The order
is `PENDING` / `SENT` / `EXECUTED`, and the channel shows exactly that. The task description even
demands it: *"see pending and executed transactions"*. Read-your-writes is satisfied by the Order
service, which is the source of truth for order state; the Portfolio service is the source of truth
only for settled positions.

Never let two services both claim to answer the same question.

## 6.6 Where eventual consistency is not acceptable

| Invariant | Guarantee | Where enforced |
|---|---|---|
| Customer cannot spend money they do not have | strong, ACID | bank core, `SELECT FOR UPDATE` + hold |
| Customer cannot sell shares they do not own | strong, ACID | Portfolio service, position hold |
| Customer cannot trade without a valid agreement | strong, synchronous read | Contract service |
| Every executed trade reaches the regulator | completeness, not consistency | outbox + gap detection |
| Position and P&L shown in the app | eventual (seconds) | Portfolio read model |
| Prices in the app | best effort | Market data, no durability |

The first three are strong because a violation is a financial or legal loss. The rest are eventual
because a violation is a stale screen.

## 6.7 Summary

* Shrink the ACID boundary until it fits inside one service and one database; put money invariants
  there.
* Saga with compensation, orchestrated for the order flow, choreographed for the fan-out.
* No 2PC, no XA — the broker is a third party and money must not be locked across a network call.
* Transactional outbox for every state-change-plus-event; at-least-once, never claimed exactly-once.
* Idempotency everywhere, enforced by database constraints rather than application checks.
* Model the pending state; do not hide eventual consistency from the user.
* Reconciliation as the final safety net, because the safety net is a regulatory requirement anyway.
