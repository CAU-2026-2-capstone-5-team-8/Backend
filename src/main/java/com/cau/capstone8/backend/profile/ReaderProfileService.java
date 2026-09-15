package com.cau.capstone8.backend.profile;

import com.cau.capstone8.backend.assessment.AssessmentSession;
import com.cau.capstone8.backend.assessment.AssessmentSessionRepository;
import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ReaderProfileService {
    private final ReaderProfileRepository profiles;
    private final AssessmentSessionRepository sessions;

    public ReaderProfileService(
            ReaderProfileRepository profiles,
            AssessmentSessionRepository sessions) {
        this.profiles = profiles;
        this.sessions = sessions;
    }

    public ReaderProfileResponse latest(long userId, long topicId) {
        ReaderProfile profile = profiles.findLatestCompleted(userId, topicId)
                .orElseThrow(() -> new ResourceNotFoundException("완료된 독자 프로필을 찾을 수 없습니다."));
        AssessmentSession session = sessions.findById(profile.getSessionId())
                .orElseThrow(() -> new IllegalStateException("프로필의 진단 세션을 찾을 수 없습니다."));
        return toResponse(profile, session);
    }

    public static ReaderProfileResponse toResponse(
            ReaderProfile profile,
            AssessmentSession session) {
        return new ReaderProfileResponse(
                profile.getId(),
                profile.getSessionId(),
                session.getUserId(),
                session.getTopicId(),
                profile.getVocabulary(),
                profile.getBackgroundKnowledge(),
                profile.getComprehension(),
                profile.getCalculationVersion(),
                profile.getEvidence(),
                session.getCompletedAt());
    }
}
