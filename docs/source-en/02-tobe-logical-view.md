# 2. TO-BE logical view

> Question: *Produce a logical view of the TO-BE architecture. A detailed deployment diagram is not
> required. We are interested in how and which components you would create, and how data would flow.*

*See Figure 1 — TO-BE logical view, Figure 2 — Buy security sequence, Figure 3 — Market data
fan-out.*

## 2.1 The principles the picture follows

Four decisions shape everything below. They are stated first because the diagram is a consequence of
them, not the other way round.

1. **Money invariants stay where the money is.** The bank core remains the single source of truth
   for cash. We extend it minimally rather than reimplementing it (section 7).
2. **Each service owns its data and is the only writer.** No shared schemas, no cross-service reads
   into another service's database.
3. **Synchronous where authority is required, asynchronous everywhere else.** Pre-trade checks and
   fund reservation are synchronous. Everything downstream of an execution is an event.
4. **Granularity of services, not of deployables.** The architects asked for a granular design. That
   is a decision about boundaries and ownership, not about how many containers ship in release one
   (section 2.7).

## 2.2 New components

| Component | Responsibility | Data it owns | Why it is separate |
|---|---|---|---|
| **API gateway / BFF** | single entry point for app and internet bank; token validation, rate limiting, screen-level aggregation; hosts the WebSocket endpoint | none | keeps the internal service topology out of two clients we cannot deploy atomically |
| **Order & execution** | order lifecycle and the buy/sell saga; orchestration and compensation | orders, saga state, outbox | highest change rate in the platform; the only component allowed to compensate |
| **Portfolio & positions** | positions, lots, cost basis, position holds, valuation | positions, lots, EOD valuations | read-heavy and scales differently from order entry; owns the cost basis, therefore owns valuation |
| **Instrument & search** | securities catalogue, search, reference data | instrument master, search index | read-mostly reference data with a completely different caching and indexing profile |
| **Market data** | single upstream subscription, normalisation, conflation, last-value cache, fan-out; FX rates as a module | none durable (a cache and a compacted topic) | the only reactive component in the platform; scales on connections, not on transactions (section 9) |
| **Contract service** | investment agreement lifecycle, MiFID profile, risk profile, entitlements | agreements, assessments, evidence references | a distinct bounded context; the core has no vocabulary for MiFID attributes (section 5) |
| **Regulatory reporting** | nightly report to FI, gap detection, versioned submissions | immutable trade records, report artefacts | legal accountability and multi-year retention must not depend on another service's schema (section 8) |
| **Broker gateway (ACL)** | anti-corruption layer to the external broker; idempotent commands, async callbacks, polling fallback, circuit breaker | broker order id mapping | the broker's protocol and vocabulary stop here; the only holder of broker credentials |
| **Funds API (facade)** | `reserve` / `settle` / `release` against the core; idempotency, TTL, retries | none (delegates into the core) | the strangler-fig seam along which the core will later be split (section 3.3) |

## 2.3 What stays in the bank core

* Customer identity and KYC.
* Accounts, balances, payments.
* **Holds** (`available_balance = balance − Σ active holds`), because a reservation invisible to the
  payments module is not a reservation (section 7.3).

The core is extended, not decomposed. The Funds API is the only door into it from the platform, and
the platform never reads its tables.

## 2.4 Existing systems, re-wired

| System | Today | TO-BE |
|---|---|---|
| **Data warehouse** | imports from the core | subscribes to the event backbone; keeps analytics only (section 8.4) |
| **AML** | integrated with the core; sees payments | additionally consumes `OrderExecuted` and `PositionChanged` — otherwise it never sees a single trade (section 11.1.5) |
| **CRM** | — | builds a read-only projection of positions from events; calls Portfolio for detail; never reads its database |
| **Notifications** | standalone module | becomes one more event consumer. The Order service publishes a fact; it does not send e-mails |

The shape here is deliberate: **one new integration mechanism, five consumers.** The sixth costs
nothing.

## 2.5 What we deliberately did *not* create

Naming the services we chose *not* to split out is as much a design statement as the ones we did.

* **No FX service, initially.** FX rates are a module of Market data — same upstream, same caching,
  same conflation concerns. It becomes a service the day someone else needs rates.
* **No valuation service.** Valuation needs the cost basis, and the cost basis belongs to Portfolio.
  A separate service would have to read Portfolio's data, which principle 2 forbids.
* **No customer service.** The core owns identity. Duplicating it would create two answers to one
  question.
* **No new notification service.** One exists.

Granularity is not maximisation. A service that cannot own its data is not a service — it is a
remote procedure call wearing a container.

## 2.6 Data flows

**F1 — Buy a security** *(Figure 2)*. Channel → Order service. Contract checked synchronously
against its owner. Funds reserved in the core (ACID). Order placed at the broker through the ACL,
carrying our `orderId` as the idempotency key. Execution arrives asynchronously; settlement debits
the account and closes the hold in one core transaction. `OrderExecuted` goes out through the outbox
to Kafka, and Portfolio, CRM, AML, Notifications, the DWH and the reporting service each consume it
independently. Failure at any step compensates by releasing the hold — never blindly (section 6.2).

**F2 — Sell a security.** The mirror image, and the scarce resource is the *position*, not the cash:
Portfolio holds the shares so the same 100 cannot be sold twice from two channels. Cash is credited
to the core afterwards, asynchronously (section 6.2, chain B).

**F3 — Sign the agreement.** Contract service writes and publishes `ContractActivated`. The core,
CRM and the DWH build read-only projections. The pre-trade check in F1 hits the Contract service
directly, never a projection (section 5.4).

**F4 — Prices** *(Figure 3)*. One upstream subscription per instrument → Market data → compacted
topic → gateway instances → WebSocket to clients. Snapshot on connect, deltas afterwards, conflated
to display cadence. Nothing here is durable and nothing needs to be.

**F5 — Nightly regulatory report.** Reporting service has been consuming executions all day into its
own immutable store. After reconciliation against the broker, the report is generated, submitted and
acknowledged. Cutoff is by trade timestamp, not ingestion timestamp (section 8.3).

**F6 — Customer service enquiry.** CRM shows the projection; for detail it calls Portfolio and
Contract. Every valuation it displays carries the FX rate and timestamp it was computed with, so the
operator can reproduce the number the customer is disputing (section 10.6).

**F7 — Reconciliation.** A scheduled job compares our holds, positions and cash movements against
the broker's statements. Breaks block the regulatory submission and page a human. This is the flow
that exists because the other six can fail.

## 2.7 Granularity: services versus deployables

The architects asked for the solution to be built "as granular as possible". Taken literally, that
produces nine deployables, nine pipelines, nine on-call rotations and a distributed system before
the first customer.

The design above is granular in the sense that matters — **bounded contexts, data ownership and
independent evolution**. The initial delivery packages several of these as modules with boundaries
enforced at build time (Spring Modulith, section 4.2), and promotes a module to its own service when
a team exists to own it and its scaling profile diverges.

Two components are separate deployables from day one regardless, because their runtime demands it:

* **Market data**, which is reactive and scales on connection count;
* **Broker gateway**, which holds the credentials to a third party and must fail independently of
  everything else.

Splitting a module later is mechanical. Merging two services is not. The direction of the cheap
mistake decides the starting point (section 12.7).

## 2.8 Summary

* Nine new components; the core is extended, not decomposed.
* One facade (`Funds API`) is the seam along which the monolith is later split.
* One anti-corruption layer (`Broker gateway`) confines the third party's model.
* One event backbone; five existing systems become consumers rather than integration points.
* Synchronous only where authority is required: the contract check and the fund reservation.
* Granularity is about boundaries and ownership, not about the number of containers.
