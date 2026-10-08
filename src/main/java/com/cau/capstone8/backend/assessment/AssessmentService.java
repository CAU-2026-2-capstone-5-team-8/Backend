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

    private final QuestionTranslationService translations;
    private final AssessmentSessionRepository sessions;
    private final AssessmentQuestionRepository assessmentQuestions;
    private final AssessmentAnswerRepository answers;
    private final QuestionRepository questions;
    private final TopicRepository topics;
    private final AppUserRepository users;
    private final com.cau.capstone8.backend.topic.TopicDiagnosticState diagnosticState;

    public AssessmentService(AssessmentSessionRepository sessions, AssessmentQuestionRepository assessmentQuestions,
                              AssessmentAnswerRepository answers, QuestionRepository questions,
                              TopicRepository topics, AppUserRepository users,com.cau.capstone8.backend.topic.TopicDiagnosticState diagnosticState, QuestionTranslationService translations) {
        this.translations=translations;
        this.diagnosticState=diagnosticState;
        this.sessions = sessions;
        this.assessmentQuestions = assessmentQuestions;
        this.answers = answers;
        this.questions = questions;
        this.topics = topics;
        this.users = users;
    }

    @Transactional
    public AssessmentResponse create(long userId, long topicId) {
        return create(userId, topicId, false);
    }

    @Transactional
    public AssessmentResponse createConceptAssessment(long userId, long topicId) {
        return create(userId, topicId, true);
    }

    private AssessmentResponse create(long userId, long topicId, boolean conceptPolicy) {
        if (!users.existsById(userId)) throw new ResourceNotFoundException("사용자를 찾을 수 없습니다.");
        if (!topics.existsById(topicId)) throw new ResourceNotFoundException("분야를 찾을 수 없습니다.");

        if (!diagnosticState.ready(topicId)) throw new QuestionBankUnavailableException("선택한 분야의 문제 생성과 검토가 진행 중입니다.");

        List<Question> sampled = conceptPolicy
                ? ConceptQuestionSelection.select(questions.findByTopicIdAndActiveTrueOrderById(topicId), TOTAL_QUESTIONS,
                        questions.answeredExposure(userId, topicId).stream().collect(Collectors.toMap(
                                row -> ((Number) row[0]).longValue(),
                                row -> ((Number) row[1]).longValue())))
                : questions.sampleActiveByTopic(topicId, QUESTIONS_PER_AREA);
        // Sampling short-circuits per area; fewer than the full set means the topic's bank isn't demo-ready yet.
        if (sampled.size() != TOTAL_QUESTIONS) {
            throw new QuestionBankUnavailableException("선택한 분야에 진단 문항이 충분히 준비되어 있지 않습니다.");
        }

        AssessmentSession session = sessions.save(new AssessmentSession(userId, topicId));
        List<AssessmentQuestion> issued = new ArrayList<>();
        for (int i = 0; i < sampled.size(); i++) {
            Question q = sampled.get(i);
            issued.add(new AssessmentQuestion(
                    session.getId(), q.getId(), i, q.getMeasurementArea(),
                    q.getPassage(), q.getPrompt(), q.getConceptId(), q.getVersion(), q.getDifficulty(),
                    q.getAnswerMode(), q.getGeneratedQuestionId(), q.getQuestionSpecId(),
                    q.getChoices(), q.getCorrectChoiceIndex(), q.getExplanation(),
                    q.getGeneratedContentHash(), q.getUpstreamProvenance()));
        }
        assessmentQuestions.saveAll(issued);

        return toResponse(session, issued, Map.of());
    }

    public AssessmentResponse get(long sessionId) {
        AssessmentSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("진단 세션을 찾을 수 없습니다."));
        List<AssessmentQuestion> issued = assessmentQuestions.findBySessionIdOrderByOrderIndex(sessionId);
        Map<Long, AssessmentAnswer> answerByQuestionId = answers
                .findByAssessmentQuestionIdIn(issued.stream().map(AssessmentQuestion::getId).toList()).stream()
                .collect(Collectors.toMap(AssessmentAnswer::getAssessmentQuestionId, answer -> answer));
        return toResponse(session, issued, answerByQuestionId);
    }

    @Transactional
    public AssessmentResponse.IssuedQuestion answer(
            long sessionId,
            long assessmentQuestionId,
            AssessmentAnswerRequest request) {
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

        AssessmentAnswer saved = saveAnswer(question, request);

        return toIssuedQuestion(question, saved);
    }

    private AssessmentResponse toResponse(AssessmentSession session, List<AssessmentQuestion> issued,
                                           Map<Long, AssessmentAnswer> answerByQuestionId) {
        List<AssessmentResponse.IssuedQuestion> issuedQuestions = issued.stream()
                .map(question -> toIssuedQuestion(question, answerByQuestionId.get(question.getId())))
                .toList();
        return new AssessmentResponse(session.getId(), session.getUserId(), session.getTopicId(),
                session.getStatus().name(), issuedQuestions);
    }

    private AssessmentAnswer saveAnswer(
            AssessmentQuestion question,
            AssessmentAnswerRequest request) {
        var existing = answers.findByAssessmentQuestionId(question.getId());
        if (question.getAnswerModeSnapshot() == AnswerMode.SELF_REPORT) {
            if (request.getKnowsConcept() == null || request.getSelectedChoiceIndex() != null) {
                throw new InvalidAssessmentAnswerException("자기평가 문항에는 knowsConcept 답변이 필요합니다.");
            }
            if (existing.isPresent()) {
                existing.get().updateSelfReport(request.getKnowsConcept());
                return existing.get();
            }
            return answers.save(AssessmentAnswer.selfReport(
                    question.getId(), request.getKnowsConcept()));
        }

        if (request.getSelectedChoiceIndex() == null || request.getKnowsConcept() != null) {
            throw new InvalidAssessmentAnswerException("객관식 문항에는 selectedChoiceIndex 답변이 필요합니다.");
        }
        int selectedIndex = request.getSelectedChoiceIndex();
        boolean correct = selectedIndex == question.getCorrectChoiceIndexSnapshot();
        if (existing.isPresent()) {
            existing.get().updateMultipleChoice(selectedIndex, correct);
            return existing.get();
        }
        return answers.save(AssessmentAnswer.multipleChoice(question.getId(), selectedIndex, correct));
    }

    private AssessmentResponse.IssuedQuestion toIssuedQuestion(
            AssessmentQuestion question,
            AssessmentAnswer answer) {
        List<String> choices = question.getChoicesSnapshot() == null
                ? List.of() : question.getChoicesSnapshot();
        return new AssessmentResponse.IssuedQuestion(
                question.getId(),
                question.getOrderIndex(),
                question.getMeasurementAreaSnapshot().name(),
                question.getConceptIdSnapshot(),
                question.getPassageSnapshot(),
                question.getPromptSnapshot(),
                question.getAnswerModeSnapshot().name(),
                question.getCognitiveOperationSnapshot(),
                question.getMeasurementContextSnapshot(),
                choices,
                answer == null ? null : answer.getKnowsConcept(),
                answer == null ? null : answer.getSelectedChoiceIndex(),
                translations.display(question));
    }
}
