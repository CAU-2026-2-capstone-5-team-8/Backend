package com.cau.capstone8.backend.assessment;

import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import com.cau.capstone8.backend.integration.ml.*;
import com.cau.capstone8.backend.topic.TopicRepository;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AssessmentDiagnosticsService {
    private final AssessmentSessionRepository sessions;
    private final AssessmentQuestionRepository questions;
    private final AssessmentAnswerRepository answers;
    private final TopicRepository topics;
    private final MlGateway gateway;
    private final TransactionTemplate readTransaction;

    public AssessmentDiagnosticsService(AssessmentSessionRepository sessions,
            AssessmentQuestionRepository questions, AssessmentAnswerRepository answers,
            TopicRepository topics, MlGateway gateway, PlatformTransactionManager transactionManager) {
        this.sessions = sessions;
        this.questions = questions;
        this.answers = answers;
        this.topics = topics;
        this.gateway = gateway;
        readTransaction = new TransactionTemplate(transactionManager);
        readTransaction.setReadOnly(true);
    }

    public record Response(long sessionId, MlReaderDiagnostics diagnostics, List<ResponseSource> responseSources) {}
    public record ResponseSource(String questionId, long assessmentQuestionId, String answerMode) {}
    private record Input(MlProfileRequest request, List<ResponseSource> sources) {}

    public Response get(long sessionId) {
        Input input = Objects.requireNonNull(readTransaction.execute(status -> load(sessionId)));
        // Remote calculation happens after the read transaction; it never changes completion/profile state.
        return new Response(sessionId, gateway.readerDiagnostics(input.request()), input.sources());
    }

    private Input load(long sessionId) {
        var session = sessions.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("진단 세션을 찾을 수 없습니다."));
        if (session.getStatus() != AssessmentStatus.COMPLETED) {
            throw new AssessmentStateConflictException("완료한 진단에서만 진단 근거를 조회할 수 있습니다.");
        }
        var issued = questions.findBySessionIdOrderByOrderIndex(sessionId);
        var byQuestion = answers.findByAssessmentQuestionIdIn(issued.stream().map(AssessmentQuestion::getId).toList())
                .stream().collect(Collectors.toMap(AssessmentAnswer::getAssessmentQuestionId, Function.identity()));
        if (issued.isEmpty() || issued.stream().anyMatch(q -> !byQuestion.containsKey(q.getId()))) {
            throw new AssessmentStateConflictException("진단 응답 근거가 완전하지 않습니다.");
        }
        var topic = topics.findById(session.getTopicId())
                .orElseThrow(() -> new ResourceNotFoundException("진단 분야를 찾을 수 없습니다."));
        var mlAnswers = issued.stream().map(q -> new MlProfileRequest.Answer(
                questionId(q), q.getMeasurementAreaSnapshot(), q.getConceptIdSnapshot(),
                q.getDifficultySnapshot(), byQuestion.get(q.getId()).resultForMl(), 1.0)).toList();
        var sources = issued.stream().map(q -> new ResponseSource(
                questionId(q), q.getId(), q.getAnswerModeSnapshot().name())).toList();
        return new Input(new MlProfileRequest(UUID.randomUUID(), "v1", session.getUserId(),
                Long.toString(sessionId), topic.getCode(), mlAnswers), sources);
    }

    private static String questionId(AssessmentQuestion question) {
        return question.getGeneratedQuestionIdSnapshot() == null
                ? question.getId().toString() : question.getGeneratedQuestionIdSnapshot();
    }
}
