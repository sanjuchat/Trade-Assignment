package com.example.tradestore.web;

import com.example.tradestore.domain.Trade;
import com.example.tradestore.dto.TradeMessage;
import com.example.tradestore.service.TradeService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/trades")
public class TradeController {

    private final TradeService tradeService;

    public TradeController(TradeService tradeService) {
        this.tradeService = tradeService;
    }

    @PostMapping
    public ResponseEntity<Void> submit(@Valid @RequestBody TradeMessage message) {
        tradeService.handle(message);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/{tradeId}")
    public ResponseEntity<Trade> get(@PathVariable String tradeId) {
        return tradeService.findById(tradeId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<Trade> list() {
        return tradeService.findAll();
    }
}
