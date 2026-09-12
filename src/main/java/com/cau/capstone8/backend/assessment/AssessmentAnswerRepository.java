package com.cau.capstone8.backend.assessment;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssessmentAnswerRepository extends JpaRepository<AssessmentAnswer, Long> {
    Optional<AssessmentAnswer> findByAssessmentQuestionId(long assessmentQuestionId);
}
