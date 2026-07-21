# 10. Showing position return in EUR when the position is in USD

> Question: *Customers want to see the return on their positions in the e-channels. The position is
> in USD, the return should be displayed in EUR. How would you implement this?*

## 10.1 The question contains three questions

1. What is the position worth **right now**?
2. What did it **cost**?
3. In **which currency** is the return measured — and how much of it is the security, and how much
   is the euro?

Only the first one is answered by the price stream from section 9. The other two are the hard part,
and they are the reason this question is on the list.

## 10.2 Return in EUR is not return in USD

A worked example, because the arithmetic is the argument:

| | At purchase | Today |
|---|---|---|
| Price | $150.00 | $165.00 |
| Quantity | 100 | 100 |
| Value (USD) | $15 000 | $16 500 |
| EUR/USD | 1.10 | 1.20 |
| Value (EUR) | €13 636.36 | €13 750.00 |

**In USD the customer is up 10.0 %. In EUR the customer is up 0.83 %.**

The stock rose; the dollar fell; the euro investor kept almost nothing. Displaying "+10 %" next to
a EUR value would be, at best, misleading — and for a regulated investment service, arguably a
mis-selling risk.

The return therefore decomposes:

```
total return (EUR) = price return (USD) + FX return + cross term
                   = 10.00 %          + (−8.33 %)  + (−0.83 %)   ≈ 0.83 %
```

A mature UI shows both numbers, labelled: return in the instrument's currency, and return in the
customer's reporting currency. The task text asks for EUR, but showing only EUR hides *why* the
number looks the way it does.

## 10.3 The invariant: never re-price history

**The FX rate used for a trade is recorded at the moment of the trade and is never recomputed.**

If the cost basis is stored only in USD and converted with today's rate whenever a report runs,
then last month's statement will show a different number this month. For a customer-facing screen
that is confusing; for a regulatory report (section 8) it is a defect — the report must be
reproducible, byte for byte, on any future date.

So the trade record stores, immutably:

* executed price, quantity, currency, fees, and their currency;
* the **FX rate applied**, its source, and its timestamp;
* the settlement amount in both the instrument currency and the account currency.

## 10.4 Where each number comes from

| Number | Source | Durable? | Authoritative? |
|---|---|---|---|
| Cost basis (EUR and USD) | Portfolio service, written at execution | yes | yes |
| Realized P&L | derived from settled trades | yes | yes |
| Current market price | Market data stream (section 9) | **no** | **no** — indicative |
| Current FX rate | FX rate service | cached, snapshotted daily | for display |
| Unrealized P&L | computed on the fly | no | indicative |
| End-of-day valuation | snapshot job → Portfolio store | yes | for history and charts |

This table is the actual answer to the question. The price feed feeds exactly one row — unrealized
valuation — and nothing else. Realized results, tax figures and regulatory reports never touch it.

## 10.5 The FX rate service

A small service (or a module of Market data), because it has its own concerns:

* **Which rate?** ECB publishes daily euro reference rates around 16:00 CET — free, authoritative,
  and the natural choice for statements and end-of-day valuation. Intraday display needs a live
  feed from the market data provider.
* **Which side?** Mid rate for display. But if the customer actually converts money, the bank's own
  customer rate (with spread) applies — and it will differ from the number on the screen. The screen
  must say so. This asymmetry is a frequent source of complaints to customer service, which is
  precisely the CRM concern raised in the task.
* **Caching.** Short TTL in memory for intraday, plus a persisted end-of-day snapshot per currency
  pair, keyed by date. The snapshot is what statements and reports read; the cache is what screens
  read.
* **Convention.** Store the pair with an explicit direction (`EURUSD = 1.20`, meaning one euro buys
  1.20 dollars) and never let two services disagree about which way it points. Half of all currency
  bugs are an inverted rate.

## 10.6 Where the calculation happens

Not in the frontend. Two channels (app, internet bank) computing money independently will diverge
in rounding, and neither can be audited.

The **Portfolio service** owns positions and cost basis. A valuation component joins:

```
position (qty, cost basis)  ×  current price  ×  current FX  =  current value, unrealized P&L
```

Intraday values are computed on request and cached briefly. End-of-day valuations are computed by a
scheduled job and stored — this gives the history for return charts without recomputing the past
from prices we no longer have (and, per section 9, never stored).

```java
public Valuation valuate(Position pos, Price price, FxRate fx) {
    BigDecimal marketValueUsd = price.value().multiply(pos.quantity());
    BigDecimal marketValueEur = marketValueUsd.divide(fx.eurUsd(), 10, RoundingMode.HALF_UP);

    BigDecimal unrealizedEur  = marketValueEur.subtract(pos.costBasisEur());
    BigDecimal returnEur      = unrealizedEur.divide(pos.costBasisEur(), 6, RoundingMode.HALF_UP);
    BigDecimal returnUsd      = marketValueUsd.subtract(pos.costBasisUsd())
                                              .divide(pos.costBasisUsd(), 6, RoundingMode.HALF_UP);

    return new Valuation(marketValueEur, unrealizedEur, returnEur, returnUsd, fx.asOf());
}
```

Note `fx.asOf()` in the result: every displayed valuation carries the timestamp and rate it was
computed with. When a customer calls support disputing a number, the CRM operator must be able to
reproduce it exactly.

Money is `BigDecimal` throughout; rates are stored with 8–10 decimal places; rounding happens
**once, at presentation**. Rounding an intermediate result and then multiplying it is how a bank
loses cents at scale.

## 10.7 The business rule that must be decided, not assumed

The customer buys AAPL three times at different prices, then sells half. Which lots were sold?

* **FIFO** — first in, first out. Common, and the default under Estonian personal income tax rules.
* **Weighted average cost** — simpler, but produces a different realized P&L and a different tax
  figure.

This is not an implementation detail; it changes what the customer owes the tax authority. It must
be specified by the business, applied consistently, and stored per lot. I assume **FIFO with
per-lot tracking** (see section 12, Assumptions) — which means the Portfolio service stores *lots*,
not just an aggregate quantity.

## 10.8 What is quietly missing from this requirement

* **Dividends.** Price return ≠ total return. A dividend-paying stock will show a
  systematically understated return if dividends are ignored — and US dividends to Estonian
  residents carry withholding tax (W-8BEN), which affects the net figure.
* **Fees and commissions.** They belong in the cost basis, otherwise every position looks slightly
  more profitable than it is.
* **Corporate actions.** A 4-for-1 split changes quantity and per-share cost basis overnight.
  Without handling, the return chart shows a −75 % collapse.
* **Money-weighted vs time-weighted return.** If the customer adds funds mid-period, a naive
  percentage is meaningless. Statements typically need one of each, for different purposes.

These are listed again in section 11 — they are the parts of "show the return" that the task text
does not mention and that turn a one-line formula into a subsystem.

## 10.9 Summary

* Return in EUR is not return in USD; the FX move is part of the result, not a conversion of it.
  Show both, decomposed.
* Store the FX rate with the trade. Never re-price history — reports must be reproducible.
* The price stream from section 9 feeds unrealized valuation only. Realized P&L, statements and
  regulatory reports never read it.
* FX rate service: ECB reference for end-of-day and statements, live feed for intraday display,
  explicit pair direction, snapshot per date.
* Calculate in the Portfolio service, never in the channels. Every valuation carries the rate and
  timestamp used.
* `BigDecimal` end to end; round once, at presentation.
* FIFO vs average cost is a business decision with tax consequences — decide it explicitly and
  store lots.
