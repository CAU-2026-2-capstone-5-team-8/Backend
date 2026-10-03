package com.cau.capstone8.backend.book;

import java.util.List;

public record BookResponse(long id, String title, String author, String description, String isbn, String mlBookId,
                           List<TopicMembership> topics) {
    public record TopicMembership(long id, String code, String name, boolean primary,
                                  double weight, boolean featureAvailable, Integer tocEntryCount,
                                  boolean rankingCandidate, Integer coveredConceptCount) {}
    public record Page(List<BookResponse> content, int page, int size, long totalElements, long totalPages) {}
}
