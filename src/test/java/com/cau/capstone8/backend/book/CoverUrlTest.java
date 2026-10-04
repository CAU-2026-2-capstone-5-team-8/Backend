package com.cau.capstone8.backend.book;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class CoverUrlTest {
    @Test void acceptsExactHttpsImageOnlyWithEligibleProvenance() {
        assertThat(CoverUrl.reviewed("https://images.example.org/book.jpg?size=large", "https://publisher.example.org/book"))
                .isEqualTo("https://images.example.org/book.jpg?size=large");
    }

    @Test void rejectsIneligibleImageOrSourceWithoutNetworkRequests() {
        for (String invalid : new String[]{null, "", "http://images.example.org/book.jpg", "javascript:alert(1)",
                "data:image/png;base64,AAAA", "//images.example.org/book.jpg", "https://user:secret@example.org/book.jpg",
                "https://example.org/book.jpg#fragment", "https://example.org:8443/book.jpg", "https://example.org/a b.jpg",
                "https://localhost/book.jpg", "https://service.localhost/book.jpg", "https://127.0.0.1/book.jpg",
                "https://[::1]/book.jpg", "https://example.org/" + "a".repeat(2048)}) {
            assertThat(CoverUrl.reviewed(invalid, "https://publisher.example.org/book")).as("image: %s", invalid).isNull();
            assertThat(CoverUrl.reviewed("https://images.example.org/book.jpg", invalid)).as("source: %s", invalid).isNull();
        }
    }
}
