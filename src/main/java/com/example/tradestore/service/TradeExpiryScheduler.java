package com.example.tradestore.service;

import com.example.tradestore.domain.EventType;
import com.example.tradestore.domain.Trade;
import com.example.tradestore.domain.TradeAuditEvent;
import com.example.tradestore.repository.TradeAuditRepository;
import com.example.tradestore.repository.TradeRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * R4 (AD-7): marks trades whose maturity date has passed as expired.
 * Idempotent: only rows still flagged 'N' are selected, so re-running is harmless.
 * Expiry is terminal; TradeValidator (R5) blocks any later update.
 */
@Component
public class TradeExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(TradeExpiryScheduler.class);

    private final TradeRepository tradeRepository;
    private final TradeAuditRepository auditRepository;
    private final Clock clock;

    public TradeExpiryScheduler(TradeRepository tradeRepository, TradeAuditRepository auditRepository, Clock clock) {
        this.tradeRepository = tradeRepository;
        this.auditRepository = auditRepository;
        this.clock = clock;
    }

    @Scheduled(cron = "${trade.expiry.cron}")
    public void expireMaturedTrades() {
        LocalDate today = LocalDate.now(clock);
        List<Trade> matured = tradeRepository.findByMaturityDateBeforeAndExpired(today, 'N');

        for (Trade trade : matured) {
            trade.setExpired('Y');
            tradeRepository.save(trade);
            auditRepository.save(new TradeAuditEvent(trade.getTradeId(), trade.getVersion(),
                    trade.getCounterPartyId(), trade.getBookId(), trade.getMaturityDate(),
                    trade.getCreatedDate(), EventType.EXPIRED, null, clock.instant()));
        }
        if (!matured.isEmpty()) {
            log.info("Expired {} matured trade(s) as of {}", matured.size(), today);
        }
    }
}
