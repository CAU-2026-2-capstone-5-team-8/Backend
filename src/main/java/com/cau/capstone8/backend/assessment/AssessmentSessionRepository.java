package com.cau.capstone8.backend.assessment;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssessmentSessionRepository extends JpaRepository<AssessmentSession, Long> {
    // Answer writes and complete claims lock the same session row in a short transaction (design.md).
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from AssessmentSession s where s.id = :id")
    Optional<AssessmentSession> findByIdForUpdate(@Param("id") long id);
}
