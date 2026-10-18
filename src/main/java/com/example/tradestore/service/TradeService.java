package com.example.tradestore.service;

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
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Orchestration: load existing -> validate -> persist current state -> append audit event.
 * Transport-agnostic: Kafka and REST both call handle(). Writes are safe to repeat (AD-4).
 */
@Service
public class TradeService {

    private final TradeRepository tradeRepository;
    private final TradeAuditRepository auditRepository;
    private final TradeValidator validator;
    private final Clock clock;

    public TradeService(TradeRepository tradeRepository, TradeAuditRepository auditRepository,
                        TradeValidator validator, Clock clock) {
        this.tradeRepository = tradeRepository;
        this.auditRepository = auditRepository;
        this.validator = validator;
        this.clock = clock;
    }

    public void handle(TradeMessage message) {
        LocalDate today = LocalDate.now(clock);
        Optional<Trade> existing = tradeRepository.findById(message.tradeId());

        try {
            validator.validate(message, existing);
        } catch (LowerVersionException | MaturityDateException | TradeExpiredException e) {
            audit(message, today, EventType.REJECTED, e.getMessage());
            throw e;
        }

        // Mutate the loaded row when present so the JPA @Version (lockVersion) is preserved.
        Trade trade = existing.orElseGet(Trade::new);
        trade.setTradeId(message.tradeId());
        trade.setVersion(message.version());
        trade.setCounterPartyId(message.counterPartyId());
        trade.setBookId(message.bookId());
        trade.setMaturityDate(message.maturityDate());
        if (existing.isEmpty()) {
            trade.setCreatedDate(today);
        }
        trade.setExpired('N');
        tradeRepository.save(trade);

        audit(message, today, EventType.ACCEPTED, null);
    }

    public Optional<Trade> findById(String tradeId) {
        return tradeRepository.findById(tradeId);
    }

    public List<Trade> findAll() {
        return tradeRepository.findAll();
    }

    private void audit(TradeMessage m, LocalDate today, EventType type, String reason) {
        auditRepository.save(new TradeAuditEvent(m.tradeId(), m.version(), m.counterPartyId(), m.bookId(),
                m.maturityDate(), today, type, reason, clock.instant()));
    }
}
