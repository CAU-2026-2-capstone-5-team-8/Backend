package com.cau.capstone8.backend.book;

import jakarta.persistence.*;

@Entity
@Table(name = "book")
public class Book {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 300) private String title;
    @Column(nullable = false, length = 200) private String author;
    @Column(nullable = false, columnDefinition = "text") private String description;
    @Column(length = 20) private String isbn;
    protected Book() {}
    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public String getDescription() { return description; }
    public String getIsbn() { return isbn; }
}
