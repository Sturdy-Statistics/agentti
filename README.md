# agentti

[![Clojars Project](https://img.shields.io/clojars/v/com.sturdystats/agentti.svg)](https://clojars.org/com.sturdystats/agentti)

**Minimal background workers for Clojure services.**

**Noun:** ([Finnish](https://translate.google.com/?sl=fi&tl=en&text=agentti&op=translate)):
agent, operative

`agentti` provides a small, explicit framework for running **periodic background tasks** inside a long-running JVM process.
It is designed for internal services and data pipelines that need a handful of highly reliable background jobs, but do not require heavyweight infrastructure.

Agentti was developed to meet the internal security and operational requirements of **Sturdy Statistics**.
It is published as open source to support transparency, auditability, and reuse, but its design is intentionally conservative and driven by real production needs.
We may not accept feature requests that dilute its focus.

> [!WARNING]
> **NOTE** v0.2.0 represents a breaking change from v0.1.x.
> `agentti` moved from using `chime` to run tasks to using `core.async`.
> Its API changed slightly in the process.

## Rationale

Many Clojure and JVM services need *a small number of background tasks* but do not want the complexity of a full job system (Quartz, distributed queues).

`agentti` takes a deliberately resilient approach by splitting the problem in two:
1. Chronology (`java.time` + `chime`): Pure generation of absolute-time sequences.
2. Execution (`core.async` + `java.util.concurrent`): A `core.async` state machine that handles sleeping, timeouts, dropped ticks, and graceful shutdowns, with tasks run in dedicated JVM threads for cancellation.

The result is a library that guarantees explicit execution semantics—tasks never overlap, timed-out threads are explicitly interrupted, and long-running schedules are immune to system clock drift.
JVM interruption is cooperative, so a timed-out task that ignores interruption remains in flight; later ticks are dropped until it exits, after which the worker resumes normally.

## Installation

Add to `deps.edn`:

```clojure
{:deps {com.sturdystats/agentti {:mvn/version "VERSION"}}}
```

## What this library is for

- Running periodic background tasks inside a JVM service (cache refreshers, polling loops, lightweight ETL).
- Tasks that **must not overlap**.
- Explicit lifecycle management (graceful shutdown by default, with forced interruption fallback).
- Simple operational visibility (status, runtime, ETA, errors) for internal dashboards.

## What this library is not for

- Distributed job queues
- Cron replacement
- High-throughput task execution
- Exactly-once or persistent scheduling semantics (if a process dies, in-memory state dies with it)

If you need persistence, distribution, or external coordination, this is not the right tool.

## Schedule drift and jitter

When configuring workers, `agentti` provides an optional `agentti.schedule` utility to generate bounded random walks.

When jitter is enabled, `agentti` intentionally applies it *cumulatively* between runs.
Each execution time is computed relative to the previous execution, not to a fixed wall clock.
This introduces a bounded random walk, so scheduled times gradually drift.
This behavior helps avoid synchronized “thundering herd” effects across workers and across processes, and favors de-correlation over strict calendar alignment.

## Basic usage

Workers require a unique name, a positive integer timeout in milliseconds, a body function, and a seqable schedule of absolute times.
Schedule items may be `java.time.Instant`, `java.time.ZonedDateTime`, `java.util.Date`, or epoch milliseconds.
A `nil` or empty schedule is accepted and registers a dormant worker with no scheduled ticks.

```clojure
(require '[agentti.core :as agentti]
         '[agentti.schedule :as sched])

(agentti/add-worker!
 {:worker-name :session-pruner

  ;; sched/periodic-seq returns a lazy schedule sequence for intervals and jitter.
  :schedule    (sched/periodic-seq 10_000 {:jitter-frac 0.1})

  ;; Execution timeout; on expiry the thread is interrupted. JVM interruption is
  ;; cooperative, so later ticks are dropped until the timed-out task actually exits.
  :timeout-ms  2000

  ;; The work to do
  :body-fn     (fn [] (println "Pruning expired sessions..."))})

;; later… (initiates a 3-second graceful shutdown, then forces interruption)
(agentti/stop-worker! :session-pruner)
```

## Timeouts and cooperative cancellation

> [!WARNING]
> Java thread cancellation is cooperative. When a task exceeds `:timeout-ms`, agentti records a timeout and calls `Future.cancel(true)`, which requests interruption of the executor thread—it cannot forcibly terminate the task.
>
> If the task ignores interruption or is blocked in non-interruptible code, it remains `:in-flight?`. Later scheduled ticks are skipped and included in the worker's `:dropped` count; they are not queued and do not run concurrently.
> The timeout appears in `:last-error` and contributes to `:num-errors`.
> If the task eventually returns, the worker clears `:in-flight?` and resumes on a later tick.
> A subsequent successful run clears `:last-error`, while the cumulative error and dropped counts remain available for monitoring.
>
> Worker bodies should return promptly when interrupted.
> Avoid swallowing `InterruptedException`, prefer interruptible operations, and configure timeouts on network and database calls rather than relying only on the worker timeout.
> If work must be terminated rather than merely interrupted, isolate it in another process and enforce the deadline at the process boundary.

For strict, non-drifting calendar schedules (e.g., “Midnight on the 1st of the month”), pass a standard `chime/periodic-seq`.

```clj
(def ^:private ^ZoneId pacific-tz (ZoneId/of "America/Los_Angeles"))

(defn- daily-at-hour
  "Returns a Chime schedule sequence that fires daily at the specified hour (0-23) in Pacific Time."
  [hour]
  (->> (chime/periodic-seq
        (let [^ZonedDateTime now (ZonedDateTime/now pacific-tz)]
          (-> now
              (.withHour (int hour))
              (.withMinute 0)
              (.withSecond 0)
              (.withNano 0))
        (Period/ofDays 1))
       (chime/without-past-times)))

(def daily-1am-schedule (daily-at-hour 1))
(def daily-2am-schedule (daily-at-hour 2))
(def daily-3am-schedule (daily-at-hour 3))

```

## Introspection / admin

`agentti` tracks granular metrics for every worker, intended for direct exposure to internal admin endpoints or dashboards.

```clj
(require '[agentti.core :as agentti])

(agentti/list-workers)
;; =>
;; [{:worker-name "session-pruner"
;;   :status :idle            ;; :running, :idle, or :stopped
;;   :running? true
;;   :timeout-ms 2000
;;   :num-runs 12
;;   :num-errors 0
;;   :last-error nil
;;   :in-flight? false
;;   :dropped 0               ;; Number of ticks dropped due to overlap
;;   :last-run "2026-04-02T18:30:00Z"
;;   :last-duration 87        ;; ms
;;   :avg-duration 92         ;; ms
;;   :uptime "14d 2h 5m 10s"
;;   :since-last-run "10s"
;;   :next-run-eta "2026-04-02T18:30:10Z"
;;   :next-run-in "10s"}]
```

This is intended for internal admin endpoints or dashboards.

## Appendix: Known issues

### Cancellation before task startup

There is a theoretical race if a task reaches its timeout after submission but before its dedicated executor thread begins the callable.
Cancelling the `Future` prevents the callable from running, so it cannot report completion to the worker loop.
The worker can consequently remain `:in-flight?`, and later ticks will continue to be dropped until the worker or process is restarted.

In normal operation, each worker has a dedicated executor and permits only one in-flight task, so there should be no executor backlog.
Encountering this race with a properly configured positive timeout would require severe JVM or OS scheduling starvation—an operational condition likely to require process or host recovery independently of agentti.
Configure timeouts with enough margin for thread startup and normal scheduling delays.

## License

Apache License 2.0

Copyright © Sturdy Statistics

## Postscript

> **A note to Finnish speakers:**
> We chose the name **agentti** in homage to [metosin](https://github.com/metosin) and out of admiration for the expressiveness of the Finnish language.
> We’re not Finnish speakers, so if we’ve misused the term, we apologize.


<!-- Local Variables: -->
<!-- fill-column: 1000000 -->
<!-- End: -->
