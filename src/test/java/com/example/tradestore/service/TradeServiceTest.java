package com.example.tradestore.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.tradestore.domain.EventType;
import com.example.tradestore.domain.Trade;
import com.example.tradestore.domain.TradeAuditEvent;
import com.example.tradestore.dto.TradeMessage;
import com.example.tradestore.exception.LowerVersionException;
import com.example.tradestore.exception.MaturityDateException;
import com.example.tradestore.exception.TradeExpiredException;
import com.example.tradestore.repository.TradeAuditRepository;
import com.example.tradestore.repository.TradeRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TradeServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

    @Mock TradeRepository tradeRepository;
    @Mock TradeAuditRepository auditRepository;
    @Mock TradeValidator validator;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private TradeService service;

    @BeforeEach
    void setUp() {
        service = new TradeService(tradeRepository, auditRepository, validator, clock);
    }

    private TradeMessage message(int version, LocalDate maturity) {
        return new TradeMessage("T1", version, "CP-1", "B1", maturity);
    }

    private Trade existing(int version, char expired) {
        Trade t = new Trade();
        t.setTradeId("T1");
        t.setVersion(version);
        t.setExpired(expired);
        t.setCreatedDate(TODAY.minusDays(30));
        t.setMaturityDate(TODAY.plusDays(10));
        return t;
    }

    private TradeAuditEvent capturedAudit() {
        ArgumentCaptor<TradeAuditEvent> captor = ArgumentCaptor.forClass(TradeAuditEvent.class);
        verify(auditRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void acceptedPath_savesTradeAndAppendsAcceptedAudit() {
        when(tradeRepository.findById("T1")).thenReturn(Optional.empty());

        service.handle(message(1, TODAY.plusDays(5)));

        ArgumentCaptor<Trade> tradeCaptor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeRepository).save(tradeCaptor.capture());
        Trade saved = tradeCaptor.getValue();
        assertThat(saved.getTradeId()).isEqualTo("T1");
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getExpired()).isEqualTo('N');
        assertThat(saved.getCreatedDate()).isEqualTo(TODAY);

        TradeAuditEvent audit = capturedAudit();
        assertThat(audit.getEventType()).isEqualTo(EventType.ACCEPTED);
        assertThat(audit.getRejectionReason()).isNull();
        assertThat(audit.getReceivedAt()).isEqualTo(clock.instant());
    }

    @Test
    void r1_lowerVersion_auditsRejectedDoesNotSaveAndRethrows() {
        Optional<Trade> existing = Optional.of(existing(2, 'N'));
        TradeMessage msg = message(1, TODAY.plusDays(5));
        when(tradeRepository.findById("T1")).thenReturn(existing);
        doThrow(new LowerVersionException("lower version")).when(validator).validate(msg, existing);

        assertThatThrownBy(() -> service.handle(msg)).isInstanceOf(LowerVersionException.class);

        verify(tradeRepository, never()).save(any());
        TradeAuditEvent audit = capturedAudit();
        assertThat(audit.getEventType()).isEqualTo(EventType.REJECTED);
        assertThat(audit.getRejectionReason()).isEqualTo("lower version");
    }

    @Test
    void r3_pastMaturity_auditsRejectedDoesNotSaveAndRethrows() {
        TradeMessage msg = message(1, TODAY.minusDays(1));
        when(tradeRepository.findById("T1")).thenReturn(Optional.empty());
        doThrow(new MaturityDateException("past maturity")).when(validator).validate(msg, Optional.empty());

        assertThatThrownBy(() -> service.handle(msg)).isInstanceOf(MaturityDateException.class);

        verify(tradeRepository, never()).save(any());
        assertThat(capturedAudit().getEventType()).isEqualTo(EventType.REJECTED);
    }

    @Test
    void r5_expiredTrade_higherVersionStillRejected() {
        Optional<Trade> expired = Optional.of(existing(2, 'Y'));
        TradeMessage msg = message(3, TODAY.plusDays(5));
        when(tradeRepository.findById("T1")).thenReturn(expired);
        doThrow(new TradeExpiredException("expired")).when(validator).validate(msg, expired);

        assertThatThrownBy(() -> service.handle(msg)).isInstanceOf(TradeExpiredException.class);

        verify(tradeRepository, never()).save(any());
        TradeAuditEvent audit = capturedAudit();
        assertThat(audit.getEventType()).isEqualTo(EventType.REJECTED);
        assertThat(audit.getRejectionReason()).isEqualTo("expired");
    }

    @Test
    void r2_sameVersion_overwritesExistingRow() {
        Trade stored = existing(2, 'N');
        when(tradeRepository.findById("T1")).thenReturn(Optional.of(stored));

        service.handle(new TradeMessage("T1", 2, "CP-9", "B9", TODAY.plusDays(20)));

        ArgumentCaptor<Trade> captor = ArgumentCaptor.forClass(Trade.class);
        verify(tradeRepository).save(captor.capture());
        Trade saved = captor.getValue();
        assertThat(saved).isSameAs(stored);               // same row => lockVersion preserved
        assertThat(saved.getCounterPartyId()).isEqualTo("CP-9");
        assertThat(saved.getBookId()).isEqualTo("B9");
        assertThat(saved.getMaturityDate()).isEqualTo(TODAY.plusDays(20));
        assertThat(saved.getCreatedDate()).isEqualTo(TODAY.minusDays(30)); // original creation date kept
        assertThat(capturedAudit().getEventType()).isEqualTo(EventType.ACCEPTED);
    }
}
