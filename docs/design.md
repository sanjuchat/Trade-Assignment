
## 1. Architecture decisions

### AD-1: Polyglot persistence — PostgreSQL current state + MongoDB audit log

**Decision:** Keep the latest state of each trade in PostgreSQL (one row per trade). Keep a full history of every received message—including accepted and rejected outcomes—in MongoDB as an append-only audit log. Use two stores rather than one.

**Problem addressed:** The system could keep everything in one SQL database with an extra audit table, but the design separates the high-volume history writes from the operational current-state table.

**Why this works:**
- Heavy history writes do not slow down the live table; the workloads have different characteristics.
- It gives each store a clear role: PostgreSQL answers “what is the current state?” while MongoDB records “what happened, and when?”
- Rejections can be audited with a reason without changing the current trade state.

**Trade-offs:** Running two databases adds operational overhead. A single PostgreSQL database with a separate audit table would be simpler to operate, but the design accepts the extra moving parts for the separation of responsibilities.

### AD-2: Ingestion — Apache Kafka via Spring Kafka

**Decision:** Trades arrive on a Kafka topic shown in the notes as `trades.inbound` and are consumed by a Spring Kafka listener.

**Why this works:** Kafka can accept a high volume of trades from different transmission methods, separates senders from storage, absorbs bursts, and allows messages to be replayed when processing fails. A REST endpoint could be added without changing the core processing service.

**Trade-offs:** Kafka requires brokers, topics, offsets, and operational support. 

### AD-3: Concurrency and ordering — partition by `tradeId` + optimistic locking

**Decision:** Key Kafka messages by `tradeId`, so all messages for the same trade are assigned to the same partition and processed in order by a single consumer. Add a JPA optimistic-lock/version check on the trade row as a safety net.

**Problem addressed:** Checking whether an incoming version is newer than the stored version is a read-then-write operation. If two updates race, both can read the same old value and pass the check; a later write could overwrite a newer value.

**Why this works:**
- Keying by `tradeId` preserves per-trade ordering in the normal Kafka processing path.
- Optimistic locking protects against races from other writers or processing paths.
- If a write loses the version race, re-read the current value and re-apply the validation rule. A stale version must not overwrite a newer version.

**Trade-offs:** Only one message for a given trade is processed at a time, so an unusually busy trade can become a throughput hotspot. Different trades can still be processed in parallel. 

### AD-4: Consistency across the two stores — safe-to-repeat writes

**Decision:** Do not attempt one large transaction spanning both databases. Make each write safe to repeat: processing the same message again should converge on the same current state and audit outcome. Retry failures; send a message to a dead-letter topic for inspection when retries are exhausted.

**Problem addressed:** PostgreSQL and MongoDB cannot share a single local transaction. A message can be saved to one store and fail on the other, or Kafka can deliver the message again.

**Worked example — partial failure:**
1. Trade version 2 arrives.
2. PostgreSQL's current state is updated to version 2.
3. The application crashes before the MongoDB history entry is written.
4. Because the message was not acknowledged, Kafka redelivers it.
5. On reprocessing, PostgreSQL's update to version 2 simply rewrites the same values, while MongoDB appends the missing history entry.
6. The stores converge on the correct current state and history.

**Trade-offs:** The stores can be briefly out of step during a retry, and the history log can contain a duplicate if an append succeeded but its acknowledgement was lost. This is an eventual-consistency design, not a distributed atomic transaction. Use a stable message/event identifier or unique key if duplicate audit entries must be prevented.

### AD-5: SQL keeps current state only (one row per trade)

**Decision:** The PostgreSQL table stores one row per trade, keyed by `tradeId`, and holds only the latest accepted version. It does not store a row for every version. Full version history remains in MongoDB.

**Alternative considered:** Store every version in SQL using a composite key such as (`tradeId`, `version`), giving full history in the relational store. The design deliberately does not choose this.

**Why this works:** The common operational query—“what is the current state of this trade?”—is a single lookup by ID, and the operational table remains small. The complete version history is already preserved in MongoDB, so duplicating it in SQL is unnecessary.

**Trade-offs:** SQL alone cannot answer “show me every past version”; that query must go to MongoDB. This is accepted because the operational path needs current state, not the full history.

### AD-6: Validation placement — domain/service layer

**Decision:** Keep all four trade rules in one plain service/domain layer (for example, `TradeService` / `TradeValidator`), not in the Kafka listener or REST controller.

**Why this works:** The same rules apply whether a trade arrives via Kafka or REST. Keeping validation in one service avoids duplicated rules and keeps transport adapters thin.

**Trade-offs:** None


### AD-7: Expiry handling — scheduled job

**Decision:** A scheduled job runs on a timer (for example, daily just after midnight) and marks every trade whose maturity date has passed as expired.

**Why this works:** Expiry is driven by time passing, not by a new message arriving. A scheduled sweep handles trades that receive no further updates. The job can safely run repeatedly if the expiry update is idempotent.

**Trade-offs:** A trade is marked expired on the next run, not necessarily at the exact instant it matures. That delay is accepted; the schedule can be configured to run more frequently if required.
** Expiry is terminal** once trade is expired it is closed. A later message for that trade id is rejected. expired trade will never be reactivate again.

### AD-8: CI/CD — GitHub Actions with an OWASP Dependency-Check gate

**Decision:** A GitHub Actions pipeline builds the project, runs the full test suite, and runs OWASP Dependency-Check. The build fails if a dependency has a critical or blocker vulnerability. GitLab CI and Jenkins are documented alternatives.

**Why this works:** The pipeline provides automated regression testing and an open-source dependency vulnerability scan without requiring the team to manage its own CI server when using GitHub Actions.

**Trade-offs:** The first vulnerability scan can be slow because it downloads a vulnerability database. Cache the database between runs where possible. GitLab CI or Jenkins can be used if they are the organisation's standard platforms.

## 2. Technology and tooling choices

These are supporting choices rather than architectural decisions:

- **Language/framework:** Java 17 and Spring Boot 3.x. Spring Boot provides integrated support for Kafka, JPA/PostgreSQL, MongoDB, scheduling, and testing. Java 17 is the baseline noted for this Spring Boot generation.
- **Alternative stack:** Python with FastAPI/REST was considered viable, but the notes describe it as less cohesive for this particular combination of components.
- **Build:** Maven with the committed Maven Wrapper (`mvnw`). The wrapper pins the Maven version for developers and CI, so a separate local Maven installation is not required.
- **SQL test datastore:** H2.
- **MongoDB test datastore:** An embedded/in-memory MongoDB option.
- **Kafka test broker:** An in-memory/embedded Kafka broker for listener/integration tests where practical.
- **Test goal:** Keep the test loop fast and Docker-free; the notes indicate that H2 runs in PostgreSQL-compatibility mode.


## 3. Error handling

The guiding principle is **nothing is ever silently dropped**: a trade is stored, retried, explicitly rejected, or parked on a dead-letter topic.

- **Rejected trade (lower version or past maturity):** The rule fails. Write a `REJECTED` audit entry with the reason, leave the current state untouched, and route the message to a dead-letter topic for inspection.
- **Datastore temporarily down:** Retry the message. If it continues to fail, route it to the dead-letter topic so processing can continue without losing visibility of the failure.
- **One store saves but the other fails:** Because writes are safe to repeat (AD-4), reprocess the message. The message is safely replayed and the stores converge to the correct state; if retries are exhausted, dead-letter it.

The audit entry should preserve enough context to diagnose the outcome, including trade ID, message/version identifier, reason, and processing timestamp.

## 4. Testing strategy — test-first (TDD)

Build test-first: write the failing test, then implement the rule. Tests are organised at three levels:

1. **Unit tests:** Test the four trade rules and service logic, with database and infrastructure dependencies mocked.
2. **Slice/integration tests:** Test SQL and MongoDB repositories against in-memory equivalents (H2 and embedded MongoDB), and test the Kafka listener against an in-memory broker.
3. **End-to-end regression:** Run the full flow against the sample trades described in the requirements, asserting that each trade is processed correctly. The screenshot's sample-trade notation is not fully legible, so confirm the exact fixtures in the build steps.

The stated goal is for tests to run with only a JDK installed, making the build reproducible on a developer machine or CI. Detailed test cases are part of the build steps.

## 5. CI/CD and vulnerability scanning

A GitHub Actions pipeline (with GitLab CI or Jenkins documented as alternatives) performs these checks on each push/build:

1. **Build and tests:** Run the full test suite; any failure fails the build.
2. **Scan dependencies:** Run OWASP Dependency-Check; fail the build if an open-source dependency has a critical or blocker vulnerability.
3. **Publish reports:** Make test and scan results available as build artefacts/reports.

The security scan runs in the pipeline rather than on every local build, so day-to-day builds stay fast while changes are still gated. The Maven Wrapper keeps the build self-contained; a local Maven installation is not required.

