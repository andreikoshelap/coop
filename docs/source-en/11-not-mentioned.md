# 11. What matters here and what has been left entirely unmentioned

> Question: *Describe the points that are important in this topic and that have currently been left
> completely unmentioned.*

The task states that business functionality was deliberately omitted. What follows is not a
complaint about the brief — it is the list a designer must produce before writing a line of code,
because several of these items **change the architecture**, not merely the backlog.

They are ordered by that criterion.

---

## 11.1 The five that would change the design

### 1. Currency conversion — nobody in this task converts anything

The customer holds **EUR**. The security is priced in **USD**. Somewhere between the reservation and
the settlement, euros become dollars. The brief never says who does it.

This is not a detail; it reshapes section 7:

* Is the FX conversion a separate transaction against the core, or does the broker convert?
* If the bank converts: which rate, which spread, and is the conversion reserved too?
* The hold is placed in EUR before the rate is final. **The reserved amount must therefore cover both
  price slippage and FX movement**, or a trade will settle for more euros than were reserved.
* Does the customer hold a USD cash balance? If yes, the core needs multi-currency accounts, or the
  platform needs its own multi-currency cash ledger (which is option B′ from section 7.3, arriving
  by a different road).

Every answer produces a different system. This is the single largest gap in the brief, and the first
question I would take back to the business.

### 2. Settlement cycle (T+1 / T+2)

Execution and settlement are not the same instant. The trade executes today; cash and securities
change hands one or two business days later.

Consequences the brief does not acknowledge:

* Between execution and settlement the customer **owns something they have not paid for**. Who
  carries that exposure — the bank or the broker?
* Does the customer's position appear immediately (trade date accounting) or on settlement date?
  The screen and the regulatory report may legitimately disagree, and both must be right.
* The hold cannot simply be released at execution; the money must move at settlement. Section 7's
  `settle` step therefore has a *date*, not just an event.

### 3. Order types, partial fills, cancellation and amendment

The brief says "buy/sell". Real orders have a type (market, limit, stop), a validity (day, GTC), and
they **fill partially**: 100 shares requested, 60 filled today, 40 tomorrow, or never.

* A partially filled order settles partially. The hold releases partially. Section 7's saga must
  handle N fills per order, not one.
* The customer will want to **cancel** a pending order. Cancellation is a race against the broker —
  the fill may already be in flight. This needs its own saga with its own `UNKNOWN` state, and it is
  entirely absent from the brief.
* Amendment (change the limit price) is cancel-and-replace, with the same race.

Designing the order aggregate around a single all-or-nothing fill and discovering partial fills
later is a rewrite of the core domain.

### 4. Client asset segregation and custody

Who actually **holds** the customer's shares? The brief says an external broker intermediates, but
says nothing about custody. In practice the securities sit with a custodian, usually in a nominee
account.

* If the broker fails, are the client's assets segregated from the broker's own?
* Investor protection schemes, and the bank's liability, depend on the answer.
* The Portfolio service's positions are then a *record* of a claim held elsewhere, which is exactly
  why reconciliation against the custodian (section 6.4) is a regulatory expectation and not an
  engineering nicety.

This is a legal structure question that dictates a data model.

### 5. AML and market abuse for the investment flow

Today AML monitors the core. The core sees payments. It will not see a single securities trade.

Investment accounts are a known vector for laundering and for market abuse — insider dealing,
layering, wash trades. Under MAR the bank has an obligation to detect and report suspicious
transactions (STOR). This requires AML to consume `OrderExecuted` and `PositionChanged` events
(section 3.6), and it requires someone to define what "suspicious" means in this context.

The brief lists AML as an existing system and moves on. In the TO-BE picture it is a new consumer
with new rules.

---

## 11.2 Regulatory and legal

* **MiFID II obligations** beyond the agreement itself: client categorisation, appropriateness and
  suitability assessment, target market, **best execution** policy and its evidencing, ex-ante and
  ex-post costs and charges disclosure, inducements.
* **Transaction reporting** — the brief says "report to FI". In practice reports often flow through
  an Approved Reporting Mechanism, and the format, fields and deadlines are prescribed. The
  reporting service's data model must be driven by that specification, not invented.
* **Clock synchronisation.** Regulatory timestamps have prescribed granularity and must be traceable
  to UTC. "The server clock" is not an answer.
* **Taxation.** Estonian residents may use the investment account (*investeerimiskonto*) regime,
  which changes when gains become taxable. Dividends from US securities carry withholding tax and
  require a W-8BEN. Annual tax statements to customers and to the tax authority are a deliverable
  nobody has mentioned.
* **GDPR against record keeping.** MiFID demands five to seven years of records; GDPR demands
  minimisation and erasure. The conflict is resolved by law, but it must be resolved *explicitly*,
  in the retention policy, per data category.
* **Complaints and dispute resolution** — which is, incidentally, why every valuation must carry the
  rate and timestamp it was computed with (section 10.6).

## 11.3 Product and business

* **Corporate actions.** Splits, reverse splits, mergers, ticker changes, rights issues, spin-offs.
  A 4-for-1 split silently turns a return chart into a −75 % cliff if unhandled.
* **Dividends.** Price return is not total return. Cash dividends, withholding tax, and their effect
  on the figures in section 10.
* **Fees.** Bank commission, broker commission, FX spread, custody fee. They belong in the cost
  basis, and they must be disclosed before the customer confirms the order.
* **Market hours, holidays, trading halts.** An order placed at 23:00 Tallinn time for a Nasdaq
  Baltic instrument does not execute. What does the app say? What happens to the hold overnight —
  and how does that interact with the TTL in section 7.6?
* **Cost basis method** — FIFO vs weighted average (section 10.7). A tax consequence, not a code
  style.
* **Fractional shares**, if offered, break the assumption that quantity is an integer.
* **Pre-trade limits and risk checks.** Maximum order size, fat-finger protection, daily turnover
  limits. Cheap to add before the order reaches the broker; expensive to add after an incident.
* **Order cancellation by the *bank***: compliance suspension, sanctions hit, account freeze.

## 11.4 Operational and non-functional

* **The brief contains no non-functional requirements at all.** "Thousands of concurrent users" is
  the only quantity given. Availability targets, latency budgets, RTO/RPO for a system holding client
  assets, expected order volume — all invented in section 12 as assumptions, all of which should be
  numbers agreed with the business.
* **Broker API constraints.** Rate limits, sandbox availability, whether the broker accepts a client
  order id (section 6.2 depends on it), whether callbacks are at-least-once, what its own idempotency
  guarantees are. The integration in section 3.4 assumes answers.
* **A second broker.** The anti-corruption layer exists precisely so that adding or replacing a
  broker is a new adapter. Whether the business wants that should be stated, because it affects how
  aggressively the domain model is kept broker-agnostic.
* **Market data licensing cost**, per entitled user, real-time vs delayed (section 9.2). An
  architectural constraint disguised as a line item.
* **Disaster recovery and business continuity.** If the platform is down at market close, orders
  cannot be cancelled. That is a customer-harm scenario, not an availability metric.
* **Fraud and account takeover.** An investment account is a way to move value out of a compromised
  bank account. Step-up authentication before the first trade, and on unusual activity.

## 11.5 Organisational

* **Build versus buy.** Banks frequently buy an off-the-shelf investment platform rather than build
  one. The brief presupposes building. That presupposition deserves one honest paragraph, because it
  is the most consequential decision in the whole exercise and it has already been made for us.
* **Team topology.** A granular service landscape requires teams that own services. Fifteen services
  and one team produces fifteen ways to be blocked. This is why section 4.2 proposes modules first,
  services when a team exists to own them.
* **SLA with the broker**, and the on-call rota that answers when reconciliation breaks at 03:00.

---

## 11.6 The honest summary

Three of the five items in 11.1 — currency conversion, settlement cycle, partial fills — each
independently invalidate a simplifying assumption made elsewhere in this document. They are
recorded as assumptions in section 12 rather than solved, because solving them requires answers from
the business that the brief does not contain.

Naming them is the deliverable. An architecture that quietly assumes a single instantaneous fill,
in one currency, settling immediately, is not simple — it is wrong, and it will be discovered to be
wrong by the first customer.
