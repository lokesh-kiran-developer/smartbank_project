package com.smartbank.transaction.service;

import com.smartbank.transaction.client.AccountClient;
import com.smartbank.transaction.dto.TransferRequest;
import com.smartbank.transaction.dto.TransferResponse;
import com.smartbank.transaction.exception.AccountServiceUnavailableException;
import com.smartbank.transaction.exception.TransferNotFoundException;
import com.smartbank.transaction.model.Transaction;
import com.smartbank.transaction.model.TransferStatus;
import com.smartbank.transaction.repository.TransactionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Optional;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountClient accountClient;

    @Autowired
    public TransactionService(TransactionRepository transactionRepository, AccountClient accountClient) {
        this.transactionRepository = transactionRepository;
        this.accountClient = accountClient;
    }

    public TransferResponse processTransfer(TransferRequest request, String idempotencyKey) {
        Optional<Transaction> existing = transactionRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return TransferResponse.fromEntity(existing.get());
        }

        Transaction transaction;
        try {
            transaction = transactionRepository.saveAndFlush(new Transaction(
                    request.getFromAccountId(),
                    request.getToAccountId(),
                    request.getAmount(),
                    idempotencyKey
            ));
        } catch (DataIntegrityViolationException duplicateKey) {
            return transactionRepository.findByIdempotencyKey(idempotencyKey)
                    .map(TransferResponse::fromEntity)
                    .orElseThrow(() -> duplicateKey);
        }
        Long transferId = transaction.getId();

        // Step 1: debit the source account.
        try {
            accountClient.debitAccount(request.getFromAccountId(), request.getAmount(), transferId);
        } catch (RuntimeException e) {
            if (isBusinessRejection(e) || isRequestNeverSent(e)) {

                throw markFailed(transaction, e);
            }
            return reverseAndFinish(transaction, e, TransferStatus.FAILED);
        }

        // Step 2: credit the destination account.
        try {
            accountClient.creditAccount(request.getToAccountId(), request.getAmount(), transferId);
        } catch (RuntimeException e) {
            return reverseAndFinish(transaction, e, TransferStatus.COMPENSATED);
        }

        transaction.setStatus(TransferStatus.COMPLETED);
        return TransferResponse.fromEntity(transactionRepository.save(transaction));
    }

    public TransferResponse getTransfer(Long id) {
        return transactionRepository.findById(id)
                .map(TransferResponse::fromEntity)
                .orElseThrow(() -> new TransferNotFoundException(id));
    }

    private TransferResponse reverseAndFinish(Transaction transaction, RuntimeException cause, String statusIfReversed) {
        boolean reversed;
        try {
            reversed = accountClient.reverseDebit(transaction.getFromAccountId(), transaction.getId());
        } catch (RuntimeException reversalFailure) {
            transaction.setStatus(TransferStatus.NEEDS_REVIEW);
            transactionRepository.save(transaction);
            cause.addSuppressed(reversalFailure);
            throw cause;
        }

        if (!reversed) {
            transaction.setStatus(TransferStatus.COMPLETED);
            return TransferResponse.fromEntity(transactionRepository.save(transaction));
        }

        transaction.setStatus(statusIfReversed);
        transactionRepository.save(transaction);
        throw cause;
    }

    private RuntimeException markFailed(Transaction transaction, RuntimeException cause) {
        transaction.setStatus(TransferStatus.FAILED);
        transactionRepository.save(transaction);
        return cause;
    }

    private boolean isBusinessRejection(RuntimeException e) {
        return e instanceof HttpClientErrorException;
    }

    private boolean isRequestNeverSent(RuntimeException e) {
        return e instanceof AccountServiceUnavailableException
                && ((AccountServiceUnavailableException) e).isRequestNeverSent();
    }
}
