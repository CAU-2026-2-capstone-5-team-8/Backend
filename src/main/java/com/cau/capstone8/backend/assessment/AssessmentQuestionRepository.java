package com.cau.capstone8.backend.assessment;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentQuestionRepository extends JpaRepository<AssessmentQuestion, Long> {
    List<AssessmentQuestion> findBySessionIdOrderByOrderIndex(long sessionId);
}
