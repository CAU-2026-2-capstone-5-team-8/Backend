package com.cau.capstone8.backend.book;

import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/books")
public class BookController {
    private final BookService service;
    public BookController(BookService service) { this.service = service; }
    @GetMapping
    public BookResponse.Page list(@RequestParam(required = false) @Positive Long topicId,
                                  @RequestParam(defaultValue = "0") @Min(0) int page,
                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(topicId, page, size);
    }
    @GetMapping("/{bookId}")
    public BookResponse detail(@PathVariable @Positive long bookId) { return service.detail(bookId); }
}
