package com.cau.capstone8.backend.assessment;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "question")
public class Question {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "topic_id", nullable = false) private Long topicId;
    @Enumerated(EnumType.STRING)
    @Column(name = "measurement_area", nullable = false, length = 40) private MeasurementArea measurementArea;
    @Column(nullable = false) private int difficulty;
    @Column(nullable = false, columnDefinition = "text") private String prompt;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb") private String options;
    @Column(name = "correct_option_id", nullable = false, length = 20) private String correctOptionId;
    @Column(nullable = false, length = 80) private String version;
    @Column(nullable = false) private boolean active;

    protected Question() {}

    public Long getId() { return id; }
    public Long getTopicId() { return topicId; }
    public MeasurementArea getMeasurementArea() { return measurementArea; }
    public int getDifficulty() { return difficulty; }
    public String getPrompt() { return prompt; }
    public String getOptions() { return options; }
    public String getCorrectOptionId() { return correctOptionId; }
    public String getVersion() { return version; }
    public boolean isActive() { return active; }
}
