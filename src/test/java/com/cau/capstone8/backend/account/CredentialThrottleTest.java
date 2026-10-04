package com.cau.capstone8.backend.account;

import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CredentialThrottleTest {
    @Test void rotatingIpsCannotBypassTheSameNormalizedAccountLimit() {
        var time = mock(Clock.class);
        when(time.instant()).thenReturn(Instant.parse("2026-10-05T00:00:00Z"));
        var throttle = new CredentialThrottle(time);
        for (int i=0;i<10;i++) throttle.check(i%2==0 ? "Alice@Example.com" : " alice@example.com ");
        assertThatThrownBy(() -> throttle.check("ALICE@example.com"))
                .isInstanceOfSatisfying(AccountException.class, e -> assertThat(e.status()).isEqualTo(429));
        assertThatCode(() -> throttle.check("bob@example.com")).doesNotThrowAnyException();
        when(time.instant()).thenReturn(Instant.parse("2026-10-05T00:01:00Z"));
        assertThatCode(() -> throttle.check("alice@example.com")).doesNotThrowAnyException();
    }
}
