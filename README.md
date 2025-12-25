# agentti

[![Clojars Project](https://img.shields.io/clojars/v/com.sturdystats/agentti.svg)](https://clojars.org/com.sturdystats/agentti)

**Minimal background workers for Clojure services.**

**Noun:** ([Finnish](https://translate.google.com/?sl=fi&tl=en&text=agentti&op=translate)):
agent, operative

`agentti` provides a small, explicit framework for running **periodic background tasks** inside a long-running JVM process. 
It is designed for internal services and data pipelines that need a handful of reliable background jobs, but which do not require heavyweight infrastructure.

## Rationale

Many Clojure and JVM services need *a small number of background tasks* but do not want the complexity of a full job system (Quartz, distributed queues) or the cognitive overhead of building everything on top of `core.async`. 
`agentti` takes a deliberately simple approach: each task runs on its own single-thread executor, is scheduled explicitly, cannot overlap, and can be started, inspected, and stopped as part of the normal service lifecycle. 
While `agentti` is built on top of [`chime`](https://github.com/jarohen/chime) for time-based scheduling, it adds explicit execution semantics—dedicated executors, timeouts, lifecycle management, and introspection.
Such features are often reimplemented ad hoc in production services.

## Installation

Add to `deps.edn`:

```clojure
{:deps {com.sturdystats/agentti {:mvn/version "VERSION"}}}
```

## What this library is for

- Running periodic background tasks inside a JVM service
- Tasks that **must not overlap**
- Simple operational visibility (status, runtime, errors)
- Explicit lifecycle management (start, stop, shutdown)

Typical examples:
- cache refreshers
- polling loops
- maintenance jobs
- lightweight ETL or indexing tasks

## What this library is *not* for

- Distributed job queues
- Cron replacement
- High-throughput task execution
- Exactly-once or persistent scheduling semantics

If you need persistence, distribution, or external coordination, this is not the right tool.

## Design highlights

- **One worker = one single-thread executor**
- **Non-blocking scheduling** scheduler threads never execute user work
- **Explicit timeouts** per run
- **No overlapping executions**
- **Graceful shutdown by default**, with force-stop available
- **Observable state** via an admin/introspection API
- Built on top of [`chime`](https://github.com/jarohen/chime)

The API favors clarity and predictability over abstractions.

## Schedule drift and jitter

When jitter is enabled, `agentti` intentionally applies it *cumulatively* between runs.
Each execution time is computed relative to the previous execution, not to a fixed wall clock. 
This introduces a bounded random walk, so scheduled times gradually drift.
This behavior helps avoid synchronized “thundering herd” effects across workers and across processes, and favors de-correlation over strict calendar alignment.

If you require a predictable, non-drifting schedule, you can bypass this behavior by using `chime/periodic-seq` directly and wrapping it with `agentti.schedules/attach-next-eta!` to retain introspection support.
(This is also what `agentti` does with jitter disabled.)

## Basic usage

```clojure
(require '[agentti.lifecycle :as agentti])

(agentti/add-worker!
  {:worker-name :example
   :interval-ms 10_000
   :timeout-ms  2_000
   :body-fn     (fn []
                  (println "hello from background worker"))})

;; later…
(agentti/stop-worker! :example)
```

## Introspection / admin

```clj
(require '[agentti.admin :as admin])

(admin/list-workers)
;; =>
;; [{:worker-name "example"
;;   :status :idle
;;   :num-runs 12
;;   :last-duration 87
;;   :next-run-eta "..."}]
```

This is intended for internal admin endpoints or dashboards.

## Notes on scheduling and dropped runs

Worker callbacks submitted by `agentti` are **non-blocking** with respect to the scheduler.
Each tick submits work to a dedicated executor and returns immediately, allowing subsequent ticks to arrive on time.

If a tick occurs while a previous run is still in flight, it is intentionally dropped and recorded in the worker’s metrics.
This provides accurate visibility into overload or misconfigured intervals, without allowing overlapping executions.

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
