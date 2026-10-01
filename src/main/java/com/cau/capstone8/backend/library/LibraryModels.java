package com.cau.capstone8.backend.library;

import jakarta.validation.constraints.*;
import java.util.List;

public final class LibraryModels {
    private LibraryModels() {}
    public enum ReadingStatus { WANT_TO_READ, READING, FINISHED }
    public enum Difficulty { EASY, APPROPRIATE, HARD }
    public record ShelfRequest(@NotNull ReadingStatus status, @NotNull @Size(max=1000) String note) {}
    public record ReviewRequest(@NotNull Difficulty difficulty, @NotBlank @Size(max=300) String text) {}
    public record OwnReview(Difficulty difficulty, String text) {}
    public record ShelfEntry(long bookId, String title, String author, ReadingStatus status,
                             String note, String updatedAt, OwnReview review) {}
    public record PublicReview(String authorLabel, Difficulty difficulty, String text, String updatedAt) {}
    public record Page<T>(List<T> content, int page, int size, long totalElements, long totalPages) {}
}
