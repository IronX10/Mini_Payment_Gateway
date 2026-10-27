package com.dixansh.mini_payment_gateway.web;

import com.dixansh.mini_payment_gateway.dto.AccountResponse;
import com.dixansh.mini_payment_gateway.dto.CreateAccountRequest;
import com.dixansh.mini_payment_gateway.dto.CursorPage;
import com.dixansh.mini_payment_gateway.dto.LedgerEntryResponse;
import com.dixansh.mini_payment_gateway.dto.TopupRequest;
import com.dixansh.mini_payment_gateway.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest req) {
        AccountResponse created = accountService.createAccount(req.email(), req.currency());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** Dev-only helper to seed test money. */
    @PostMapping("/{id}/topup")
    public AccountResponse topup(@PathVariable Long id, @Valid @RequestBody TopupRequest req) {
        return accountService.topup(id, req.amount());
    }

    @GetMapping("/{id}")
    public AccountResponse get(@PathVariable Long id) {
        return accountService.getAccount(id);
    }

    @GetMapping("/{id}/ledger")
    public CursorPage<LedgerEntryResponse> ledger(@PathVariable Long id,
                                                  @RequestParam(required = false) String cursor,
                                                  @RequestParam(required = false) Integer limit) {
        return accountService.listLedger(id, cursor, limit);
    }
}
