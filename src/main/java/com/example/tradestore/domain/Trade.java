package com.example.tradestore.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** Current state of a trade: exactly one row per tradeId (AD-5). */
@Entity
@Table(name = "trade")
public class Trade {

    @Id
    private String tradeId;

    /** Business version supplied by the sender. */
    private int version;

    private String counterPartyId;
    private String bookId;
    private LocalDate maturityDate;
    private LocalDate createdDate;

    @Column(nullable = false)
    private char expired = 'N';

    /** JPA optimistic-lock counter (AD-3). Distinct from the business {@code version}. */
    @jakarta.persistence.Version
    private long lockVersion;

    public Trade() {
    }

    public String getTradeId() { return tradeId; }
    public void setTradeId(String tradeId) { this.tradeId = tradeId; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public String getCounterPartyId() { return counterPartyId; }
    public void setCounterPartyId(String counterPartyId) { this.counterPartyId = counterPartyId; }

    public String getBookId() { return bookId; }
    public void setBookId(String bookId) { this.bookId = bookId; }

    public LocalDate getMaturityDate() { return maturityDate; }
    public void setMaturityDate(LocalDate maturityDate) { this.maturityDate = maturityDate; }

    public LocalDate getCreatedDate() { return createdDate; }
    public void setCreatedDate(LocalDate createdDate) { this.createdDate = createdDate; }

    public char getExpired() { return expired; }
    public void setExpired(char expired) { this.expired = expired; }

    public long getLockVersion() { return lockVersion; }
}
