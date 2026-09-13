package com.cau.capstone8.backend.assessment;

import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import com.cau.capstone8.backend.topic.TopicRepository;
import com.cau.capstone8.backend.user.AppUserRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class AssessmentService {
    private static final int QUESTIONS_PER_AREA = 3;
    private static final int TOTAL_QUESTIONS = QUESTIONS_PER_AREA * MeasurementArea.values().length;

    private final AssessmentSessionRepository sessions;
    private final AssessmentQuestionRepository assessmentQuestions;
    private final QuestionRepository questions;
    private final TopicRepository topics;
    private final AppUserRepository users;
    private final ObjectMapper json;

    public AssessmentService(AssessmentSessionRepository sessions, AssessmentQuestionRepository assessmentQuestions,
                              QuestionRepository questions, TopicRepository topics, AppUserRepository users,
                              ObjectMapper json) {
        this.sessions = sessions;
        this.assessmentQuestions = assessmentQuestions;
        this.questions = questions;
        this.topics = topics;
        this.users = users;
        this.json = json;
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
            issued.add(new AssessmentQuestion(session.getId(), q.getId(), i, q.getPrompt(), q.getOptions(),
                    q.getCorrectOptionId(), q.getVersion()));
        }
        assessmentQuestions.saveAll(issued);

        return toResponse(session, issued);
    }

    private AssessmentResponse toResponse(AssessmentSession session, List<AssessmentQuestion> issued) {
        List<AssessmentResponse.IssuedQuestion> issuedQuestions = issued.stream()
                .map(q -> new AssessmentResponse.IssuedQuestion(q.getId(), q.getOrderIndex(), q.getPromptSnapshot(),
                        json.readTree(q.getOptionsSnapshot())))
                .toList();
        return new AssessmentResponse(session.getId(), session.getUserId(), session.getTopicId(),
                session.getStatus().name(), issuedQuestions);
    }
}
