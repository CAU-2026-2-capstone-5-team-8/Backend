package com.cau.capstone8.backend.account;

import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentAccount {
    private CurrentAccount() {}
    public static Long optionalId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof Long id ? id : null;
    }
    public static long id() {
        Long id = optionalId();
        if (id == null) throw new AccountException(401,"AUTH_REQUIRED","로그인이 필요합니다.");
        return id;
    }
}
