

## Conventions and rule reference

- **Stack:** Java 17, Spring Boot 3.x, Maven via Maven Wrapper. Docker is not assumed; builds run through the wrapper and in CI.
- **Approach:** strict TDD—write the failing JUnit 5 test first, then implement the code.
- **Base package:** `com.example.tradestore`.
- **Business key:** `tradeId` (one current SQL row per trade). Business `version` is a field; optimistic locking uses a separate `@Version` field named `lockVersion`.
- **Streaming:** Kafka topic `trades.inbound`, keyed by `tradeId`; dead-letter topic `trades.dlt`.
- **SQL:** PostgreSQL in runtime; H2 in PostgreSQL-compatibility mode for tests.
- **NoSQL:** MongoDB for the audit log; embedded/Flapdoodle MongoDB for tests.
- **Validation rules R1–R5 live in the domain layer only.**

Rule reference (use this consistently in service and validation prompts):

- **R1:** `incoming.version < stored.version` → reject with `LowerVersionException`.
- **R2:** `incoming.version == stored.version` → overwrite the existing record.
- **R3:** `maturityDate < today` → reject with `MaturityDateException`.
- **R4:** once `maturityDate` has passed, the scheduled job marks `expired = 'Y'`.
- **R5:** if an existing trade has `expired = 'Y'`, reject any update with `TradeExpiredException`; expiry is terminal and trades cannot be reactivated.

---

## Prompt 0 — Project scaffolding & build

Set up a new Java 17 / Spring Boot 3.x project named `trade-store`, using Maven with the Maven Wrapper. Commit the wrapper files: `.mvn/wrapper/maven-wrapper.properties`, `mvnw`, and `mvnw.cmd`. Use base package `com.example.tradestore`.

**Pin these versions exactly** (known-compatible; do not use `latest`):

- Java 17 (tested with 17.0.12), configured with `<java.version>17</java.version>`.
- Spring Boot parent `3.2.5` (`spring-boot-starter-parent`); let its BOM manage versions for Spring, Kafka, JPA, MongoDB, and JUnit 5 dependencies.
- Flapdoodle embedded Mongo for Spring Boot 3.x: `de.flapdoodle.embed:de.flapdoodle.embed.mongo.spring3x:4.11.0` (test scope).
- OWASP `dependency-check-maven` plugin `9.2.0`.
- `jqwik` `1.8.4` (optional, test scope).

**Produce:**

- `pom.xml` with `spring-boot-starter`, `spring-kafka`, `spring-boot-starter-data-jpa`, `spring-boot-starter-data-mongodb`, `spring-boot-starter-validation`, `spring-boot-starter-web`, and PostgreSQL (runtime).
- Test dependencies: `spring-boot-starter-test` (JUnit 5 and Mockito), `spring-kafka-test`, `com.h2database:h2`, the Flapdoodle artifact above, and optionally `net.jqwik:jqwik`.
- Use `jakarta.*` imports throughout (Spring Boot 3 uses Jakarta EE 9+; do not use `javax.*`).
- Configure `spring-boot-maven-plugin`. Declare OWASP `dependency-check-maven`, but bind it only to a Maven `ci` profile—not the default `verify` lifecycle—so ordinary local verification does not trigger the slow NVD download.
- Add a Maven profile `ci` that activates the OWASP plugin's `check` goal with `failBuildOnCVSS` set to `7` (fail on High/Critical findings).
- `src/main/java/com/example/tradestore/TradeStoreApplication.java` with `@SpringBootApplication` and `@EnableScheduling`.
- `src/main/resources/application.yml` with runtime datasource (PostgreSQL), Mongo URI, Kafka bootstrap/consumer-group configuration and topic `trades.inbound`, plus expiry cron property `trade.expiry.cron` (default `0 5 0 * * *`).
- `src/test/resources/application-test.yml` using H2 in PostgreSQL-compatible mode and embedded Mongo; tests must not require a running Kafka, PostgreSQL, or MongoDB service.
- `.gitignore` for Java/Maven, ignoring `/target`, IDE files, and the OWASP data-cache directory.

**Definition of Done:** `./mvnw -q -DskipTests package` compiles an empty app; `./mvnw -q test` runs successfully with zero tests; `./mvnw -Pci -DskipTests verify` triggers the dependency-check plugin.

---

## Prompt 1 — Domain model (entity, document, DTO, enum, exceptions)

In package `com.example.tradestore`, create the domain types. JPA is not required for plain data holders, but add a small test asserting field mapping/defaults.

**Produce:**

- `domain/Trade.java` — JPA `@Entity`, table `trade`; fields: `tradeId` (`@Id`, `String`), `version` (`int`, business version), `counterPartyId`, `bookId`, `maturityDate` (`LocalDate`), `createdDate` (`LocalDate`), `expired` (`char`, default `'N'`), and `@Version private long lockVersion` (optimistic lock, distinct from business `version`).
- `domain/EventType.java` — enum `{ ACCEPTED, REJECTED, EXPIRED }`.
- `domain/TradeAuditEvent.java` — Mongo `@Document("trade_audit")`; fields: `id` (`String`), `tradeId`, `version`, `counterPartyId`, `bookId`, `maturityDate`, `createdDate`, `eventType` (`EventType`), `rejectionReason` (nullable), and `receivedAt` (`Instant`). Index `tradeId` and `receivedAt`.
- `dto/TradeMessage.java` — request DTO mirroring the trade business fields, with bean validation such as `@NotBlank tradeId`, `@Min(1) version`, and `@NotNull maturityDate` (plus validation for other required fields).
- `exception/LowerVersionException.java`, `exception/MaturityDateException.java`, and `exception/TradeExpiredException.java` — all extend `RuntimeException` and have a message constructor.

**Definition of Done:** the project compiles; `TradeTest` confirms `expired` defaults to `'N'` and field mapping/defaults are correct.

---

## Prompt 2 — Validation rules (TDD, the core)

Using strict TDD, implement `service/TradeValidator.java` with method:

`void validate(TradeMessage incoming, Optional<Trade> existing)`

**FIRST** write `TradeValidatorTest` (JUnit 5), with each case as its own test:

1. `maturityDate` before today → throws `MaturityDateException` (R3).
2. Existing trade is present and `existing.expired == 'Y'` → throws `TradeExpiredException` (R5); expiry is terminal and no update/reactivation is allowed.
3. Existing trade is present and `incoming.version < existing.version` → throws `LowerVersionException` (R1).
4. Existing trade is present and `incoming.version == existing.version` → returns normally (R2, overwrite allowed).
5. Existing trade is present and `incoming.version > existing.version` → returns normally.
6. Existing trade is empty and `maturityDate > today` → returns normally (first version).

Use a fixed/injected `Clock` or pass `today` so tests are deterministic.

**THEN** implement the validator to make all tests pass. Evaluation order is **R3 (past maturity) → R5 (already-expired trade) → R1 (lower version)**, before accepting an update.

**Definition of Done:** all `TradeValidatorTest` cases are green; validator contains no persistence or transport code.

---

## Prompt 3 — Trade service orchestration (TDD, mocked repositories)

Using TDD, implement `service/TradeService.java` with:

- `void handle(TradeMessage message)`
- Constructor-injected dependencies: `TradeRepository`, `TradeAuditRepository`, `TradeValidator`, and a `Clock`.

**FIRST** write `TradeServiceTest` with Mockito mocks:

- **Accepted path:** valid message → calls `validator.validate`, saves a `Trade` (`expired = 'N'`, `createdDate = today`) via `TradeRepository.save`, and appends an `ACCEPTED` `TradeAuditEvent`.
- **Rejected path (R1, R3, and R5 separately):** validator throws → service appends a `REJECTED` audit event with the reason, does **not** call `TradeRepository.save`, and rethrows the exception. For R5, use an existing trade with `expired = 'Y'` and an incoming higher version to prove that expiry is terminal.
- **Same-version path (R2):** existing v2, incoming v2 → overwrites (save called).

**THEN** implement `TradeService` to satisfy the tests, following the flow:

`load existing → validate → persist current state → append audit event`.

**Definition of Done:** all `TradeServiceTest` cases are green; no Kafka/web code here.

---

## Prompt 4 — Repositories & slice tests

Create Spring Data repositories and their slice tests.

Produce:

- `repository/TradeRepository.java` extends `JpaRepository<Trade, String>` with `List<Trade> findByMaturityDateBeforeAndExpired(LocalDate today, char expired)`. Optionally add an atomic conditional update using `@Modifying` and `@Query` (for example, an update constrained by `tradeId` and version).
- `repository/TradeAuditRepository.java` extends `MongoRepository<TradeAuditEvent, String>` with `List<TradeAuditEvent> findByTradeId(String tradeId)`.

**Tests:**

- `@DataJpaTest` (H2), `TradeRepositoryTest`: save/upsert by `tradeId`; verify `findByMaturityDateBeforeAndExpired` returns only matured, non-expired rows; verify optimistic-lock version bump on update.
- `@DataMongoTest` (embedded Mongo), `TradeAuditRepositoryTest`: persist events, query by `tradeId`, and confirm append-only behaviour.

**Definition of Done:** both slice tests are green without Docker.

---

## Prompt 5 — Kafka ingestion + DLT (TDD with embedded Kafka)

Implement `messaging/TradeKafkaListener.java` (`@KafkaListener` on `trades.inbound`, with the consumer group from configuration). It deserializes a `TradeMessage` and delegates to `TradeService.handle`.

On validation exceptions, use Spring Kafka's `DefaultErrorHandler` and `DeadLetterPublishingRecoverer` to route the record to `trades.dlt` after bounded retries. Configure producers to key messages by `tradeId`; document this in `messaging/KafkaConfig.java`.

**FIRST** write an `@EmbeddedKafka` test, `TradeKafkaListenerIT`:

- **Happy path:** publish a valid `TradeMessage` → service persists and audits.
- **Failure path:** publish a message that violates R1/R3 → record ends up on `trades.dlt`; current state remains unchanged.

**THEN** implement the listener and Kafka configuration to pass.

**Definition of Done:** embedded-Kafka integration test is green; no external broker is needed.

---

## Prompt 6 — REST transport + global error handling (TDD, web slice)

Implement `web/TradeController.java`:

- `POST /api/trades` → validates the body, calls `TradeService.handle`, and returns `202`.
- `GET /api/trades/{tradeId}` → returns the current trade or `404`.
- `GET /api/trades` → lists current trades.

Implement `web/GlobalExceptionHandler.java` (`@RestControllerAdvice`) mapping `LowerVersionException`, `MaturityDateException`, and `TradeExpiredException` to HTTP `400` with a structured error body. Bean-validation errors also return `400`. Keep `TradeExpiredException` at `400` for consistency with the other business-rule rejections (HTTP `409 Conflict` is an alternative only if the API contract is deliberately changed).

**FIRST** write `@WebMvcTest TradeControllerTest` (mock `TradeService`) covering each endpoint and all three business exception-to-400 mappings (`LowerVersionException`, `MaturityDateException`, and `TradeExpiredException`).

**THEN** implement the controller and advice.

**Definition of Done:** web-slice tests are green; the error contract is documented in the tests.

---

## Prompt 7 — Scheduled expiry (TDD)

Implement `service/TradeExpiryScheduler.java` with:

- `@Scheduled(cron = "${trade.expiry.cron}")` on `void expireMaturedTrades()`.
- Load records using `findByMaturityDateBeforeAndExpired(today, 'N')`.
- Set each matured trade's `expired` flag to `'Y'`, save it, and append an `EXPIRED` audit event per trade.
- Use an injected `Clock`.

**FIRST** write `TradeExpirySchedulerTest` with mocked repositories:

- Matured, non-expired trades → each is flipped to `'Y'` and an `EXPIRED` audit event is written.
- **Idempotency:** a second run with no matured rows does nothing.

**THEN** implement the scheduler to pass.

**Definition of Done:** scheduler tests are green; the job is idempotent.

---

## Prompt 8 — End-to-end regression test (the CI gate suite)

Write `@SpringBootTest TradeStoreRegressionIT`, wiring the real service with H2, embedded Mongo, and `@EmbeddedKafka`, and exercise the brief's sample data in order:

1. T1 v1 (maturity in past) → `REJECTED` (R3).
2. T2 v2 (future maturity) → `ACCEPTED`.
3. T2 v1 → `REJECTED` (R1, lower version).
4. T3 v3 (maturity in past) → `REJECTED` (R3).

Assert final SQL state and the audit-log event sequence. Then run the expiry job against a soon-matured trade and assert it flips to `'Y'`. Finally, submit a new, higher-version message for that now-expired trade and assert it is `REJECTED` (R5, `TradeExpiredException`); expiry is terminal and the trade must not be reactivated.

**Definition of Done:** `./mvnw verify` runs this as part of the regression suite and it is green.

---

## Prompt 9 — CI/CD pipeline (GitHub Actions + GitLab CI + Jenkins fallback)

Create `.github/workflows/ci.yml`:

- Triggers: `push` and `pull_request`.
- Steps: checkout → `actions/setup-java` (Temurin 17) → cache Maven and the OWASP NVD data → `./mvnw -B -Pci clean verify` (compiles, runs the full JUnit regression suite, and runs the OWASP scan).
- A failing test or a CVSS ≥ 7 critical/blocker finding fails the build.
- Upload Surefire and Dependency-Check HTML reports as artifacts.
- The `ci` profile enables the OWASP gate. Locally, plain `./mvnw verify` runs tests only (no slow NVD download), so a reviewer who clones the repository is not blocked by the scan.

Also provide `.gitlab-ci.yml` (the repository may live on GitLab): one job on an `eclipse-temurin:17` image running `./mvnw -B -Pci clean verify`, caching `~/.m2` and the OWASP data directory, and publishing the Surefire and Dependency-Check reports as artifacts.

Provide a `Jenkinsfile` with equivalent stages (`Checkout → Build → Test → Dependency-Check → Publish`) as a documented alternative.

**Definition of Done:** GitHub Actions and GitLab CI files are valid YAML; the Dependency-Check gate (via the `ci` profile) fails on High/Critical findings.

---

## Prompt 10 — Diagrams & docs sync

Ensure `docs/diagrams/mermaid-diagrams.md` contains the three key diagrams:

1. High-level system architecture.
2. Class diagram.
3. Trade-ingestion sequence (accept/reject).

Annotate the Kafka edge as **keyed by `tradeId`** and show the `Trade` entity's `@Version lockVersion` field.

**Definition of Done:** diagrams render and stay consistent with the ADRs.

---

## Prompt 11 — `local` demo profile (run the app with no external infrastructure)

Add a Spring profile named `local` so the full application can run without a real Kafka broker, PostgreSQL, or MongoDB installed—useful for a live demo.

Produce `src/main/resources/application-local.yml` that points the running application at the same embedded substitutes used by tests:

- **SQL:** H2 in-memory (PostgreSQL-compatibility mode), auto-created schema.
- **NoSQL:** embedded MongoDB via Flapdoodle (the Spring Boot 3.x integration), so the app starts its own in-process Mongo.
- **Streaming:** either embedded/single-node Kafka for `local`, or, if that proves fiddly to run outside a test, disable the Kafka listener under the `local` profile (annotate it so it is not loaded) and rely on the REST endpoint for the demo. Both REST and Kafka delegate to the same `TradeService`, so all four rules are still exercised via REST.

Document the run command:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Then demonstrate the rules live with REST calls:

- POST a valid future-maturity trade (T1 v1) → `202 Accepted`.
- POST the same trade ID with a lower version → `400` (`LowerVersionException`).
- POST a past-maturity trade → `400` (`MaturityDateException`).
- GET `/api/trades/{tradeId}` → shows the stored current state.

**Definition of Done:** the run command starts the app with no external services installed, and the four REST calls above demonstrate rules R1–R3 (R4 via the scheduler or a short cron for the demo).

---

## Suggested execution order

`0 → 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11`

Prompts 2–7 are strict TDD (test first). Prompt 8 is the regression gate the CI pipeline (Prompt 9) enforces. Prompt 11 is optional but recommended: it lets the app run on embedded H2 + Mongo (and embedded or disabled Kafka) with no external infrastructure, which is handy for a live demo. A developer handed these in order, with the design document as reference, can build the application end to end.
