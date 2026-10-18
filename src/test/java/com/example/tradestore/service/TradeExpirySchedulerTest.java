package com.example.tradestore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.tradestore.domain.EventType;
import com.example.tradestore.domain.Trade;
import com.example.tradestore.domain.TradeAuditEvent;
import com.example.tradestore.repository.TradeAuditRepository;
import com.example.tradestore.repository.TradeRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TradeExpirySchedulerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

    @Mock TradeRepository tradeRepository;
    @Mock TradeAuditRepository auditRepository;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-17T00:05:00Z"), ZoneOffset.UTC);
    private TradeExpiryScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new TradeExpiryScheduler(tradeRepository, auditRepository, clock);
    }

    private Trade matured(String id) {
        Trade t = new Trade();
        t.setTradeId(id);
        t.setVersion(1);
        t.setCounterPartyId("CP-1");
        t.setBookId("B1");
        t.setMaturityDate(TODAY.minusDays(1));
        t.setCreatedDate(TODAY.minusDays(30));
        return t;
    }

    @Test
    void flipsMaturedTradesToExpiredAndAuditsEach() {
        Trade a = matured("A");
        Trade b = matured("B");
        when(tradeRepository.findByMaturityDateBeforeAndExpired(TODAY, 'N')).thenReturn(List.of(a, b));

        scheduler.expireMaturedTrades();

        assertThat(a.getExpired()).isEqualTo('Y');
        assertThat(b.getExpired()).isEqualTo('Y');
        verify(tradeRepository).save(a);
        verify(tradeRepository).save(b);

        ArgumentCaptor<TradeAuditEvent> captor = ArgumentCaptor.forClass(TradeAuditEvent.class);
        verify(auditRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(TradeAuditEvent::getTradeId, TradeAuditEvent::getEventType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("A", EventType.EXPIRED),
                        org.assertj.core.groups.Tuple.tuple("B", EventType.EXPIRED));
    }

    @Test
    void secondRunWithNoMaturedRows_doesNothing() {
        when(tradeRepository.findByMaturityDateBeforeAndExpired(TODAY, 'N')).thenReturn(List.of());

        scheduler.expireMaturedTrades();
        scheduler.expireMaturedTrades();

        verify(tradeRepository, never()).save(any());
        verify(auditRepository, never()).save(any());
    }
}
