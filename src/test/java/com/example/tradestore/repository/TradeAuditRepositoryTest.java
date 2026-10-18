package com.example.tradestore.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.tradestore.domain.EventType;
import com.example.tradestore.domain.TradeAuditEvent;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.test.context.ActiveProfiles;

@DataMongoTest
@ActiveProfiles("test")
class TradeAuditRepositoryTest {

    @Autowired TradeAuditRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private TradeAuditEvent event(String tradeId, int version, EventType type, String reason) {
        return new TradeAuditEvent(tradeId, version, "CP-1", "B1", LocalDate.of(2030, 1, 1),
                LocalDate.of(2026, 10, 17), type, reason, Instant.now());
    }

    @Test
    void persistsAndQueriesByTradeId() {
        repository.save(event("T1", 1, EventType.ACCEPTED, null));
        repository.save(event("T2", 1, EventType.ACCEPTED, null));

        assertThat(repository.findByTradeId("T1"))
                .hasSize(1)
                .first()
                .satisfies(e -> {
                    assertThat(e.getId()).isNotNull();
                    assertThat(e.getEventType()).isEqualTo(EventType.ACCEPTED);
                });
    }

    @Test
    void isAppendOnly_everyEventIsANewDocument() {
        repository.save(event("T1", 1, EventType.ACCEPTED, null));
        repository.save(event("T1", 1, EventType.REJECTED, "lower version"));
        repository.save(event("T1", 2, EventType.ACCEPTED, null));

        assertThat(repository.findByTradeId("T1"))
                .extracting(TradeAuditEvent::getEventType)
                .containsExactlyInAnyOrder(EventType.ACCEPTED, EventType.REJECTED, EventType.ACCEPTED);
        assertThat(repository.count()).isEqualTo(3);
    }
}
