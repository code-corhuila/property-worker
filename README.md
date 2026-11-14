# property-worker

> The scheduled jobs of the platform

Part of the **PMS Property** distributed system — team `pms-property`, Grupo 2.
Governance and documentation live in [`property-docs`](https://github.com/code-corhuila/property-docs).

Java 21 and Spring Boot 3.5 in three Maven modules (ADR-011, Annex D). It receives no
requests and owns no schema: it acts on the other services only through their public APIs.

## The job `expire-stale-reservations` (ADR-014, HU-BOOK-005)

Every Reserva left `PENDIENTE` longer than `PENDING_TTL` (15 minutes) is expired, so a Saga
that did not finish never traps the dates. On every run the job:

```
GET  /api/v1/reservas?estado=PENDIENTE&creadaAntesDe=<now - PENDING_TTL>&limit=<BATCH_SIZE>
POST /api/v1/reservas/{id}/expirar
```

- **Booking owns the transition.** It writes `EXPIRADA` (`TIMEOUT_PAGO_EXCEDIDO`) and the
  event `ReservaExpirada` to its outbox in one transaction; the worker only triggers it.
- **Safe to run twice.** An expired Reserva answers `200` again. One that was confirmed or
  cancelled meanwhile answers `422`: counted, never retried.
- **Bounded runs.** At most `BATCH_SIZE` reservations per run (1 to 100: the largest page of
  E-02) and never past `RUN_TIMEOUT`; the rest waits for the next run. A failure on one
  Reserva does not stop the batch.

```
worker-core/       the job and the port to Booking — NO Spring dependency
worker-adapters/   the scheduler (one X-Correlation-Id per run) and the HTTP client of Booking
worker-app/        the composition root: every limit, with its default
deploy/            Dockerfile and this service's compose file (no port: it receives no requests)
```

## Run

```bash
mvn -B verify
SERVICE_TOKEN=<svc-worker token> BOOKING_URL=http://localhost:3001 java -jar worker-app/target/worker-app-0.1.0.jar
```

Without `SERVICE_TOKEN` it does not start. In the platform the token comes from
`WORKER_SERVICE_TOKEN` (`property-infra-postgres`; in develop, `scripts/dev-keys.sh`).

| Variable | Default | What it bounds |
|---|---|---|
| `PENDING_TTL` | `15m` | How long a Reserva may stay `PENDIENTE` |
| `EXPIRE_EVERY` | `60s` | The pause between the end of a run and the next one |
| `BATCH_SIZE` | `100` | Reservations per run, 1 to 100 |
| `RUN_TIMEOUT` | `50s` | The length of a run |
| `HTTP_TIMEOUT`, `HTTP_ATTEMPTS`, `HTTP_BACKOFF` | `5s`, `3`, `200ms` | Each call to Booking; only network errors, `429` and `5xx` are retried, with exponential backoff and full jitter |

Every call carries the run's `X-Correlation-Id`, and every log line of the run writes it.

## Branching

Three permanent branches. **None of them accepts a direct commit** — you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  qa/...
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another: `merge develop -> qa` and `merge qa -> main` do not exist in this model.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/branching-policy.md` in `property-docs`.
