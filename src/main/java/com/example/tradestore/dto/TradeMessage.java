package com.example.tradestore.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * Inbound trade (Kafka or REST). createdDate and expired are assigned by the store,
 * never trusted from the sender.
 */
public record TradeMessage(
        @NotBlank String tradeId,
        @Min(1) int version,
        @NotBlank String counterPartyId,
        @NotBlank String bookId,
        @NotNull LocalDate maturityDate) {
}
