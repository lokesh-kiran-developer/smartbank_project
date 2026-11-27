package com.smartbank.transaction.controller;

import com.smartbank.transaction.dto.TransferRequest;
import com.smartbank.transaction.dto.TransferResponse;
import com.smartbank.transaction.exception.AccountServiceUnavailableException;
import com.smartbank.transaction.exception.TransferNotFoundException;
import com.smartbank.transaction.model.Transaction;
import com.smartbank.transaction.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TransactionController.class)
class TransactionControllerTest {

    private static final String TRANSFER_JSON = "{\"fromAccountId\":1,\"toAccountId\":2,\"amount\":10.00}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TransactionService transactionService;

    @Test
    void shouldReturnTransferWhenItExists() throws Exception {
        Transaction txn = new Transaction(1L, 2L, new BigDecimal("500.00"), "key-1");
        ReflectionTestUtils.setField(txn, "id", 42L);
        txn.setStatus("COMPLETED");
        when(transactionService.getTransfer(42L)).thenReturn(TransferResponse.fromEntity(txn));

        mockMvc.perform(get("/api/transactions/42"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value(42))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void shouldReturn404WhenTransferDoesNotExist() throws Exception {
        when(transactionService.getTransfer(99L)).thenThrow(new TransferNotFoundException(99L));

        mockMvc.perform(get("/api/transactions/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void shouldReturn503WhenAccountServiceIsUnavailable() throws Exception {
        when(transactionService.processTransfer(any(TransferRequest.class), anyString()))
                .thenThrow(new AccountServiceUnavailableException("down", new RuntimeException("boom")));

        mockMvc.perform(post("/api/transactions/transfer")
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TRANSFER_JSON))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void shouldPassThroughBusinessErrorFromAccountService() throws Exception {
        when(transactionService.processTransfer(any(TransferRequest.class), anyString()))
                .thenThrow(new HttpClientErrorException(HttpStatus.CONFLICT));

        mockMvc.perform(post("/api/transactions/transfer")
                        .header("Idempotency-Key", "k2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TRANSFER_JSON))
                .andExpect(status().isConflict());
    }
}
