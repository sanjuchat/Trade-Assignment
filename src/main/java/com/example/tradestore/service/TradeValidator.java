package com.example.tradestore.service;

import com.example.tradestore.domain.Trade;
import com.example.tradestore.dto.TradeMessage;
import com.example.tradestore.exception.LowerVersionException;
import com.example.tradestore.exception.MaturityDateException;
import com.example.tradestore.exception.TradeExpiredException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The trade rules (AD-6). Pure domain logic: no persistence, no transport.
 * Order: R3 (past maturity) -> R5 (already expired) -> R1 (lower version).
 * R2 (same version overwrites) is simply "no exception".
 */
@Component
public class TradeValidator {

    private final Clock clock;

    public TradeValidator(Clock clock) {
        this.clock = clock;
    }

    public void validate(TradeMessage incoming, Optional<Trade> existing) {
        LocalDate today = LocalDate.now(clock);

        if (incoming.maturityDate().isBefore(today)) {
            throw new MaturityDateException("Trade " + incoming.tradeId()
                    + " rejected: maturity date " + incoming.maturityDate() + " is before today (" + today + ")");
        }

        if (existing.isPresent()) {
            Trade current = existing.get();

            if (current.getExpired() == 'Y') {
                throw new TradeExpiredException("Trade " + incoming.tradeId()
                        + " rejected: trade is expired and cannot be updated or reactivated");
            }

            if (incoming.version() < current.getVersion()) {
                throw new LowerVersionException("Trade " + incoming.tradeId()
                        + " rejected: incoming version " + incoming.version()
                        + " is lower than stored version " + current.getVersion());
            }
        }
    }
}
