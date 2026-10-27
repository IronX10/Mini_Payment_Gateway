package com.dixansh.mini_payment_gateway.service;

import com.dixansh.mini_payment_gateway.domain.Account;
import com.dixansh.mini_payment_gateway.domain.User;
import com.dixansh.mini_payment_gateway.repository.AccountRepository;
import com.dixansh.mini_payment_gateway.repository.UserRepository;
import com.dixansh.mini_payment_gateway.util.HashUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Owns the system "settlement float" accounts that money originates from.
 *
 * <p>Real money has to enter the ledger somewhere. Rather than mutate a balance out of thin air
 * (which would break reconciliation), a top-up is modelled as a transfer FROM a per-currency
 * funding account that belongs to a reserved system user. That keeps every customer top-up a
 * balanced double-entry pair, so the global ledger still sums to zero. The funding account is
 * seeded with a large float and is deliberately excluded from customer reconciliation - it
 * represents the gateway's own settlement position (see DECISIONS.md).
 */
@Service
public class SystemAccountService {

    public static final String SYSTEM_EMAIL = "system@mpg.local";
    /** 1e15 - large enough to fund any dev/test scenario while staying within NUMERIC(18,2). */
    private static final BigDecimal FUNDING_SEED = new BigDecimal("1000000000000000.00");

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;

    public SystemAccountService(UserRepository userRepository, AccountRepository accountRepository) {
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
    }

    @Transactional
    public User ensureSystemUser() {
        return userRepository.findByEmail(SYSTEM_EMAIL)
                .orElseGet(() -> userRepository.save(
                        new User(SYSTEM_EMAIL, HashUtil.sha256Hex("system-" + UUID.randomUUID()))));
    }

    /** Find-or-create the seeded funding account for a currency. */
    @Transactional
    public Account fundingAccount(String currency) {
        Long systemUserId = ensureSystemUser().getId();
        return accountRepository.findFirstByUserIdAndCurrency(systemUserId, currency)
                .orElseGet(() -> {
                    Account a = new Account(systemUserId, currency);
                    a.setBalance(FUNDING_SEED);
                    return accountRepository.save(a);
                });
    }

    /** True for accounts owned by the reserved system user (excluded from customer reconciliation). */
    public boolean isSystemAccount(Account account) {
        Long systemUserId = userRepository.findByEmail(SYSTEM_EMAIL).map(User::getId).orElse(null);
        return systemUserId != null && systemUserId.equals(account.getUserId());
    }
}
