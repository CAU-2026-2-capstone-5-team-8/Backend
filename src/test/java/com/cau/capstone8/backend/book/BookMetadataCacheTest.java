package com.cau.capstone8.backend.book;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

class BookMetadataCacheTest {
    final BookRepository repository = mock(BookRepository.class);
    final AtomicLong clock = new AtomicLong();

    @BeforeEach void existingVersion() {
        when(repository.findMetadataVersion(anyLong())).thenReturn(Optional.of(Instant.EPOCH));
    }

    Book book(long id, String title) {
        Book book = mock(Book.class);
        when(book.getId()).thenReturn(id);
        when(book.getTitle()).thenReturn(title);
        when(book.getAuthor()).thenReturn("Author");
        when(book.getDescription()).thenReturn("Description");
        return book;
    }

    @Test void cachesAnImmutableSnapshotAndReloadsAfterExpiry() {
        var original = book(1, "Original");
        var updated = book(1, "Updated");
        when(repository.findById(1L)).thenReturn(Optional.of(original)).thenReturn(Optional.of(updated));
        var cache = new BookMetadataCache(repository, true, 10, Duration.ofMinutes(5), clock::get);
        assertThat(cache.get(1).title()).isEqualTo("Original");
        when(original.getTitle()).thenReturn("Mutated entity");
        clock.set(Duration.ofMinutes(4).toNanos());
        assertThat(cache.get(1).title()).isEqualTo("Original");
        verify(repository, times(1)).findById(1L);
        clock.set(Duration.ofMinutes(5).toNanos());
        assertThat(cache.get(1).title()).isEqualTo("Updated");
        verify(repository, times(2)).findById(1L);
    }

    @Test void disabledCacheAlwaysReadsDatabase() {
        var one = book(1, "One");
        var two = book(1, "Two");
        when(repository.findById(1L)).thenReturn(Optional.of(one)).thenReturn(Optional.of(two));
        var cache = new BookMetadataCache(repository, false, 10, Duration.ofMinutes(5), clock::get);
        assertThat(cache.get(1).title()).isEqualTo("One");
        assertThat(cache.get(1).title()).isEqualTo("Two");
        verify(repository, never()).findMetadataVersion(anyLong());
    }

    @Test void olderSnapshotCannotReplaceNewerCachedVersion() {
        var old = book(1, "Old snapshot");
        var current = book(1, "Current snapshot");
        when(repository.findMetadataVersion(1L)).thenReturn(Optional.of(Instant.EPOCH.plusSeconds(1)),
                Optional.of(Instant.EPOCH), Optional.of(Instant.EPOCH.plusSeconds(1)));
        when(repository.findById(1L)).thenReturn(Optional.of(current), Optional.of(old));
        var cache = new BookMetadataCache(repository, true, 10, Duration.ofMinutes(5), clock::get);
        assertThat(cache.get(1).title()).isEqualTo("Current snapshot");
        assertThat(cache.get(1).title()).isEqualTo("Old snapshot");
        assertThat(cache.get(1).title()).isEqualTo("Current snapshot");
        verify(repository, times(2)).findById(1L);
    }

    @Test void cachedMetadataDoesNotHideVersionLookupFailureOrDeletion() {
        var row = book(1, "Cached");
        when(repository.findById(1L)).thenReturn(Optional.of(row));
        when(repository.findMetadataVersion(1L)).thenReturn(Optional.of(Instant.EPOCH))
                .thenThrow(new IllegalStateException("DB unavailable")).thenReturn(Optional.empty());
        var cache = new BookMetadataCache(repository, true, 10, Duration.ofMinutes(5), clock::get);
        assertThat(cache.get(1).title()).isEqualTo("Cached");
        assertThatThrownBy(() -> cache.get(1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cache.get(1)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test void missingBooksAndDatabaseFailuresAreNotCached() {
        var recovered = book(1, "Recovered");
        when(repository.findById(1L)).thenReturn(Optional.empty()).thenThrow(new IllegalStateException("DB unavailable"))
                .thenReturn(Optional.of(recovered));
        var cache = new BookMetadataCache(repository, true, 10, Duration.ofMinutes(5), clock::get);
        assertThatThrownBy(() -> cache.get(1)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> cache.get(1)).isInstanceOf(IllegalStateException.class);
        assertThat(cache.get(1).title()).isEqualTo("Recovered");
    }

    @Test void rejectsUnboundedOrInvalidConfiguration() {
        assertThatIllegalArgumentException().isThrownBy(() -> new BookMetadataCache(repository, true, 0, Duration.ofMinutes(1), clock::get));
        assertThatIllegalArgumentException().isThrownBy(() -> new BookMetadataCache(repository, true, 10, Duration.ZERO, clock::get));
        assertThatIllegalArgumentException().isThrownBy(() -> new BookMetadataCache(repository, true, 10, Duration.ofSeconds(-1), clock::get));
    }

    @Test void concurrentRequestsShareOneLoad() throws Exception {
        var row = book(1, "Shared");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(repository.findById(1L)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
            return Optional.of(row);
        });
        var cache = new BookMetadataCache(repository, true, 10, Duration.ofMinutes(5), clock::get);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> cache.get(1));
            try {
                assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> cache.get(1));
                release.countDown();
                assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS)).isSameAs(second.get(5, java.util.concurrent.TimeUnit.SECONDS));
                verify(repository, times(1)).findById(1L);
            } finally { release.countDown(); }
        }
    }
}
