package com.example.tradestore.messaging;

import com.example.tradestore.dto.TradeMessage;
import com.example.tradestore.service.TradeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Thin transport adapter: parse -> bean-validate -> delegate. All business rules live in TradeService. */
@Component
@ConditionalOnProperty(name = "trade.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class TradeKafkaListener {

    private final TradeService tradeService;
    private final ObjectMapper objectMapper;
    private final Validator beanValidator;

    public TradeKafkaListener(TradeService tradeService, ObjectMapper objectMapper, Validator beanValidator) {
        this.tradeService = tradeService;
        this.objectMapper = objectMapper;
        this.beanValidator = beanValidator;
    }

    @KafkaListener(topics = "${trade.kafka.topic}")
    public void onMessage(ConsumerRecord<String, String> record) throws JsonProcessingException {
        TradeMessage message = objectMapper.readValue(record.value(), TradeMessage.class);

        Set<ConstraintViolation<TradeMessage>> violations = beanValidator.validate(message);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
        tradeService.handle(message);
    }
}
