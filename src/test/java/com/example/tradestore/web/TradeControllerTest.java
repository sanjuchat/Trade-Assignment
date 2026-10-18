package com.example.tradestore.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.tradestore.domain.Trade;
import com.example.tradestore.dto.TradeMessage;
import com.example.tradestore.exception.LowerVersionException;
import com.example.tradestore.exception.MaturityDateException;
import com.example.tradestore.exception.TradeExpiredException;
import com.example.tradestore.service.TradeService;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Documents the HTTP contract:
 * POST /api/trades -> 202 | 400 {status,error,message,timestamp}
 * GET /api/trades/{id} -> 200 | 404 ; GET /api/trades -> 200
 */
@WebMvcTest(TradeController.class)
class TradeControllerTest {

    @Autowired MockMvc mvc;
    @MockBean TradeService tradeService;

    private String body(String id, int version, String maturity) {
        return """
                {"tradeId":"%s","version":%d,"counterPartyId":"CP-1","bookId":"B1","maturityDate":"%s"}
                """.formatted(id, version, maturity);
    }

    private String future() {
        return LocalDate.now().plusDays(10).toString();
    }

    @Test
    void post_validTrade_returns202AndDelegates() throws Exception {
        mvc.perform(post("/api/trades").contentType(MediaType.APPLICATION_JSON).content(body("T1", 1, future())))
                .andExpect(status().isAccepted());

        verify(tradeService).handle(any(TradeMessage.class));
    }

    @Test
    void post_invalidBody_returns400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/trades").contentType(MediaType.APPLICATION_JSON).content(body("", 0, future())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ValidationError"))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void post_lowerVersion_returns400() throws Exception {
        doThrow(new LowerVersionException("lower")).when(tradeService).handle(any());

        mvc.perform(post("/api/trades").contentType(MediaType.APPLICATION_JSON).content(body("T1", 1, future())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("LowerVersionException"))
                .andExpect(jsonPath("$.message").value("lower"));
    }

    @Test
    void post_pastMaturity_returns400() throws Exception {
        doThrow(new MaturityDateException("past")).when(tradeService).handle(any());

        mvc.perform(post("/api/trades").contentType(MediaType.APPLICATION_JSON).content(body("T1", 1, "2020-05-20")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MaturityDateException"));
    }

    @Test
    void post_expiredTrade_returns400() throws Exception {
        doThrow(new TradeExpiredException("expired")).when(tradeService).handle(any());

        mvc.perform(post("/api/trades").contentType(MediaType.APPLICATION_JSON).content(body("T1", 9, future())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("TradeExpiredException"));
    }

    @Test
    void get_existingTrade_returns200() throws Exception {
        Trade trade = new Trade();
        trade.setTradeId("T1");
        trade.setVersion(2);
        when(tradeService.findById("T1")).thenReturn(Optional.of(trade));

        mvc.perform(get("/api/trades/T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tradeId").value("T1"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.expired").value("N"));
    }

    @Test
    void get_unknownTrade_returns404() throws Exception {
        when(tradeService.findById("NOPE")).thenReturn(Optional.empty());

        mvc.perform(get("/api/trades/NOPE")).andExpect(status().isNotFound());
    }

    @Test
    void list_returns200WithTrades() throws Exception {
        Trade trade = new Trade();
        trade.setTradeId("T1");
        when(tradeService.findAll()).thenReturn(List.of(trade));

        mvc.perform(get("/api/trades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tradeId").value("T1"));
    }
}
