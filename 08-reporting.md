# 8. Reporting: through the Data warehouse, or inside the investment platform?

> Question: *Would it be reasonable to do the reporting through the Data warehouse, or could this
> task be part of the investment services platform?*

## 8.1 The question conflates two different reports

The task mentions reporting twice, and they are not the same thing:

1. **"We are obliged to report customer transactions to the regulator every night."** A legal
   obligation, with a deadline, a defined format, and a person who is accountable if it is late or
   wrong.
2. **Analytical and business reporting.** Which instruments sell, what the average portfolio looks
   like, how the product is performing.

Answering "yes, through the DWH" or "no, in the platform" without separating these is the mistake
the question is testing for. The answer is **both, for different reports** — and the reasoning is
what matters.

## 8.2 The regulatory report belongs to the platform

A dedicated **Regulatory reporting service** inside the investment platform, with its own store.

The argument is not technical elegance. It is accountability:

**Ownership.** The obligation to report to FI belongs to the investment business. A service owned by
that business, deployed with it, tested with it, and alerted on by its on-call rota, is the only
place where "the report did not go out last night" has an owner.

**Coupling to a deadline.** The DWH is loaded by nightly batch ETL, tuned for BI, and its schema
changes when analysts need new dimensions. If the ETL is late, or a BI-driven schema change breaks a
join, the bank misses a regulatory deadline. Making a legal obligation depend on an analytics
pipeline is an unforced risk.

**Completeness, not consistency.** Analytics tolerates a missing row; the regulator does not. The
reporting service consumes `OrderExecuted` events and must *detect* gaps, not merely process what
arrives. Per-aggregate sequence numbers, a gap detector, and an alert when the sequence breaks —
none of which a DWH loader would ever be built to do.

**Reproducibility.** A report submitted for 12 March must be regenerable, identically, in three
years. This requires storing the immutable trade records as they were, the FX rates as they were
(section 10.3), and the produced report artefact plus its hash. A DWH whose dimensions are updated
in place cannot promise this.

**Corrections.** Regulators expect amendments and cancel/replace submissions. Reports are therefore
versioned submissions with a lifecycle — `GENERATED → SUBMITTED → ACKNOWLEDGED → AMENDED` — which is
an application concern, not a warehouse concern.

**Retention.** MiFID II record-keeping is five years, extendable to seven. The reporting store is
built for that from the start.

**And the practical one.** Today the DWH imports from the bank core. The investment data does not
exist in the core. Routing regulatory reporting through the DWH would mean building a *new* pipeline
into a *legacy* system to serve a *new* obligation — new dependency, no benefit.

## 8.3 Design points for the reporting service

* Consumes the same `OrderExecuted` / `TradeSettled` events as everyone else, from the event
  backbone. It is a normal consumer, not a special case.
* Writes an **immutable** trade record to its own store. It never reads Portfolio's database.
* **Cutoff by trade timestamp, not ingestion timestamp.** A trade executed at 23:59:58 and consumed
  at 00:00:03 belongs to the previous reporting day. Getting this backwards produces a report that
  is complete but wrong, which is worse than one that is late.
* **Reconciliation before submission.** The nightly report is generated from our records, after they
  have been reconciled against the broker's statements (section 6.4). A discrepancy blocks
  submission and raises an alert; it does not silently pick one side.
* Late fills and amendments after the cutoff produce a correction submission, not a rewritten report.

## 8.4 The DWH keeps analytics — but stops reading the core for this

Business intelligence, product analytics, customer segmentation: all still the DWH's job. Nothing
here argues for a second analytics stack.

What changes is **how the data gets there**. Today the DWH pulls from the bank core. For investment
data it instead **subscribes to the event backbone** — the same `OrderExecuted`, `PositionChanged`,
`ContractActivated` events that Portfolio, CRM and AML consume.

This is a strictly better integration than the point-to-point import that exists today:

* the platform does not know the DWH exists, and does not break when the DWH schema changes;
* adding a fourth consumer costs nothing;
* the DWH's load no longer runs against a production transactional database.

## 8.5 The general principle

> Reporting that the bank is **legally obliged** to produce is a product feature, and lives with the
> product. Reporting the bank **wants** in order to understand itself is analytics, and lives in the
> warehouse.

The regulator does not accept "the ETL was late" as an explanation. An analyst does.

## 8.6 Summary

* Two reports, two homes. The distinction is accountability, not technology.
* Regulatory reporting to FI: a service inside the investment platform, with its own immutable
  store, gap detection, reproducible artefacts, versioned submissions, and MiFID retention.
* Never make a legal deadline depend on an analytics pipeline.
* Cutoff by trade timestamp; reconcile against the broker before submitting; corrections are new
  submissions, not rewrites.
* The DWH keeps business analytics and switches from importing out of the core to subscribing to
  the event backbone.
