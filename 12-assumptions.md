# 12. Assumptions

The brief is deliberately incomplete. Every design in this document therefore rests on choices that
were made rather than given. They are listed here, with the reason each was chosen and — more
usefully — **what would have to change if the assumption turns out to be false.**

An assumption that cannot be falsified is not an assumption; it is a guess with better handwriting.

---

## 12.1 Scope

| # | Assumption | Rationale | If false |
|---|---|---|---|
| S1 | The bank builds the platform rather than buying an off-the-shelf one | the brief says "from scratch" | the whole exercise becomes vendor integration and this document answers the wrong question (section 11.5) |
| S2 | A single external broker, at least initially | the brief says "an external broker" | the anti-corruption layer already accommodates a second one; the domain model must stay broker-agnostic, which it is by construction (3.4) |
| S3 | Deployment topology, infrastructure and detailed business functionality are out of scope | stated in the brief | — |
| S4 | The bank core cannot be replaced, only extended at its edges | it is described as a monolith with logic in the database | if the core can be modified freely, option B′ (7.3) becomes available immediately and the Funds API facade is unnecessary |

## 12.2 Business rules assumed

| # | Assumption | Rationale | If false |
|---|---|---|---|
| B1 | Orders are **market orders**, filled completely and once | simplification; the brief says only "buy/sell" | the Order aggregate must model N partial fills, the hold releases partially, and the saga gains a cancel/replace path — a change to the core domain, not an addition (11.1.3) |
| B2 | Settlement is **T+2**, and the position appears on trade date | market standard for equities | trade-date vs settlement-date accounting changes what the screen and the regulatory report each show (11.1.2) |
| B3 | Cost basis uses **FIFO with per-lot tracking** | default under Estonian personal income tax rules | weighted-average cost changes realised P&L and the customer's tax liability; Portfolio stores lots either way, so the change is contained (10.7) |
| B4 | Currency conversion is performed **by the bank**, at the moment of settlement, using the bank's customer rate | the customer's cash is in EUR in the core | if the **broker** converts, the FX risk between reservation and settlement moves to the broker and the reserved amount no longer needs an FX buffer (11.1.1) |
| B5 | The reserved amount includes a buffer for **price slippage and FX movement** | follows from B1 and B4 | if limit orders only, the limit price caps the exposure and the buffer shrinks to fees |
| B6 | A terminated agreement blocks buying but permits selling | client assets must not be trapped | a legal review may impose a different rule; the state machine in 5.5 is the place it lives |
| B7 | On a new version of the terms, existing customers continue under the accepted version until the next order | least disruptive | blocking all trading until re-acceptance is a business decision with a revenue cost |
| B8 | Dividends, corporate actions and fractional shares are **out of scope for v1** | not mentioned in the brief | each is a subsystem, not a feature; corporate actions in particular rewrite historical quantities (11.3) |

## 12.3 Regulatory assumptions

| # | Assumption | Rationale | If false |
|---|---|---|---|
| R1 | The nightly report to FI is a **file or API submission** with a prescribed schema, versioned, with corrections as new submissions | standard regulatory practice | the reporting service's data model is driven by the actual specification; nothing structural changes |
| R2 | MiFID II applies: client categorisation, appropriateness assessment, best execution, costs disclosure | the bank offers investment services to retail customers in the EU | if the service is execution-only to professionals, the Contract service's model simplifies considerably |
| R3 | Record retention is **five years**, extendable to seven | MiFID II record-keeping | affects storage sizing only |
| R4 | Client securities are held by a **custodian in a nominee structure**; the Portfolio service records a claim, not the asset itself | standard for intermediated access | if the bank is the custodian, custody becomes a service in the platform and reconciliation changes counterparty (11.1.4) |
| R5 | AML and market abuse monitoring for investments will be delivered as a new consumer of platform events | today's AML only sees the core | this is not optional; it is listed as an assumption only because the brief did not scope it (11.1.5) |

## 12.4 Technical assumptions

| # | Assumption | Rationale | If false |
|---|---|---|---|
| T1 | The broker accepts **our order id** as a client order id and its API is idempotent on it | required for timeout recovery | without it, the `UNKNOWN` state cannot be resolved by polling, and the platform must maintain a broker-side id mapping written *before* the call — a durable pre-write with all its own failure modes (6.2) |
| T2 | Broker execution callbacks are **at-least-once**, possibly duplicated, possibly out of order | safest assumption for any external async channel | if exactly-once were guaranteed we would not rely on it anyway |
| T3 | The bank core can be extended with a `holds` table and an `available_balance` check on every debit path | the minimal irreducible change (7.3) | if the core cannot be touched at all, holds are impossible and the only correct design is a segregated investment cash account (option B′) from day one |
| T4 | The core already has a blocked-amount mechanism for card authorisations, which the hold extends conceptually | typical of any bank core that issues cards | if not, the change to the core is larger and the case for B′ strengthens |
| T5 | Market data arrives as a **subscription stream**, licensed per entitled end user | how market data is actually sold | polling a REST price API changes cost, latency and the whole of section 9 |
| T6 | Approximately 300 ms conflation is acceptable for "near real-time" | imperceptible to a human reading prices | a trading desk would demand microseconds; a retail app would tolerate a second |

## 12.5 Non-functional assumptions

The brief specifies exactly one quantity: *thousands* of concurrent users. Everything else below was
invented and must be replaced with agreed numbers.

| # | Assumption | Rationale |
|---|---|---|
| N1 | ~10 000 concurrent WebSocket connections at peak; a few hundred distinct instruments watched | scale implied by "thousands of users" and by the Baltic instrument universe |
| N2 | Order volume is low relative to price traffic — hundreds per minute at peak, not thousands per second | retail investment service, not a trading venue |
| N3 | Availability target for order entry during market hours is high; overnight batch has a longer window | orders that cannot be cancelled at market close are customer harm, not downtime (11.4) |
| N4 | Recovery objectives (RTO/RPO) for a system recording client asset claims are near-zero data loss | client claims cannot be reconstructed from a screenshot |

## 12.6 The questions I would ask before writing code

Ordered by how much of the design they would change:

1. **Who converts EUR to USD, when, and at what rate?** (B4, 11.1.1)
2. **Can the bank core be modified to hold reservations — and does it already, for card authorisations?** (T3, T4)
3. **Does the broker accept a client order id, and is its order-placement endpoint idempotent on it?** (T1)
4. **Partial fills: does the broker report them incrementally, and must the customer be able to cancel the remainder?** (B1)
5. **Who is the custodian, and are client assets segregated?** (R4)
6. **What exactly is the FI report — schema, deadline, correction mechanism?** (R1)
7. **Is the customer's cash to remain in the core, or will a segregated investment account be created?** (B4, T3 — this is the fork between option A and option B′ of section 7.3)

The last one is the fork in the road. Everything in section 7 is written so that the answer can
arrive late without invalidating the design — but it should not arrive after the first release.

---

## 12.7 A note on how these assumptions were chosen

Where an assumption could be made in either direction, it was made in the direction that is
**cheaper to reverse**:

* Holds live in the core, because moving them out later is a migration; discovering that an external
  hold is invisible to the payments module is an incident.
* The domain model is broker-agnostic, because the second broker is cheap to plan for and expensive
  to retrofit.
* Modules first, services second, because splitting a module is mechanical and merging two services
  is not.
* Events carry state, because widening an event is compatible and adding a synchronous callback is
  not.

Reversibility is the only property a design can offer against requirements it has not been told.
