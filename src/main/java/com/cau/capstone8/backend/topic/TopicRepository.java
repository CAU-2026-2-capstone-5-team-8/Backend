package com.cau.capstone8.backend.topic;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TopicRepository extends JpaRepository<Topic, Long> {
    List<Topic> findAllByOrderByIdAsc();
}
