package com.dixansh.mini_payment_gateway.service;

import com.dixansh.mini_payment_gateway.domain.Transaction;
import com.dixansh.mini_payment_gateway.domain.TxStatus;
import com.dixansh.mini_payment_gateway.dto.HeldPaymentResponse;
import com.dixansh.mini_payment_gateway.dto.PaymentResponse;
import com.dixansh.mini_payment_gateway.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The human-in-the-loop side of the fraud hook (Phase 5): list the payments the rules engine held,
 * and release one back into the normal flow. Kept separate from {@link PaymentService} (the
 * automated create path) because this is the manual review surface behind {@code /internal}.
 *
 * <p>The actual settlement on release is delegated to {@link TransferService#releaseHeld} so it runs
 * in its own transactional unit with the same locking and validation as any other transfer.
 */
@Service
public class FraudReviewService {

    private final TransactionRepository transactionRepository;
    private final TransferService transferService;

    public FraudReviewService(TransactionRepository transactionRepository, TransferService transferService) {
        this.transactionRepository = transactionRepository;
        this.transferService = transferService;
    }

    /** All payments currently HELD for review, newest first (backed by the partial index on HELD). */
    @Transactional(readOnly = true)
    public List<HeldPaymentResponse> listHeld() {
        return transactionRepository.findByStatusOrderByCreatedAtDesc(TxStatus.HELD).stream()
                .map(HeldPaymentResponse::from)
                .toList();
    }

    /**
     * Approve a held payment: settle it now and resume the normal event flow (emits
     * {@code payment.completed}). Returns the completed payment view.
     */
    public PaymentResponse release(UUID transactionId) {
        Transaction settled = transferService.releaseHeld(transactionId);
        return PaymentResponse.from(settled);
    }
}
