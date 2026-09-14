package com.cau.capstone8.backend.assessment;

import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import com.cau.capstone8.backend.topic.TopicRepository;
import com.cau.capstone8.backend.user.AppUserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AssessmentService {
    private static final int QUESTIONS_PER_AREA = 3;
    private static final int TOTAL_QUESTIONS = QUESTIONS_PER_AREA * MeasurementArea.values().length;

    private final AssessmentSessionRepository sessions;
    private final AssessmentQuestionRepository assessmentQuestions;
    private final AssessmentAnswerRepository answers;
    private final QuestionRepository questions;
    private final TopicRepository topics;
    private final AppUserRepository users;

    public AssessmentService(AssessmentSessionRepository sessions, AssessmentQuestionRepository assessmentQuestions,
                              AssessmentAnswerRepository answers, QuestionRepository questions,
                              TopicRepository topics, AppUserRepository users) {
        this.sessions = sessions;
        this.assessmentQuestions = assessmentQuestions;
        this.answers = answers;
        this.questions = questions;
        this.topics = topics;
        this.users = users;
    }

    @Transactional
    public AssessmentResponse create(long userId, long topicId) {
        if (!users.existsById(userId)) throw new ResourceNotFoundException("사용자를 찾을 수 없습니다.");
        if (!topics.existsById(topicId)) throw new ResourceNotFoundException("분야를 찾을 수 없습니다.");

        List<Question> sampled = questions.sampleActiveByTopic(topicId, QUESTIONS_PER_AREA);
        // Sampling short-circuits per area; fewer than the full set means the topic's bank isn't demo-ready yet.
        if (sampled.size() != TOTAL_QUESTIONS) {
            throw new QuestionBankUnavailableException("선택한 분야에 진단 문항이 충분히 준비되어 있지 않습니다.");
        }

        AssessmentSession session = sessions.save(new AssessmentSession(userId, topicId));
        List<AssessmentQuestion> issued = new ArrayList<>();
        for (int i = 0; i < sampled.size(); i++) {
            Question q = sampled.get(i);
            issued.add(new AssessmentQuestion(session.getId(), q.getId(), i, q.getMeasurementArea(), q.getPrompt(), q.getVersion()));
        }
        assessmentQuestions.saveAll(issued);

        return toResponse(session, issued, Map.of());
    }

    public AssessmentResponse get(long sessionId) {
        AssessmentSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("진단 세션을 찾을 수 없습니다."));
        List<AssessmentQuestion> issued = assessmentQuestions.findBySessionIdOrderByOrderIndex(sessionId);
        Map<Long, Boolean> knownByQuestionId = answers
                .findByAssessmentQuestionIdIn(issued.stream().map(AssessmentQuestion::getId).toList()).stream()
                .collect(Collectors.toMap(AssessmentAnswer::getAssessmentQuestionId, AssessmentAnswer::isKnowsConcept));
        return toResponse(session, issued, knownByQuestionId);
    }

    @Transactional
    public AssessmentResponse.IssuedQuestion answer(long sessionId, long assessmentQuestionId, boolean knowsConcept) {
        // Lock the session row so a concurrent answer write or completion can't race this update (design.md).
        AssessmentSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("진단 세션을 찾을 수 없습니다."));
        AssessmentQuestion question = assessmentQuestions.findById(assessmentQuestionId)
                .filter(q -> q.getSessionId().equals(sessionId))
                .orElseThrow(() -> new ResourceNotFoundException("발급된 문항을 찾을 수 없습니다."));

        if (session.getStatus() == AssessmentStatus.PROCESSING || session.getStatus() == AssessmentStatus.COMPLETED) {
            throw new AssessmentStateConflictException("완료되었거나 처리 중인 세션은 답변을 수정할 수 없습니다.");
        }
        if (session.getStatus() == AssessmentStatus.CREATED) {
            session.setStatus(AssessmentStatus.IN_PROGRESS);
        }

        var existing = answers.findByAssessmentQuestionId(assessmentQuestionId);
        if (existing.isPresent()) {
            existing.get().setKnowsConcept(knowsConcept);
        } else {
            answers.save(new AssessmentAnswer(assessmentQuestionId, knowsConcept));
        }

        return new AssessmentResponse.IssuedQuestion(question.getId(), question.getOrderIndex(),
                question.getMeasurementAreaSnapshot().name(), question.getPromptSnapshot(), knowsConcept);
    }

    private AssessmentResponse toResponse(AssessmentSession session, List<AssessmentQuestion> issued,
                                           Map<Long, Boolean> knownByQuestionId) {
        List<AssessmentResponse.IssuedQuestion> issuedQuestions = issued.stream()
                .map(q -> new AssessmentResponse.IssuedQuestion(q.getId(), q.getOrderIndex(),
                        q.getMeasurementAreaSnapshot().name(), q.getPromptSnapshot(), knownByQuestionId.get(q.getId())))
                .toList();
        return new AssessmentResponse(session.getId(), session.getUserId(), session.getTopicId(),
                session.getStatus().name(), issuedQuestions);
    }
}
