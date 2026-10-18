package com.example.tradestore.repository;

import com.example.tradestore.domain.Trade;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<Trade, String> {

    List<Trade> findByMaturityDateBeforeAndExpired(LocalDate today, char expired);
}
