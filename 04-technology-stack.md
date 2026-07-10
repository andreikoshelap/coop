# 4. Technology stack

> Question: *Which technologies would you use? The investment platform and related systems are
> built from scratch. The only keyword is Java.*

A list of technologies proves nothing. What follows is a set of decisions, each with the reason it
was made **for this problem** — not because the technology is popular — and, where it matters, the
alternative that was rejected.

## 4.1 Language and runtime

**Java 21 (LTS).**

* **Records and sealed interfaces** for the domain: `Money`, `Isin`, `OrderId`, and a sealed
  `OrderState` hierarchy that the compiler checks exhaustively. A saga state machine with an
  unhandled state is a production incident; `switch` over a sealed type turns it into a compile
  error.
* **Virtual threads** let the ordinary services stay blocking without paying for a thread per
  in-flight request. This is why Order, Portfolio and Contract are plain Spring MVC (see 4.3).
* LTS, because a bank runs this for a decade.

## 4.2 Framework

**Spring Boot 3.5+ / 4.x, Spring Framework, Spring Data JPA, Spring Security.**

The reason is not familiarity. It is that everything this platform needs is a first-class citizen:
declarative transactions with explicit propagation (which is the whole of section 6), OAuth2
resource server, Actuator health and metrics wired into the deployment platform, Testcontainers
integration, and a migration story that a bank's operations team already knows how to run.

**Spring Modulith** for the services that are granular in the logical view but do not need to be
separate deployables on day one. Module boundaries are enforced at build time by an ArchUnit-style
test, and a module can be promoted to its own service later without rewriting its internals. This
is how "as granular as possible" is achieved without shipping fifteen deployables to production in
the first release — the architects asked for granularity of *services*, not of *containers*.

## 4.3 Blocking or reactive?

**Blocking Spring MVC everywhere, except the market data gateway, which is WebFlux on Reactor
Netty.**

The bottleneck for Order, Portfolio and Contract is the database, not the connection count.
Reactive code there buys nothing and costs debuggability, stack traces and the ability to use
`@Transactional` the way everyone expects.

The market data gateway holds ten thousand mostly-idle WebSocket connections and needs real
backpressure semantics (`onBackpressureLatest`, conflation — section 9.4). That is what Reactor is
for.

Worth naming the nuance, because an architect will ask: **virtual threads do not replace Reactor
here.** They make blocking I/O cheap, which solves the thread-per-connection cost, but they do not
give you backpressure or conflation operators. Cheap threads and a bounded stream are different
problems.

## 4.4 Persistence

**PostgreSQL, one database per service. Flyway for migrations.**

* Relational, because every hard invariant in this system is a constraint: unique idempotency keys,
  `CHECK (amount > 0)`, `available_balance >= 0`, foreign keys between orders and holds. Section 6
  is one long argument that *the database enforces and the application optimizes*. A document store
  cannot enforce.
* **`NUMERIC(19,4)` for money, `BigDecimal` in Java, serialized as a JSON string.** Never `double`,
  never `float`, at no layer, including the wire format — a JSON number will be parsed as a double
  by some client eventually.
* `SELECT ... FOR UPDATE` for the hold path (section 7.4).
* One database per service, no shared schema. The moment two services share a table, they are one
  service with a slow function call.

**Rejected:** MongoDB or any eventually-consistent store for trades and holds. **Rejected:** a
single shared database "for now" — it is never for now.

## 4.5 Event backbone

**Apache Kafka**, with **Avro or Protobuf + a schema registry**.

Kafka rather than RabbitMQ because we need **log semantics**, not queue semantics:

* multiple independent consumers of the same events (Portfolio, CRM, AML, DWH, reporting), each with
  its own offset;
* **replay** — a new consumer, or a rebuilt read model, reads history from the beginning;
* **log compaction**, keyed by ISIN, so a restarted market data gateway rebuilds current prices
  without touching the paid upstream feed (section 9.3);
* retention measured in days, which is what makes reprocessing after a consumer bug possible.

RabbitMQ is the better tool for work queues. This is not a work queue.

The **schema registry** exists because `OrderExecuted` will be consumed by four systems for the next
decade, and event-carried state transfer (section 3.5) means the event is a long-lived public
contract. Backward-compatible evolution must be enforced by a build step, not by discipline.

**Transactional outbox** published by CDC (Debezium) or a polling publisher. Never a direct produce
from application code inside a transaction.

## 4.6 Integration with the broker

**QuickFIX/J** if the broker speaks FIX; a plain `RestClient` if it speaks REST. Either way the
protocol lives entirely inside the Broker gateway and never escapes it (section 3.4).

**Resilience4j** for circuit breaker, retry with exponential backoff **and jitter**, bulkhead and
timeout. These are configuration on the gateway, not scattered `try/catch` blocks.

## 4.7 Caching and scheduling

* **Caffeine** for the in-process last-value cache of prices, and short-TTL caches for FX rates.
* **Redis** only if a shared cache is genuinely needed across instances — an in-process cache fed
  from a compacted topic usually is not.
* **ShedLock** over Spring `@Scheduled` for the jobs that must run exactly once across the cluster:
  hold expiry sweeps, reconciliation, end-of-day valuation, the nightly regulatory report. Two
  instances generating the regulator's report simultaneously is a memorable way to learn this.

## 4.8 Security

* **OAuth2 / OIDC** at the gateway; the bank's existing IAM as the identity provider, not a new one.
* **mTLS** between services; the Funds API in particular must be callable only by the Order service.
* Secrets in Vault or the platform's secret store — never in configuration, never in the repository.
* **Every money-moving call is audited**: who, what, when, from which channel, with which
  idempotency key. Audit is not logging; it is durable, queryable and retained for years.

## 4.9 Observability

* **OpenTelemetry** traces, **Micrometer** metrics, structured JSON logs.
* `orderId` and `traceId` propagate through synchronous calls *and* event headers, so a saga can be
  reconstructed end to end. Without this, "order stuck in `UNKNOWN`" is unanswerable at 03:00.
* The alerts that matter are business-level, not CPU-level: holds older than TTL, orders in
  `UNKNOWN`, reconciliation breaks, gap detected in the reporting sequence, silent market data feed.

## 4.10 Testing

* **Testcontainers** — real PostgreSQL, real Kafka. Locking behaviour, `SELECT FOR UPDATE` and
  unique constraint violations do not reproduce on H2.
* **Consumer-driven contract tests** against the Broker gateway and the Funds API.
* **WireMock** for the broker in integration tests, including the timeout and duplicate-callback
  paths — the failure paths of section 6.2 are the ones that need tests, because they are the ones
  nobody exercises manually.
* Property-based tests for the money and FX arithmetic (section 10.6). Rounding bugs hide in
  examples and surface in production.

## 4.11 Delivery

Docker images, Kubernetes, Gradle, GitHub Actions or the bank's CI. Deliberately unremarkable: the
deployment diagram was explicitly out of scope, and nothing about this problem argues for anything
exotic here.

**Rejected:** serverless. Long-lived WebSocket connections and stateful saga orchestration are the
two workloads functions-as-a-service handles worst.

## 4.12 Summary of the choices that carry weight

| Choice | Because |
|---|---|
| Java 21, sealed types | the saga state machine is checked by the compiler |
| Spring Modulith before microservices | granular design, few deployables on day one |
| Blocking MVC, reactive only at the price gateway | reactive complexity paid only where it buys something |
| PostgreSQL, one per service | every hard invariant is a database constraint |
| Kafka + schema registry | replay, compaction, many consumers, decade-long event contracts |
| Outbox via CDC | no dual writes |
| Resilience4j on the broker edge | the third party is the thing that fails |
| ShedLock on every scheduled job | the regulator gets one report, not two |
