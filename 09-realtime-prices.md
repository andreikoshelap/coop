# 9. Near real-time price display in the app and internet bank

> Question: *How, and with which technology, would you display near real-time price information in
> the app and internet bank, given that thousands of users may be logged in concurrently?*

## 9.1 Framing the problem correctly

The naive reading is "we need WebSockets". The real constraint is the **fan-out ratio**.

Thousands of concurrent users are looking at, at most, a few hundred distinct instruments — the
Nasdaq Baltic main list, a handful of popular US tickers, whatever is on their watchlist. The
system must therefore hold **one upstream subscription per instrument**, never one per user, and
multiply that stream internally.

Two consequences follow immediately, and they are the answer:

1. Exactly one component talks to the market data provider. Everything else consumes an internal
   stream.
2. The number of upstream subscriptions scales with the **instrument catalogue**, not with the user
   count. Ten thousand users cost the same upstream as ten.

Getting this wrong is not a performance bug — it is a **licensing bill**. Market data is priced per
subscription and per entitled end-user; a per-user upstream subscription would be both slow and
financially absurd.

## 9.2 Upstream: subscribe, do not poll

The provider (the broker itself, Nasdaq Nordic, Refinitiv, Bloomberg) pushes over FIX/FAST,
WebSocket, or multicast. We subscribe once and receive ticks. Polling an external price API on a
timer is wrong on every axis: latency, cost, and rate limits.

**Entitlements are an architectural concern, not an accounting one.** Real-time data is licensed
per end user, and the price differs for retail vs professional clients; delayed data (typically
15 minutes) is cheap or free. The Market data service therefore keys every subscription by
entitlement level and serves the delayed topic to users who are not entitled to real time. This
must exist from day one — retrofitting entitlements into a streaming pipeline is painful.

## 9.3 Internal distribution

```
provider → Market data service → last-value cache + Kafka/Redis → gateway instances → clients
```

* **Market data service** — the single upstream client. Normalises ticks into our own model
  (an anti-corruption layer, same as the Broker gateway), maintains the **last value cache** (LVC).
* **Kafka compacted topic keyed by ISIN**, or Redis pub/sub. Compaction matters: a new gateway
  instance can rebuild the current price of every instrument from the topic without asking upstream.
* **Gateway instances** — stateless, hold the client connections, fan out to subscribers.

Prices are **not durable business data**. They are not audited, not reported, and a lost tick is
replaced by the next one. This is the one chain in the whole platform that needs no delivery
guarantee at all (see section 6.2, chain E).

## 9.4 Conflation: the single most important optimisation

A liquid instrument produces hundreds of ticks per second. A human eye resolves maybe four. Pushing
every tick to every client is pure waste — and worse, a slow mobile connection will build an
unbounded backlog of stale prices.

We **conflate**: for each instrument keep only the latest value and emit a snapshot on a fixed
cadence (250–500 ms is imperceptible and cuts traffic by one to two orders of magnitude).

```java
Flux<PriceTick> priceStream(Set<String> isins) {
    return marketDataStream
        .filter(tick -> isins.contains(tick.isin()))
        .onBackpressureLatest()                       // drop stale, never buffer
        .sample(Duration.ofMillis(300))               // conflate to display cadence
        .groupBy(PriceTick::isin)
        .flatMap(group -> group.sample(Duration.ofMillis(300)));
}
```

`onBackpressureLatest()` is the load-bearing line: a slow consumer drops intermediate prices rather
than stalling the stream or exhausting memory. For price data, dropping intermediate values is not
data loss — the intermediate values are *already wrong*.

## 9.5 Transport: WebSocket, SSE, or polling

| | Polling | SSE | WebSocket |
|---|---|---|---|
| Direction | request/response | server → client | bidirectional |
| Overhead per update | full HTTP round trip | minimal | minimal |
| Reconnect | n/a | built into the protocol | manual |
| Proxy / corporate firewall | trivial | good over HTTP/2 | occasionally blocked |
| Client subscribes / unsubscribes | new request | via separate REST call | in-band |

**Choice: WebSocket**, because subscriptions are dynamic — the user scrolls a watchlist, opens an
instrument page, switches tabs, and the set of instruments they care about changes constantly.
In-band `subscribe` / `unsubscribe` frames keep the gateway from pushing prices nobody is looking
at. The same socket also carries order status updates, which the customer expects to see change
without a refresh.

SSE would be a defensible alternative if prices were the only push and subscriptions were static.
Polling is defensible for nothing here.

Implementation: **Spring Boot + WebFlux on Reactor Netty**. Non-blocking I/O is the reason:
ten thousand idle WebSocket connections on a servlet-per-thread model means ten thousand threads;
on Netty it means ten thousand file descriptors and a handful of event-loop threads. This is the
one place in the platform where the reactive stack genuinely earns its complexity — the rest of the
services (Order, Portfolio, Contract) are ordinary blocking Spring MVC, because their bottleneck is
the database, not connection count.

## 9.6 Snapshot + delta

On connect, the client must not stare at empty cells until an illiquid instrument happens to tick.
The gateway therefore serves:

1. an immediate **snapshot** from the last-value cache (Caffeine, per gateway instance, warmed from
   the compacted topic),
2. followed by the **delta stream**.

The same sequence runs on every reconnect. Mobile clients lose their socket constantly — in a
lift, on the tram — so reconnect with exponential backoff plus snapshot replay is normal operation,
not an error path.

## 9.7 Scaling and topology

* Gateway instances are stateless with respect to *business* state; subscription state lives in the
  socket, which by nature is pinned to one instance. The load balancer needs connection affinity for
  the socket's lifetime — this is inherent to WebSocket, not a design choice.
* Adding a gateway instance costs one more consumer on the internal topic. Upstream is untouched.
* Realistic budget: ~10k concurrent sockets per instance on WebFlux with tuned file descriptors and
  heap. Thousands of users therefore fit on a small number of instances with room to fail over.
* Metrics that actually matter: open connections, ticks/s in vs out, **conflation ratio**, dropped
  ticks, and time-since-last-tick per instrument (a silent feed is the failure that goes unnoticed).

## 9.8 What must never come from this stream

The displayed price is **indicative**. It is not the execution price, it is not an offer, and it is
never used to compute what the customer is charged. The execution price comes from the broker's
execution report and nowhere else; the regulatory report in section 8 uses executed prices, not
display prices.

Mixing these up is the kind of mistake that ends in a compliance incident. The channels must show a
timestamp and a "delayed" badge when the user is not entitled to real time.

## 9.9 Summary

* One upstream subscription per instrument, never per user. Fan-out internally.
* Entitlements (real-time vs delayed, retail vs professional) are designed in from day one.
* Conflation at ~300 ms, `onBackpressureLatest` — dropping stale prices is correct, not lossy.
* WebSocket over Spring WebFlux / Reactor Netty; the rest of the platform stays blocking MVC.
* Snapshot from the last-value cache on connect, delta stream afterwards; reconnect is routine.
* Prices are the one non-durable chain in the platform — no guarantees needed, none provided.
* Display price is indicative and never the basis for execution or reporting.
