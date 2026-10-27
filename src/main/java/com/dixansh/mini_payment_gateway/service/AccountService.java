package com.dixansh.mini_payment_gateway.service;

import com.dixansh.mini_payment_gateway.config.CacheConfig;
import com.dixansh.mini_payment_gateway.domain.Account;
import com.dixansh.mini_payment_gateway.domain.LedgerEntry;
import com.dixansh.mini_payment_gateway.domain.Transaction;
import com.dixansh.mini_payment_gateway.domain.User;
import com.dixansh.mini_payment_gateway.dto.AccountResponse;
import com.dixansh.mini_payment_gateway.dto.CursorPage;
import com.dixansh.mini_payment_gateway.dto.LedgerEntryResponse;
import com.dixansh.mini_payment_gateway.exception.NotFoundException;
import com.dixansh.mini_payment_gateway.repository.AccountRepository;
import com.dixansh.mini_payment_gateway.repository.LedgerEntryRepository;
import com.dixansh.mini_payment_gateway.repository.UserRepository;
import com.dixansh.mini_payment_gateway.util.CursorCodec;
import com.dixansh.mini_payment_gateway.util.HashUtil;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransferService transferService;
    private final SystemAccountService systemAccountService;

    public AccountService(AccountRepository accountRepository,
                          UserRepository userRepository,
                          LedgerEntryRepository ledgerEntryRepository,
                          TransferService transferService,
                          SystemAccountService systemAccountService) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.transferService = transferService;
        this.systemAccountService = systemAccountService;
    }

    @Transactional
    public AccountResponse createAccount(String email, String currency) {
        String ccy = (currency == null || currency.isBlank()) ? "INR" : currency;
        User user = resolveUser(email);
        Account account = accountRepository.save(new Account(user.getId(), ccy));
        return toResponse(account, BigDecimal.ZERO);
    }

    /** Dev-only cash-in: a real double-entry transfer from the currency's funding account. */
    @Transactional
    @CacheEvict(cacheNames = CacheConfig.ACCOUNTS_CACHE, key = "#accountId")
    public AccountResponse topup(Long accountId, BigDecimal amount) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("account " + accountId + " not found"));
        Account funding = systemAccountService.fundingAccount(account.getCurrency());

        String key = "topup-" + UUID.randomUUID();
        String hash = PaymentService.requestHash(funding.getId(), accountId, amount, account.getCurrency());
        // eventType null: a dev cash-in is an internal ledger movement, not a merchant payment, so
        // it must not emit a payment.completed webhook.
        transferService.executeTransfer(key, funding.getId(), accountId, amount, account.getCurrency(), hash, null);

        // account is the same managed instance executeTransfer mutated; balance is already updated.
        return toResponse(account, accountRepository.derivedBalance(accountId));
    }

    /**
     * Cached read (cache-aside via {@code @Cacheable}). On a hit the method body - and its DB
     * transaction - never runs, taking read load off Postgres for a hot account. The short TTL
     * configured in {@link CacheConfig} bounds how stale the balance can be.
     */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CacheConfig.ACCOUNTS_CACHE, key = "#accountId")
    public AccountResponse getAccount(Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException("account " + accountId + " not found"));
        return toResponse(account, accountRepository.derivedBalance(accountId));
    }

    @Transactional(readOnly = true)
    public CursorPage<LedgerEntryResponse> listLedger(Long accountId, String cursor, Integer limit) {
        if (!accountRepository.existsById(accountId)) {
            throw new NotFoundException("account " + accountId + " not found");
        }
        int size = normalizeLimit(limit);
        Long cursorId = CursorCodec.decodeLedger(cursor);

        List<LedgerEntry> rows = ledgerEntryRepository.findPage(accountId, cursorId, PageRequest.of(0, size + 1));

        String next = null;
        if (rows.size() > size) {
            LedgerEntry last = rows.get(size - 1);
            next = CursorCodec.encodeLedger(last.getId());
            rows = rows.subList(0, size);
        }
        List<LedgerEntryResponse> items = new ArrayList<>(rows.size());
        for (LedgerEntry e : rows) {
            items.add(LedgerEntryResponse.from(e));
        }
        return new CursorPage<>(items, next);
    }

    private User resolveUser(String email) {
        if (email != null && !email.isBlank()) {
            return userRepository.findByEmail(email)
                    .orElseGet(() -> userRepository.save(new User(email, HashUtil.sha256Hex("key-" + UUID.randomUUID()))));
        }
        // Anonymous dev account: mint a throwaway user so the FK is satisfied.
        String generated = "dev-" + UUID.randomUUID() + "@mpg.local";
        return userRepository.save(new User(generated, HashUtil.sha256Hex("key-" + UUID.randomUUID())));
    }

    private AccountResponse toResponse(Account a, BigDecimal ledgerBalance) {
        return new AccountResponse(a.getId(), a.getUserId(), a.getCurrency(),
                a.getBalance(), ledgerBalance, a.getVersion(), a.getCreatedAt());
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
