package com.cau.capstone8.backend.topic;

import jakarta.persistence.*;

@Entity
@Table(name = "topic")
public class Topic {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 80) private String code;
    @Column(nullable = false, length = 120) private String name;
    @Column(name = "ml_topic_id", length = 120) private String mlTopicId;
    @Column(name = "parent_id") private Long parentId;
    protected Topic() {}
    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getMlTopicId() { return mlTopicId; }
    public Long getParentId() { return parentId; }
}
