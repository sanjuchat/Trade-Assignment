package com.example.tradestore.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.example.tradestore.domain.EventType;
import com.example.tradestore.domain.Trade;
import com.example.tradestore.domain.TradeAuditEvent;
import com.example.tradestore.repository.TradeAuditRepository;
import com.example.tradestore.repository.TradeRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"trades.inbound", "trades.dlt"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@DirtiesContext
class TradeKafkaListenerIT {

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired EmbeddedKafkaBroker broker;
    @Autowired TradeRepository tradeRepository;
    @Autowired TradeAuditRepository auditRepository;

    @BeforeEach
    void clean() {
        tradeRepository.deleteAll();
        auditRepository.deleteAll();
    }

    private String json(String id, int version, LocalDate maturity) {
        return """
                {"tradeId":"%s","version":%d,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"%s"}
                """.formatted(id, version, maturity);
    }

    @Test
    void happyPath_validTradeIsPersistedAndAudited() {
        kafkaTemplate.send("trades.inbound", "K-OK", json("K-OK", 1, LocalDate.now().plusDays(10)));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Trade stored = tradeRepository.findById("K-OK").orElseThrow();
            assertThat(stored.getVersion()).isEqualTo(1);
            assertThat(stored.getExpired()).isEqualTo('N');
            assertThat(auditRepository.findByTradeId("K-OK"))
                    .extracting(TradeAuditEvent::getEventType)
                    .containsExactly(EventType.ACCEPTED);
        });
    }

    @Test
    void failurePath_lowerVersionGoesToDltAndStateIsUnchanged() {
        kafkaTemplate.send("trades.inbound", "K-BAD", json("K-BAD", 2, LocalDate.now().plusDays(10)));
        await().atMost(Duration.ofSeconds(20))
                .until(() -> tradeRepository.findById("K-BAD").isPresent());

        String lowerVersion = json("K-BAD", 1, LocalDate.now().plusDays(10));
        kafkaTemplate.send("trades.inbound", "K-BAD", lowerVersion);

        try (Consumer<String, String> dltConsumer = dltConsumer()) {
            broker.consumeFromAnEmbeddedTopic(dltConsumer, "trades.dlt");
            ConsumerRecord<String, String> dead =
                    KafkaTestUtils.getSingleRecord(dltConsumer, "trades.dlt", Duration.ofSeconds(20));
            assertThat(dead.key()).isEqualTo("K-BAD");
            assertThat(dead.value()).isEqualTo(lowerVersion);
        }

        assertThat(tradeRepository.findById("K-BAD").orElseThrow().getVersion()).isEqualTo(2);
        assertThat(auditRepository.findByTradeId("K-BAD"))
                .extracting(TradeAuditEvent::getEventType)
                .containsExactly(EventType.ACCEPTED, EventType.REJECTED);
    }

    private Consumer<String, String> dltConsumer() {
        Map<String, Object> props = KafkaTestUtils.consumerProps("dlt-test-group", "true", broker);
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
    }
}
