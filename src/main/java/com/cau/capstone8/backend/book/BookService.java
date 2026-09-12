package com.cau.capstone8.backend.book;

import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import com.cau.capstone8.backend.topic.TopicRepository;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class BookService {
    private final BookRepository books;
    private final TopicRepository topics;
    public BookService(BookRepository books, TopicRepository topics) { this.books = books; this.topics = topics; }

    public BookResponse.Page list(Long topicId, int page, int size) {
        if (topicId != null && !topics.existsById(topicId)) throw new ResourceNotFoundException("분야를 찾을 수 없습니다.");
        long total = books.countCatalog(topicId);
        long offset = (long) page * size;
        List<Book> rows = offset >= total ? List.of() : books.findCatalog(topicId, size, offset);
        return new BookResponse.Page(map(rows), page, size, total, total / size + (total % size == 0 ? 0 : 1));
    }

    public BookResponse detail(long id) {
        return map(List.of(books.findById(id).orElseThrow(
                () -> new ResourceNotFoundException("도서를 찾을 수 없습니다.")))).getFirst();
    }

    private List<BookResponse> map(List<Book> rows) {
        if (rows.isEmpty()) return List.of();
        var membership = books.findMemberships(rows.stream().map(Book::getId).toList()).stream()
                .collect(Collectors.groupingBy(BookRepository.TopicMembership::getBookId,
                        Collectors.mapping(t -> new BookResponse.TopicMembership(t.getTopicId(), t.getCode(),
                                t.getName(), t.getPrimary(), t.getWeight(), t.getFeatureAvailable()), Collectors.toList())));
        return rows.stream().map(b -> new BookResponse(b.getId(), b.getTitle(), b.getAuthor(), b.getDescription(),
                b.getIsbn(), membership.getOrDefault(b.getId(), List.of()))).toList();
    }
}
