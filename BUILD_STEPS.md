# Build order and per-step verification

Run `.\mvnw verify` (Windows) / `./mvnw verify` after each step. Step 4 onward needs internet on the first
run (embedded MongoDB binary download).

| Step | Files |
|------|-------|
| 0 | pom.xml, mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties, .gitignore, TradeStoreApplication.java, config/ClockConfig.java, application.yml, application-test.yml |
| 1 | domain/Trade, EventType, TradeAuditEvent; dto/TradeMessage; exception/*; test: domain/TradeTest |
| 2 | service/TradeValidator; test: service/TradeValidatorTest |
| 3 | repository/TradeRepository + TradeAuditRepository (interfaces, needed to compile); service/TradeService; test: service/TradeServiceTest |
| 4 | tests: repository/TradeRepositoryTest, TradeAuditRepositoryTest |
| 5 | messaging/KafkaConfig, TradeKafkaListener; test: messaging/TradeKafkaListenerIT |
| 6 | web/TradeController, ApiError, GlobalExceptionHandler; test: web/TradeControllerTest |
| 7 | service/TradeExpiryScheduler; test: service/TradeExpirySchedulerTest |
| 8 | test: TradeStoreRegressionIT |
| 9 | .github/workflows/ci.yml, .gitlab-ci.yml, Jenkinsfile |
| 10 | docs/diagrams/mermaid-diagrams.md |
| 11 | application-local.yml |

Local demo:
`./mvnw spring-boot:run -Dspring-boot.run.profiles=local -Dspring-boot.run.useTestClasspath=true`
