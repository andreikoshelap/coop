# 5. Where to store the investment services agreement

> Question: *Before using the service, the customer must sign an investment services agreement.
> The customer's other agreements are today stored in the bank core. Where in the system, and how,
> would you keep this information?*

## 5.1 The trap in the question

The question invites the answer "in the core, next to the other agreements — one place for
everything". That answer is wrong, but it is wrong for an interesting reason, and saying *why* is
the point of this section.

An investment agreement is not the same kind of object as a loan agreement or a current account
agreement. It carries attributes that exist only in the investment domain and that the core has no
vocabulary for:

* MiFID II client categorisation (retail / professional / eligible counterparty);
* the appropriateness and suitability assessments, and their results;
* the customer's risk profile and declared knowledge and experience;
* the target market assessment;
* tax residency, and US withholding status (W-8BEN) for US securities;
* the market data entitlement level — whether this customer is licensed for real-time prices
  (section 9.2) or must be served the delayed feed;
* the accepted version of the terms and conditions.

None of these mean anything to the payments module. Putting them in the core means either polluting
the core's data model with a foreign domain, or storing them somewhere else anyway and having the
agreement split across two systems — the worst of both.

## 5.2 The decision

**The Contract service inside the investment platform is the master of the investment agreement.**

The core remains the master of what it is genuinely the master of: customer identity, KYC status,
accounts, money. The Contract service stores only `customerId` plus the investment-specific
attributes above. We do not duplicate master data; we reference it.

| Data | Master | Everyone else |
|---|---|---|
| Customer identity, KYC | Bank core | read-only |
| Accounts and balances | Bank core | read-only |
| Investment agreement, MiFID profile | Contract service | read-only |
| Signed document | Document store / bank DMS | reference + hash in Contract service |

## 5.3 How the rest of the bank still sees "one customer"

The objection to the decision above is legitimate: the bank wants a single view of the customer's
contractual relationships, and customer service must answer questions about them.

The answer is **events, not shared tables**. On activation the Contract service publishes
`ContractActivated { customerId, contractId, type, version, activatedAt }` to the event backbone.
The core, CRM and the DWH build read-only projections from it.

The rule, stated in one line for the document:

> We do not duplicate master data — we publish facts. The Contract service owns the agreement;
> everyone else owns a projection of the fact that it exists.

The CRM operator sees "investment agreement, active since 12.03.2026" without the CRM knowing
anything about MiFID target markets. If the operator needs the detail, the CRM calls the Contract
service — it does not store a copy.

## 5.4 Why the authoritative check is synchronous

The Order service must not trade for a customer without a valid agreement. That check hits the
**Contract service directly and synchronously** (step 2 of the buy sequence), not a replicated
projection.

The reason is the gap in eventual consistency: a customer signs the agreement, and for the next few
hundred milliseconds the core's projection says they have not. If the order path read the
projection, we would either block a legitimate customer or — with a stale "active" flag after
termination — trade for a customer who no longer has an agreement. The projections are for display;
the owner of the data answers questions of authority.

This is the general principle from section 6.5: never let two services claim to answer the same
question.

## 5.5 Lifecycle, not a boolean

An agreement is not a flag. It is a state machine, and the states have trading consequences:

| State | Buy | Sell / close position |
|---|---|---|
| `DRAFT` | no | no |
| `ACTIVE` | yes | yes |
| `SUSPENDED` (e.g. expired appropriateness test) | no | **yes** |
| `TERMINATED` | no | **yes**, until the portfolio is empty |

The asymmetry in the last two rows matters and is routinely missed: a customer whose agreement has
ended must still be able to liquidate what they already own. Blocking sells on a terminated
agreement traps client assets, which is a regulatory problem, not a UX one.

Terms and conditions are **versioned**. When the bank publishes v3, existing customers keep trading
under v2 until they accept, or are blocked at the next order — a business decision, recorded in
section 12 as an assumption.

## 5.6 Signature and evidence

In Estonia the agreement is signed with an eIDAS-qualified electronic signature — Smart-ID or
Mobile-ID. The signed container is stored in the bank's document store, not in the Contract
service's database.

The Contract service keeps the **reference and the hash**, plus the evidence trail: which channel,
which timestamp, which version of the terms, which IP and device. Years later, in a dispute, the
question is not "what does the database say" but "prove what the customer agreed to, and when".

Retention follows MiFID II: records of the agreement and the assessments are kept for at least five
years after the relationship ends, extendable to seven at the regulator's request.

## 5.7 Summary

* The investment agreement lives in the platform's Contract service, because it is that bounded
  context's data — it carries MiFID attributes the core has no vocabulary for.
* The core stays the master of identity, KYC, accounts and money.
* The single customer view is preserved by publishing `ContractActivated`, not by sharing a table.
* The pre-trade check is synchronous, against the owner. Projections are for display only.
* The agreement is a lifecycle, not a boolean; a terminated customer must still be able to sell.
* The signed document lives in the document store; the Contract service keeps the reference, the
  hash and the evidence trail.
