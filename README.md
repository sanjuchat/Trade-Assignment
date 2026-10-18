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

```bash
# accepted (202)
curl -i -X POST localhost:8080/api/trades -H "Content-Type: application/json" \
  -d '{"tradeId":"T1","version":2,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2030-01-01"}'
# lower version -> 400 LowerVersionException
curl -i -X POST localhost:8080/api/trades -H "Content-Type: application/json" \
  -d '{"tradeId":"T1","version":1,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2030-01-01"}'
# past maturity -> 400 MaturityDateException
curl -i -X POST localhost:8080/api/trades -H "Content-Type: application/json" \
  -d '{"tradeId":"T2","version":1,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"2020-05-20"}'
# current state
curl localhost:8080/api/trades/T1
```

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

- **Audit duplicates:** if an audit append succeeds but the acknowledgement is lost, a retry can add a duplicate
  audit entry. Fix: stable event id with a unique index (see AD-4).
- **REST path concurrency:** an optimistic-lock conflict on REST surfaces as an error rather than an automatic
  re-read and re-validate; the Kafka path recovers through redelivery.
- **Producer keying:** per-trade ordering depends on producers keying by `tradeId`; the consumer cannot enforce it.
- **Hot trades:** a very busy `tradeId` is a single-partition throughput hotspot.
- **Scan threshold:** the CI gate fails at CVSS >= 7 (High and above), stricter than "critical/blocker" in the brief.
- **Not included:** authentication, metrics/tracing, schema-registry-based message contracts.
