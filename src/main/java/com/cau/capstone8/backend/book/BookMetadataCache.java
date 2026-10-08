package com.cau.capstone8.backend.book;

import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Process-local snapshots only; membership and feature availability must remain live. */
@Component
public class BookMetadataCache {
    private final BookRepository books;
    private final boolean enabled;
    private final Cache<Version, Metadata> cache;

    @Autowired
    public BookMetadataCache(BookRepository books,
            @Value("${catalog.metadata-cache.enabled:true}") boolean enabled,
            @Value("${catalog.metadata-cache.maximum-size:2000}") long maximumSize,
            @Value("${catalog.metadata-cache.ttl:PT5M}") Duration ttl) {
        this(books, enabled, maximumSize, ttl, Ticker.systemTicker());
    }

    BookMetadataCache(BookRepository books, boolean enabled, long maximumSize, Duration ttl, Ticker ticker) {
        if (maximumSize < 1 || ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("Catalog cache maximum-size and ttl must be positive");
        }
        this.books = books;
        this.enabled = enabled;
        this.cache = Caffeine.newBuilder().maximumSize(maximumSize).expireAfterWrite(ttl).ticker(ticker).build();
    }

    public Metadata get(long id) {
        if (!enabled) return load(id);
        // BookService's REPEATABLE_READ transaction keeps the version and row in one snapshot.
        // The database trigger covers direct SQL writers as well as application writes.
        Instant updatedAt = books.findMetadataVersion(id)
                .orElseThrow(() -> new ResourceNotFoundException("도서를 찾을 수 없습니다."));
        return cache.get(new Version(id, updatedAt), version -> load(version.id()));
    }

    private record Version(long id, Instant updatedAt) {}

    private Metadata load(long id) {
        Book book = books.findById(id).orElseThrow(() -> new ResourceNotFoundException("도서를 찾을 수 없습니다."));
        return new Metadata(book.getId(), book.getTitle(), book.getAuthor(), book.getDescription(), book.getIsbn(),
                book.getMlBookId(), book.getCoverUrl());
    }

    public record Metadata(long id, String title, String author, String description, String isbn,
                           String mlBookId, String coverUrl) {}
}
