package com.example.tradestore.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.tradestore.domain.Trade;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TradeRepositoryTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

    @Autowired TradeRepository repository;

    private Trade trade(String id, int version, LocalDate maturity, char expired) {
        Trade t = new Trade();
        t.setTradeId(id);
        t.setVersion(version);
        t.setCounterPartyId("CP-1");
        t.setBookId("B1");
        t.setMaturityDate(maturity);
        t.setCreatedDate(TODAY);
        t.setExpired(expired);
        return t;
    }

    @Test
    void saveAndUpsertByTradeId_keepsOneRowPerTrade() {
        repository.saveAndFlush(trade("T1", 1, TODAY.plusDays(5), 'N'));

        Trade loaded = repository.findById("T1").orElseThrow();
        loaded.setVersion(2);
        repository.saveAndFlush(loaded);

        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findById("T1").orElseThrow().getVersion()).isEqualTo(2);
    }

    @Test
    void findByMaturityDateBeforeAndExpired_returnsOnlyMaturedNonExpired() {
        repository.save(trade("OLD-N", 1, TODAY.minusDays(1), 'N'));
        repository.save(trade("OLD-Y", 1, TODAY.minusDays(1), 'Y'));
        repository.save(trade("TODAY-N", 1, TODAY, 'N'));
        repository.save(trade("FUTURE-N", 1, TODAY.plusDays(1), 'N'));
        repository.flush();

        List<Trade> result = repository.findByMaturityDateBeforeAndExpired(TODAY, 'N');

        assertThat(result).extracting(Trade::getTradeId).containsExactly("OLD-N");
    }

    @Test
    void optimisticLockVersion_bumpsOnUpdate() {
        Trade saved = repository.saveAndFlush(trade("T1", 1, TODAY.plusDays(5), 'N'));
        long before = saved.getLockVersion();

        saved.setBookId("B2");
        Trade updated = repository.saveAndFlush(saved);

        assertThat(updated.getLockVersion()).isEqualTo(before + 1);
    }
}
