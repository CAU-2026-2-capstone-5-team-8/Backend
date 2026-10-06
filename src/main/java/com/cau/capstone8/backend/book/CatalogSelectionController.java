package com.cau.capstone8.backend.book;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogSelectionController {
    private final CatalogSelectionService service;
    public CatalogSelectionController(CatalogSelectionService service) { this.service=service; }
    @GetMapping("/api/books/selection")
    public List<Map<String,Object>> current() { return service.current(); }
}
