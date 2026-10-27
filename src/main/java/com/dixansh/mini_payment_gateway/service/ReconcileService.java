package com.dixansh.mini_payment_gateway.service;

import com.dixansh.mini_payment_gateway.domain.Account;
import com.dixansh.mini_payment_gateway.dto.ReconcileResponse;
import com.dixansh.mini_payment_gateway.repository.AccountRepository;
import com.dixansh.mini_payment_gateway.repository.LedgerEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Consistency audit. Two things must hold for the system to be healthy:
 *   1. the signed sum across the entire ledger is exactly zero;
 *   2. every customer account's cached balance equals its ledger-derived balance.
 *
 * <p>System funding accounts are excluded from (2) by design - their cached balance carries the
 * settlement float that never had originating ledger entries (see {@link SystemAccountService}).
 */
@Service
public class ReconcileService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final SystemAccountService systemAccountService;

    public ReconcileService(AccountRepository accountRepository,
                            LedgerEntryRepository ledgerEntryRepository,
                            SystemAccountService systemAccountService) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.systemAccountService = systemAccountService;
    }

    @Transactional(readOnly = true)
    public ReconcileResponse reconcile() {
        BigDecimal signedSum = ledgerEntryRepository.signedSum();

        List<ReconcileResponse.AccountMismatch> mismatches = new ArrayList<>();
        for (Account a : accountRepository.findAll()) {
            if (systemAccountService.isSystemAccount(a)) {
                continue;
            }
            BigDecimal derived = accountRepository.derivedBalance(a.getId());
            if (a.getBalance().compareTo(derived) != 0) {
                mismatches.add(new ReconcileResponse.AccountMismatch(a.getId(), a.getBalance(), derived));
            }
        }

        boolean balanced = signedSum != null && signedSum.compareTo(BigDecimal.ZERO) == 0 && mismatches.isEmpty();
        return new ReconcileResponse(Objects.requireNonNullElse(signedSum, BigDecimal.ZERO), balanced, mismatches);
    }
}
