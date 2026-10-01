package com.cau.capstone8.backend.library;

import static com.cau.capstone8.backend.library.LibraryModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Prototype user IDs, consistent with assessment APIs. Not an authentication boundary. */
@RestController
@RequestMapping("/api")
public class LibraryController {
    private final LibraryService service;
    public LibraryController(LibraryService service) { this.service=service; }
    @GetMapping("/users/{userId}/shelf")
    public Page<ShelfEntry> shelf(@PathVariable @Positive long userId,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
            @RequestParam(required=false) ReadingStatus status) { return service.shelf(userId,page,size,status); }
    @PostMapping("/users/{userId}/shelf/{bookId}")
    public ShelfEntry add(@PathVariable @Positive long userId,@PathVariable @Positive long bookId) { return service.add(userId,bookId); }
    @PutMapping("/users/{userId}/shelf/{bookId}")
    public ShelfEntry save(@PathVariable @Positive long userId,@PathVariable @Positive long bookId,@RequestBody @Valid ShelfRequest request) { return service.save(userId,bookId,request); }
    @DeleteMapping("/users/{userId}/shelf/{bookId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable @Positive long userId,@PathVariable @Positive long bookId) { service.remove(userId,bookId); }
    @PutMapping("/users/{userId}/reviews/{bookId}")
    public OwnReview review(@PathVariable @Positive long userId,@PathVariable @Positive long bookId,@RequestBody @Valid ReviewRequest request) { return service.saveReview(userId,bookId,request); }
    @DeleteMapping("/users/{userId}/reviews/{bookId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeReview(@PathVariable @Positive long userId,@PathVariable @Positive long bookId) { service.removeReview(userId,bookId); }
    @GetMapping("/books/{bookId}/reviews")
    public Page<PublicReview> reviews(@PathVariable @Positive long bookId,
            @RequestParam(defaultValue="0") @Min(0) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return service.reviews(bookId,page,size); }
}
