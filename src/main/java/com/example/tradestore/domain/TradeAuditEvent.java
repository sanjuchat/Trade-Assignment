package com.example.tradestore.domain;

import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One append-only history entry per processed message (AD-1). */
@Document("trade_audit")
public class TradeAuditEvent {

    @Id
    private String id;

    @Indexed
    private String tradeId;

    private int version;
    private String counterPartyId;
    private String bookId;
    private LocalDate maturityDate;
    private LocalDate createdDate;
    private EventType eventType;
    private String rejectionReason;

    @Indexed
    private Instant receivedAt;

    public TradeAuditEvent() {
    }

    public TradeAuditEvent(String tradeId, int version, String counterPartyId, String bookId,
                           LocalDate maturityDate, LocalDate createdDate, EventType eventType,
                           String rejectionReason, Instant receivedAt) {
        this.tradeId = tradeId;
        this.version = version;
        this.counterPartyId = counterPartyId;
        this.bookId = bookId;
        this.maturityDate = maturityDate;
        this.createdDate = createdDate;
        this.eventType = eventType;
        this.rejectionReason = rejectionReason;
        this.receivedAt = receivedAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTradeId() { return tradeId; }
    public int getVersion() { return version; }
    public String getCounterPartyId() { return counterPartyId; }
    public String getBookId() { return bookId; }
    public LocalDate getMaturityDate() { return maturityDate; }
    public LocalDate getCreatedDate() { return createdDate; }
    public EventType getEventType() { return eventType; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getReceivedAt() { return receivedAt; }
}
