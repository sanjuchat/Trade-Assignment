# Trade Store — Mermaid Diagrams

This document captures the high-level system architecture and low-level class diagram from the supplied screenshots.

## 1. High-Level System Architecture

```mermaid
flowchart LR
    Producers["Trade Producers<br/>(any transmission)"]

    subgraph Kafka["Apache Kafka"]
        Topic["Topic: trades.inbound"]
        DLT["Topic: trades.dlt"]
    end

    subgraph Service["Trade Store Service (Spring Boot 3.x / Java 17)"]
        Listener["TradeKafkaListener"]
        Svc["TradeService"]
        Validator["TradeValidator"]
        Scheduler["TradeExpiryScheduler"]
        JpaRepo["TradeRepository (JPA)"]
        MongoRepo["TradeAuditRepository (Mongo)"]
    end

    Postgres[("PostgreSQL<br/>current state")]
    Mongo[("MongoDB<br/>audit / event log")]

    Producers -->|publish, keyed by tradeId| Topic
    Topic -->|consume in order per tradeId| Listener
    Listener --> Svc
    Svc --> Validator
    Svc -->|save / update current state| JpaRepo
    Svc -->|append to history / audit log| MongoRepo
    Listener -->|on failure| DLT
    JpaRepo --> Postgres
    MongoRepo --> Mongo
    Scheduler -->|mark expired where maturity < today| JpaRepo
    Scheduler -->|append EXPIRED events| MongoRepo
```

## 2. Low-Level Design — Class Diagram

```mermaid
classDiagram
    class TradeKafkaListener {
        reads trades from Kafka
    }

    class TradeService {
        orchestrates: load, validate, save, audit
    }

    class TradeValidator {
        applies rules R1, R3, R5
    }

    class TradeExpiryScheduler {
        marks matured trades expired (R4)
    }

    class TradeRepository {
        current state (PostgreSQL)
    }

    class TradeAuditRepository {
        history / audit log (MongoDB)
    }

    TradeKafkaListener --> TradeService
    TradeService --> TradeValidator
    TradeService --> TradeRepository
    TradeService --> TradeAuditRepository
    TradeExpiryScheduler --> TradeRepository
    TradeExpiryScheduler --> TradeAuditRepository
```

## Notes

- **R1 — Lower version:** reject an incoming trade when `incoming.version < stored.version`.
- **R2 — Same version:** allow overwrite when `incoming.version == stored.version`.
- **R3 — Past maturity:** reject an incoming trade whose `maturityDate < today`.
- **R4 — Expiry job:** scheduled processing marks trades expired when their maturity date has passed and appends an `EXPIRED` audit event.
- **R5 — Expired trade is terminal:** reject updates to a trade already marked `expired = 'Y'`; do not allow reactivation.
- Kafka messages are keyed by `tradeId` so records for the same trade can be processed in order by a partition.
- PostgreSQL stores the current trade state; MongoDB stores append-only audit/event history.
