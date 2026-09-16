package com.cau.capstone8.backend.book;

import jakarta.persistence.*;

@Entity
@Table(name = "book_feature")
public class BookFeature {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "book_id", nullable = false) private Long bookId;
    @Column(name = "topic_id", nullable = false) private Long topicId;
    @Column(nullable = false, length = 80) private String version;
    @Column(nullable = false) private boolean active;
    @Column(nullable = false) private double vocabulary;
    @Column(nullable = false) private double knowledge;
    @Column(nullable = false) private double comprehension;
    @Column(name = "topic_relevance", nullable = false) private double topicRelevance;

    protected BookFeature() {}

    public Long getId() { return id; }
    public Long getBookId() { return bookId; }
    public Long getTopicId() { return topicId; }
    public String getVersion() { return version; }
    public boolean isActive() { return active; }
    public double getVocabulary() { return vocabulary; }
    public double getKnowledge() { return knowledge; }
    public double getComprehension() { return comprehension; }
    public double getTopicRelevance() { return topicRelevance; }
}
