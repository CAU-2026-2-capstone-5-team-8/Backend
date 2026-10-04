package com.cau.capstone8.backend.account;

import static com.cau.capstone8.backend.account.AccountModels.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class AccountController {
    private final AccountService accounts;
    public AccountController(AccountService accounts) { this.accounts=accounts; }

    @PostMapping("/api/auth/register")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.CREATED)
    public Session register(@RequestBody @Valid Register request) { return accounts.register(request); }

    @PostMapping("/api/auth/login")
    @SecurityRequirements
    public Session login(@RequestBody @Valid Login request) { return accounts.login(request); }

    @PostMapping("/api/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader("Authorization") String authorization) {
        CurrentAccount.id();
        accounts.logout(authorization.substring(7));
    }

    @GetMapping("/api/me")
    public Profile profile() { return accounts.profile(CurrentAccount.id()); }

    @PutMapping("/api/me")
    public Profile update(@RequestBody @Valid Update request) {
        return accounts.update(CurrentAccount.id(),request);
    }
}
