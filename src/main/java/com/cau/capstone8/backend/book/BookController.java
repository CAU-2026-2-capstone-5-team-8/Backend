package com.cau.capstone8.backend.book;

import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/books")
public class BookController {
    private final BookService service;
    private final CatalogSummaryService summary;
    public BookController(BookService service, CatalogSummaryService summary) { this.service = service; this.summary = summary; }
    @GetMapping("/summary")
    public CatalogSummaryService.Summary summary() { return summary.summary(); }
    @GetMapping
    public BookResponse.Page list(@RequestParam(required = false) @Positive Long topicId,
                                  @RequestParam(defaultValue = "0") @Min(0) int page,
                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(topicId, page, size);
    }
    @GetMapping("/{bookId}")
    public BookResponse detail(@PathVariable @Positive long bookId) { return service.detail(bookId); }
}
