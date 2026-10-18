package com.example.tradestore.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TradeTest {

    @Test
    void expiredDefaultsToN() {
        assertThat(new Trade().getExpired()).isEqualTo('N');
    }

    @Test
    void lockVersionStartsAtZeroAndIsSeparateFromBusinessVersion() {
        Trade trade = new Trade();
        trade.setVersion(5);

        assertThat(trade.getLockVersion()).isZero();
        assertThat(trade.getVersion()).isEqualTo(5);
    }

    @Test
    void settersMapFields() {
        Trade trade = new Trade();
        LocalDate maturity = LocalDate.of(2030, 1, 1);
        LocalDate created = LocalDate.of(2026, 10, 17);

        trade.setTradeId("T1");
        trade.setVersion(1);
        trade.setCounterPartyId("CP-1");
        trade.setBookId("B1");
        trade.setMaturityDate(maturity);
        trade.setCreatedDate(created);

        assertThat(trade.getTradeId()).isEqualTo("T1");
        assertThat(trade.getVersion()).isEqualTo(1);
        assertThat(trade.getCounterPartyId()).isEqualTo("CP-1");
        assertThat(trade.getBookId()).isEqualTo("B1");
        assertThat(trade.getMaturityDate()).isEqualTo(maturity);
        assertThat(trade.getCreatedDate()).isEqualTo(created);
    }
}
