package com.example.tradestore.repository;

import com.example.tradestore.domain.TradeAuditEvent;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface TradeAuditRepository extends MongoRepository<TradeAuditEvent, String> {

    List<TradeAuditEvent> findByTradeId(String tradeId);
}
