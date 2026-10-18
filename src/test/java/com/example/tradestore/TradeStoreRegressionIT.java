package com.example.tradestore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.example.tradestore.domain.EventType;
import com.example.tradestore.domain.Trade;
import com.example.tradestore.domain.TradeAuditEvent;
import com.example.tradestore.dto.TradeMessage;
import com.example.tradestore.exception.LowerVersionException;
import com.example.tradestore.exception.MaturityDateException;
import com.example.tradestore.exception.TradeExpiredException;
import com.example.tradestore.repository.TradeAuditRepository;
import com.example.tradestore.repository.TradeRepository;
import com.example.tradestore.service.TradeExpiryScheduler;
import com.example.tradestore.service.TradeService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

/**
 * CI gate. Replays the brief's sample trades using dates relative to "today"
 * (the brief's literal 2020/2021 dates are all in the past now).
 */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"trades.inbound", "trades.dlt"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class TradeStoreRegressionIT {

    @Autowired TradeService tradeService;
    @Autowired TradeRepository tradeRepository;
    @Autowired TradeAuditRepository auditRepository;
    @Autowired Clock clock;

    private final LocalDate today = LocalDate.now();

    @BeforeEach
    void clean() {
        tradeRepository.deleteAll();
        auditRepository.deleteAll();
    }

    @Test
    void briefSampleData_thenExpiry_thenExpiredIsTerminal() {
        // 1. T1 v1, maturity in the past -> REJECTED (R3)
        assertThatThrownBy(() -> tradeService.handle(new TradeMessage("T1", 1, "CP-1", "B1", today.minusYears(5))))
                .isInstanceOf(MaturityDateException.class);

        // 2. T2 v2, future maturity -> ACCEPTED
        tradeService.handle(new TradeMessage("T2", 2, "CP-2", "B1", today.plusYears(1)));

        // 3. T2 v1 -> REJECTED (R1, lower version)
        assertThatThrownBy(() -> tradeService.handle(new TradeMessage("T2", 1, "CP-1", "B1", today.plusYears(1))))
                .isInstanceOf(LowerVersionException.class);

        // 4. T3 v3, maturity in the past -> REJECTED (R3)
        assertThatThrownBy(() -> tradeService.handle(new TradeMessage("T3", 3, "CP-3", "B2", today.minusYears(12))))
                .isInstanceOf(MaturityDateException.class);

        // Final SQL state: only T2 v2 exists
        assertThat(tradeRepository.findAll())
                .extracting(Trade::getTradeId, Trade::getVersion, Trade::getExpired)
                .containsExactly(tuple("T2", 2, 'N'));

        // Audit log sequence
        assertThat(auditRepository.findAll(Sort.by("id")))
                .extracting(TradeAuditEvent::getTradeId, TradeAuditEvent::getVersion, TradeAuditEvent::getEventType)
                .containsExactly(
                        tuple("T1", 1, EventType.REJECTED),
                        tuple("T2", 2, EventType.ACCEPTED),
                        tuple("T2", 1, EventType.REJECTED),
                        tuple("T3", 3, EventType.REJECTED));

        // R4: a trade maturing today is valid now; two days later the expiry job flips it to 'Y'
        tradeService.handle(new TradeMessage("T4", 1, "CP-4", "B3", today));
        assertThat(tradeRepository.findById("T4").orElseThrow().getExpired()).isEqualTo('N');

        TradeExpiryScheduler jobTwoDaysLater = new TradeExpiryScheduler(
                tradeRepository, auditRepository, Clock.offset(clock, Duration.ofDays(2)));
        jobTwoDaysLater.expireMaturedTrades();

        assertThat(tradeRepository.findById("T4").orElseThrow().getExpired()).isEqualTo('Y');
        assertThat(auditRepository.findByTradeId("T4"))
                .extracting(TradeAuditEvent::getEventType)
                .containsExactlyInAnyOrder(EventType.ACCEPTED, EventType.EXPIRED);

        // R5: higher version for the now-expired trade is rejected; it is never reactivated
        assertThatThrownBy(() -> tradeService.handle(new TradeMessage("T4", 2, "CP-4", "B3", today.plusYears(1))))
                .isInstanceOf(TradeExpiredException.class);

        assertThat(tradeRepository.findById("T4").orElseThrow().getExpired()).isEqualTo('Y');
        assertThat(tradeRepository.findById("T4").orElseThrow().getVersion()).isEqualTo(1);
        assertThat(auditRepository.findByTradeId("T4"))
                .extracting(TradeAuditEvent::getEventType)
                .contains(EventType.REJECTED);
    }
    @Test
    void r2_sameVersionOverwritesExistingRecord() {
        tradeService.handle(new TradeMessage("T5", 1, "CP-1", "B1", today.plusYears(1)));
        tradeService.handle(new TradeMessage("T5", 1, "CP-9", "B9", today.plusYears(2)));

        assertThat(tradeRepository.findAll()).hasSize(1);
        Trade t = tradeRepository.findById("T5").orElseThrow();
        assertThat(t.getCounterPartyId()).isEqualTo("CP-9");
        assertThat(t.getBookId()).isEqualTo("B9");
        assertThat(t.getMaturityDate()).isEqualTo(today.plusYears(2));
        assertThat(t.getLockVersion()).isEqualTo(1);   // updated in place, not a second row

        assertThat(auditRepository.findByTradeId("T5"))
                .extracting(TradeAuditEvent::getEventType)
                .containsExactly(EventType.ACCEPTED, EventType.ACCEPTED);
    }
}
