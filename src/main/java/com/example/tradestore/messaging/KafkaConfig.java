package com.example.tradestore.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.example.tradestore.exception.LowerVersionException;
import com.example.tradestore.exception.MaturityDateException;
import com.example.tradestore.exception.TradeExpiredException;
import jakarta.validation.ConstraintViolationException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.KafkaAdmin.NewTopics;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka wiring.
 *
 * Ordering (AD-3): producers MUST key every record by tradeId, e.g.
 * {@code kafkaTemplate.send("trades.inbound", trade.tradeId(), json)}.
 * All messages for one trade then land on one partition and are consumed in order.
 *
 * Failures: business-rule violations and malformed payloads are deterministic, so they are
 * NOT retried and go straight to the dead-letter topic. Anything else (e.g. a datastore briefly
 * down) is retried a bounded number of times, then dead-lettered.
 */
@Configuration
@ConditionalOnProperty(name = "trade.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConfig {

    @Bean
    public NewTopics tradeTopics(@Value("${trade.kafka.topic}") String inbound,
                                 @Value("${trade.kafka.dlt-topic}") String dlt,
                                 @Value("${trade.kafka.partitions:3}") int partitions) {
        return new NewTopics(
                TopicBuilder.name(inbound).partitions(partitions).replicas(1).build(),
                TopicBuilder.name(dlt).partitions(1).replicas(1).build());
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template,
                                                 @Value("${trade.kafka.dlt-topic}") String dlt) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template, (record, ex) -> new TopicPartition(dlt, -1));   // -1 = let the producer pick

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(200L, 2));
        handler.addNotRetryableExceptions(
                LowerVersionException.class,
                MaturityDateException.class,
                TradeExpiredException.class,
                ConstraintViolationException.class,
                JsonProcessingException.class);
        return handler;
    }
}
