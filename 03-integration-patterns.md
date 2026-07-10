# 3. Integration patterns

> Question: *Describe the integration patterns used in the different parts.*

A pattern is a decision, and a decision only means something next to the alternative it rejected.
This section is therefore organised by **boundary**, not by pattern name. Each boundary has
different failure modes, different latency budgets and a different owner — which is exactly why one
integration style for the whole platform would be wrong.

## 3.1 Overview

| Boundary | Style | Pattern | Rejected alternative |
|---|---|---|---|
| Channels → platform | sync | API gateway / BFF, REST + WebSocket | channels calling each service directly |
| Platform → bank core (funds) | sync | Facade (Funds API) + idempotent commands, strangler fig | shared database, direct DB reads, 2PC |
| Platform → external broker | sync command + async callback | Anti-corruption layer, idempotency key, circuit breaker | letting the broker's model into our domain |
| Order → Contract (pre-trade) | sync | authoritative read against the owner | reading a replicated projection |
| Inside the platform | async | transactional outbox → Kafka, idempotent consumers | dual writes, synchronous service chains |
| Order saga | async, orchestrated | saga with compensation | 2PC; pure choreography |
| Platform → DWH / CRM / AML / Notifications | async | publish–subscribe, event-carried state transfer | point-to-point ETL out of a live database |
| Market data → channels | streaming | fan-out, conflation, snapshot + delta | polling; one upstream subscription per user |
| Platform → regulator | batch | nightly file / API submission, versioned | streaming to a regulator |

The rest of this section explains the non-obvious rows.

## 3.2 Channels → platform: API gateway / BFF

The app and the internet bank talk to one entry point, not to six services. The gateway terminates
TLS, validates the OAuth2 token, applies rate limits, and aggregates the two or three calls a
portfolio screen needs into one response.

The alternative — channels calling `order-service`, `portfolio-service` and `instrument-service`
directly — leaks the internal service topology into two clients we cannot deploy atomically with
the backend. Every service split then becomes a mobile app release.

The same gateway hosts the WebSocket endpoint (section 9).

## 3.3 Platform → bank core: facade over a monolith

This is the boundary that decides whether the project succeeds.

**Pattern: a facade (`Funds API`) exposing `reserve` / `settle` / `release`, in the new stack,
delegating into the core.** Commands are idempotent, keyed by `orderId`.

Three things we do *not* do:

* **No shared database.** The investment platform never reads the core's tables. A shared schema is
  not an integration; it is a distributed monolith with no contract and no versioning.
* **No 2PC.** Discussed in section 6.3.
* **No business logic in the facade.** The facade translates and protects. The money invariant stays
  inside the core's transaction (section 7).

The facade is also the **strangler-fig seam**: the interface is already extracted, the
implementation is not. When the core is eventually decomposed, or when the customer's cash moves to
a dedicated investment account (section 7.3, option B′), the interface stays and the implementation
moves behind it. Callers never notice.

## 3.4 Platform → external broker: anti-corruption layer

The Broker gateway is an **anti-corruption layer**. The broker's model — its order types, its status
codes, its notion of a fill, its FIX tags — stops at that boundary and is translated into our domain
language. Otherwise the broker's vocabulary spreads through the platform, and replacing the broker
(or adding a second one) becomes a rewrite rather than a new adapter.

The interaction is asymmetric and that asymmetry is the design:

* **Command out, synchronously**, carrying *our* `orderId` as the client order id. This is what makes
  the call idempotent and what makes recovery possible after a timeout (section 6.2).
* **Execution in, asynchronously** — a callback or a stream. At-least-once, therefore an idempotent
  consumer.
* **A polling fallback** (`GET /orders/{orderId}`), because the async channel is the thing that
  breaks first, and after a timeout we must be able to ask "does this order exist".

Resilience is not decoration here: timeouts, retries **with jitter**, a circuit breaker so a slow
broker does not exhaust our threads, and a bulkhead so broker calls cannot starve the rest of the
service. Retries without jitter turn a broker hiccup into a synchronised stampede.

## 3.5 Inside the platform: events, not call chains

Services communicate by publishing facts, written to the database in the same transaction that
changed the state (transactional outbox, section 6.4), then relayed to Kafka.

For the fan-out after execution we use **event-carried state transfer**: `OrderExecuted` contains
everything Portfolio, CRM, AML, the DWH and the reporting service need. The alternative — a thin
`OrderExecutedNotification` followed by five services calling back to ask for details — recreates
synchronous coupling with extra steps and makes the Order service a bottleneck for consumers it is
not supposed to know about.

The trade-off is honest and worth stating: event-carried state transfer means bigger events, and a
schema that must evolve compatibly for years. That is what the schema registry in section 4 is for.

**Orchestration vs choreography.** The order flow is an orchestrated saga: it touches money, it has
compensations, and someone must be able to answer "why is order X stuck" by looking in one place.
Everything downstream of `OrderExecuted` is choreography: consumers subscribe, and the Order service
does not know they exist. Using orchestration for the fan-out would put the Order service in the
business of knowing about AML. Using choreography for the money flow would scatter the compensation
logic across services.

## 3.6 Platform → existing systems

**AML.** Today AML is integrated with the core, which means it sees payments and knows nothing about
securities. This is a gap in the current picture (section 11) and not merely an integration detail:
market abuse, insider dealing and layering through investment accounts are exactly what AML exists
to catch. In the TO-BE picture AML subscribes to `OrderExecuted` and `PositionChanged` on the same
event backbone as everyone else.

**CRM.** Customer service must answer questions about the customer's portfolio. CRM builds a
read-only projection from events (a CQRS read model) and, for detail, calls the Portfolio service.
It never reads the platform's database.

**DWH.** Switches from importing out of the core to subscribing to the backbone — argued in
section 8.4.

**Notifications.** The existing notification module becomes just another consumer. The Order service
does not send e-mails; it publishes a fact.

The shape of this is deliberate: **one new integration mechanism, five consumers.** Adding the sixth
costs nothing.

## 3.7 Platform → regulator

Batch. Once a night, a generated artefact, submitted and acknowledged, versioned, with corrections
as new submissions rather than rewrites (section 8.3). Nothing here wants to be real-time, and
making it so would only add ways to fail.

## 3.8 Cross-cutting

* **Idempotency** on every command that crosses a boundary, enforced by a database constraint rather
  than an application check.
* **Correlation.** `orderId` and `traceId` propagate through every call and every event. A saga that
  cannot be traced end to end cannot be operated.
* **Dead letter queues** with alerting. An event that cannot be processed must land somewhere a
  human looks, not in a retry loop forever.
* **Contract testing** at each boundary (consumer-driven contracts against the Broker gateway stub
  and the Funds API), because the integration points are where this system will actually break.
