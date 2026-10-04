package com.cau.capstone8.backend.account;

import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Component;

/** Additional account bucket across source IPs; bounded and local to this JVM. */
@Component
public final class CredentialThrottle {
    private final Clock clock;
    private final Map<String, Window> attempts = new HashMap<>();
    public CredentialThrottle() { this(Clock.systemUTC()); }
    CredentialThrottle(Clock clock) { this.clock = clock; }
    public synchronized void check(String email) {
        long now = clock.instant().getEpochSecond();
        attempts.entrySet().removeIf(e -> now - e.getValue().start() >= 60);
        // Do not retain raw email addresses in the limiter.
        String key = AccountService.hashToken(email.strip().toLowerCase(Locale.ROOT));
        Window prior = attempts.get(key);
        if (prior == null && attempts.size() >= 10_000) reject();
        int count = prior == null ? 1 : Math.min(11, prior.count() + 1);
        attempts.put(key, new Window(prior == null ? now : prior.start(), count));
        if (count > 10) reject();
    }
    private void reject() { throw new AccountException(429, "AUTH_RATE_LIMIT", "요청이 많습니다. 잠시 후 다시 시도해 주세요."); }
    private record Window(long start, int count) {}
}
