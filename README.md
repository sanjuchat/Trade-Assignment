# Trade Store

A trade store that accepts trades from any transport (Kafka or REST), enforces version and maturity rules,
keeps the **current state** in SQL and a full **audit history** in NoSQL.

Built with Java 17, Spring Boot 3.2, Kafka, PostgreSQL (H2 in tests), MongoDB (embedded in tests), JUnit 5 (TDD),
and a CI pipeline with an OWASP dependency-vulnerability gate.

> **AI disclosure:** the architecture decisions (`docs/design.md`) are my own. The implementation was generated with
> AI from the step-by-step prompts in `docs/prompts.md`, then built and verified locally by me.

## Business rules

| Rule | Behaviour | Where it is tested |
|------|-----------|--------------------|
| R1 | Lower version than stored is rejected (`LowerVersionException`) | `TradeValidatorTest`, `TradeServiceTest`, `TradeStoreRegressionIT` |
| R2 | Same version overwrites the existing record | `TradeValidatorTest`, `TradeServiceTest`, `TradeStoreRegressionIT` |
| R3 | Maturity date before today is rejected (`MaturityDateException`) | `TradeValidatorTest`, `TradeServiceTest`, `TradeStoreRegressionIT` |
| R4 | Scheduled job marks matured trades `expired = 'Y'` | `TradeExpirySchedulerTest`, `TradeStoreRegressionIT` |
| R5 | Expired is terminal: any later update is rejected (`TradeExpiredException`) | `TradeValidatorTest`, `TradeServiceTest`, `TradeStoreRegressionIT` |

Rules R1-R3 and R5 live only in `TradeValidator` (domain layer), so Kafka and REST behave identically.

## Architecture (summary)

| Decision | Choice |
|----------|--------|
| AD-1 | PostgreSQL = current state (1 row per trade); MongoDB = append-only audit log |
| AD-2 | Kafka ingestion (`trades.inbound`), dead-letter topic `trades.dlt` |
| AD-3 | Kafka key = `tradeId` for per-trade ordering, plus JPA `@Version` optimistic lock as a safety net |
| AD-4 | No cross-database transaction; writes are safe to repeat, retries + DLT |
| AD-5 | SQL keeps the latest version only; history lives in MongoDB |
| AD-6 | Validation in the service/domain layer, not in transport adapters |
| AD-7 | Scheduled expiry job (`trade.expiry.cron`) |
| AD-8 | CI with regression tests and an OWASP Dependency-Check gate (CVSS >= 7 fails) |

Full reasoning and trade-offs: [`docs/design.md`](docs/design.md). Diagrams: [`docs/diagrams/mermaid-diagrams.md`](docs/diagrams/mermaid-diagrams.md).

## Prerequisites

- JDK 17
- Internet on the first build (Maven, dependencies, and an embedded MongoDB binary are downloaded)
- No Docker, Kafka, PostgreSQL or MongoDB installation needed

## Build and test

```bash
./mvnw verify          # Windows PowerShell: .\mvnw verify
```

Runs 35 tests: unit, repository slice tests (H2, embedded Mongo), embedded-Kafka integration test and the
end-to-end regression test (`TradeStoreRegressionIT`).

The regression test replays the brief's sample scenario (T1 past maturity, T2 v2, T2 v1, T3 past maturity),
then expiry (R4) and terminal expiry (R5). It uses dates relative to today because the brief's literal 2014-2021
dates are now in the past.

With the OWASP vulnerability gate (slow on first run, downloads the NVD database):

```bash
./mvnw -Pci verify
```

## Run the demo (no infrastructure)

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.useTestClasspath=true
```

PowerShell: quote the options, e.g. `.\mvnw spring-boot:run "-Dspring-boot.run.profiles=local" "-Dspring-boot.run.useTestClasspath=true"`.

The `local` profile uses H2 and embedded MongoDB, disables the Kafka listener, and runs the expiry job every minute.

In a second terminal (PowerShell):

```powershell
$uri = "http://localhost:8080/api/trades"
$future = (Get-Date).AddYears(1).ToString("yyyy-MM-dd")

function Send($id, $ver, $mat) {
  $body = @{tradeId=$id; version=$ver; counterPartyId="CP-1"; bookId="B1"; maturityDate=$mat} | ConvertTo-Json
  try {
    $r = Invoke-WebRequest -UseBasicParsing -Method Post -Uri $uri -ContentType "application/json" -Body $body
    "$id v$ver -> $($r.StatusCode)"
  } catch {
    "$id v$ver -> $([int]$_.Exception.Response.StatusCode) $($_.ErrorDetails.Message)"
  }
}

Send "T1" 2 $future          # 202 accepted
Send "T1" 1 $future          # 400 LowerVersionException (R1)
Send "T1" 2 $future          # 202 same version overwrites (R2)
Send "T2" 1 "2020-05-20"     # 400 MaturityDateException (R3)
Invoke-RestMethod "$uri/T1"  # current state: version 2, expired N
(Invoke-RestMethod $uri).Count   # 1 (the rejected T2 was never stored)
```

On Linux/macOS, the same calls with curl:

```bash
curl -i -X POST localhost:8080/api/trades -H "Content-Type: application/json" \
  -d '{"tradeId":"T1","version":2,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2030-01-01"}'
```

R4 (expiry) and R5 (expired is terminal) depend on the date passing, so they are demonstrated by
`TradeStoreRegressionIT`, which moves the clock forward, rather than by this live demo.

## API

| Method | Path | Result |
|--------|------|--------|
| POST | `/api/trades` | `202` accepted, `400` on rule violation or invalid body |
| GET | `/api/trades/{tradeId}` | `200` current trade, `404` unknown |
| GET | `/api/trades` | `200` list of current trades |

Error body: `{"status":400,"error":"LowerVersionException","message":"...","timestamp":"..."}`

## Project layout

```
src/main/java/com/example/tradestore/
  domain/      Trade (JPA), TradeAuditEvent (Mongo), EventType
  dto/         TradeMessage (validated inbound record)
  service/     TradeValidator (R1-R3, R5), TradeService (orchestration), TradeExpiryScheduler (R4)
  repository/  TradeRepository (JPA), TradeAuditRepository (Mongo)
  messaging/   TradeKafkaListener, KafkaConfig (DLT, retry policy)
  web/         TradeController, GlobalExceptionHandler, ApiError
src/test/      unit, slice, embedded-Kafka and regression tests (TDD)
docs/          design.md (ADRs), prompts.md (AI prompts), diagrams, problem statement
.github/workflows/ci.yml   build + tests + OWASP gate (also .gitlab-ci.yml, Jenkinsfile)
```

## CI/CD

GitHub Actions (`.github/workflows/ci.yml`) runs `./mvnw -B -Pci clean verify` on every push and pull request:
full regression suite plus OWASP Dependency-Check. A failing test or any dependency with CVSS >= 7 fails the build.
Surefire and Dependency-Check reports are uploaded as artifacts. GitLab CI and Jenkins equivalents are included.

## Known limitations and next steps

### Accepted trade-offs (decided in the ADRs)

- **Two datastores (AD-1):** more operational overhead than one PostgreSQL database with an audit table, in exchange
  for separating high-volume history writes from the operational table.
- **Hot trade (AD-3):** one `tradeId` maps to one partition and one consumer, so a very busy trade is processed
  serially. Different trades still run in parallel.
- **Eventual consistency across stores (AD-4):** during a retry the two stores can briefly differ; they converge on
  redelivery.
- **Expiry is not instantaneous (AD-7):** a trade is marked expired on the next scheduled run, not at the exact
  moment it matures. The cron is configurable (`trade.expiry.cron`).

### Gaps I would close next

| Gap | Impact | Next step |
|-----|--------|-----------|
| Duplicate audit entry if an audit append succeeds but the offset is not committed (AD-4) | History can contain a duplicate; current state stays correct | Deterministic event id (topic-partition-offset) as the Mongo `_id`, upsert on replay |
| REST path has no automatic retry on an optimistic-lock conflict | Caller receives an error instead of a re-validated result | Map the conflict to `409` for caller retry, or route REST through Kafka so both transports share ordering and retry |
| Per-trade ordering depends on producers keying by `tradeId` (AD-3) | An unkeyed producer loses ordering; `@Version` still prevents stale overwrites, but retries increase | Publish the keying rule as part of the topic contract, or add an ingestion gateway that keys messages |
| Expiry job runs on every instance when scaled out (AD-7) | Result is correct because the job is idempotent, but the work is duplicated | Distributed scheduler lock (for example ShedLock) |
| OWASP scan is a non-blocking step until an NVD API key is configured (AD-8) | Without the key the NVD returns 403, so the gate is not enforced yet | Add `NVD_API_KEY` as a repository secret and remove `continue-on-error`. The gate is CVSS >= 7 |
| Not included: authentication, metrics/tracing, schema registry for message contracts | Outside the scope of this exercise | Spring Security (OAuth2/JWT), Micrometer + tracing, schema registry |
