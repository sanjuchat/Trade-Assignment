package com.example.tradestore.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.tradestore.domain.Trade;
import com.example.tradestore.dto.TradeMessage;
import com.example.tradestore.exception.LowerVersionException;
import com.example.tradestore.exception.MaturityDateException;
import com.example.tradestore.exception.TradeExpiredException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TradeValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), ZoneOffset.UTC);
    private final TradeValidator validator = new TradeValidator(clock);

    private TradeMessage message(int version, LocalDate maturity) {
        return new TradeMessage("T1", version, "CP-1", "B1", maturity);
    }

    private Trade existing(int version, char expired) {
        Trade t = new Trade();
        t.setTradeId("T1");
        t.setVersion(version);
        t.setExpired(expired);
        t.setMaturityDate(TODAY.plusDays(10));
        return t;
    }

    @Test
    void r3_pastMaturityIsRejected() {
        assertThatThrownBy(() -> validator.validate(message(1, TODAY.minusDays(1)), Optional.empty()))
                .isInstanceOf(MaturityDateException.class);
    }

    @Test
    void r3_maturityToday_isAllowed() {
        assertThatCode(() -> validator.validate(message(1, TODAY), Optional.empty()))
                .doesNotThrowAnyException();
    }

    @Test
    void r5_expiredExistingTradeRejectsEvenHigherVersion() {
        assertThatThrownBy(() ->
                validator.validate(message(9, TODAY.plusDays(5)), Optional.of(existing(2, 'Y'))))
                .isInstanceOf(TradeExpiredException.class);
    }

    @Test
    void r1_lowerVersionIsRejected() {
        assertThatThrownBy(() ->
                validator.validate(message(1, TODAY.plusDays(5)), Optional.of(existing(2, 'N'))))
                .isInstanceOf(LowerVersionException.class);
    }

    @Test
    void r2_sameVersionIsAllowed() {
        assertThatCode(() ->
                validator.validate(message(2, TODAY.plusDays(5)), Optional.of(existing(2, 'N'))))
                .doesNotThrowAnyException();
    }

    @Test
    void higherVersionIsAllowed() {
        assertThatCode(() ->
                validator.validate(message(3, TODAY.plusDays(5)), Optional.of(existing(2, 'N'))))
                .doesNotThrowAnyException();
    }

    @Test
    void firstVersionWithFutureMaturityIsAllowed() {
        assertThatCode(() -> validator.validate(message(1, TODAY.plusDays(5)), Optional.empty()))
                .doesNotThrowAnyException();
    }

    @Test
    void evaluationOrder_pastMaturityWinsOverExpired() {
        assertThatThrownBy(() ->
                validator.validate(message(9, TODAY.minusDays(1)), Optional.of(existing(2, 'Y'))))
                .isInstanceOf(MaturityDateException.class);
    }
}
