package com.cau.capstone8.backend.assessment;

import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import com.cau.capstone8.backend.integration.ml.MlGateway;
import com.cau.capstone8.backend.integration.ml.MlGatewayException;
import com.cau.capstone8.backend.integration.ml.MlProfileRequest;
import com.cau.capstone8.backend.integration.ml.MlProfileResponseValidator;
import com.cau.capstone8.backend.integration.ml.MlProfileResult;
import com.cau.capstone8.backend.profile.ReaderProfile;
import com.cau.capstone8.backend.profile.ReaderProfileRepository;
import com.cau.capstone8.backend.profile.ReaderProfileService;
import com.cau.capstone8.backend.topic.TopicRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AssessmentCompletionService {
    private static final int REQUIRED_QUESTION_COUNT = 9;
    private static final String CONTRACT_VERSION = "v1";
    private static final String FAILURE_MESSAGE = "ML 프로필 계산에 실패했습니다.";

    private final AssessmentSessionRepository sessions;
    private final AssessmentQuestionRepository assessmentQuestions;
    private final AssessmentAnswerRepository answers;
    private final ReaderProfileRepository profiles;
    private final TopicRepository topics;
    private final MlGateway mlGateway;
    private final TransactionTemplate transactions;
    private final Duration processingLease;

    public AssessmentCompletionService(
            AssessmentSessionRepository sessions,
            AssessmentQuestionRepository assessmentQuestions,
            AssessmentAnswerRepository answers,
            ReaderProfileRepository profiles,
            TopicRepository topics,
            MlGateway mlGateway,
            PlatformTransactionManager transactionManager,
            @Value("${assessment.processing-lease:PT30S}") Duration processingLease) {
        this.sessions = sessions;
        this.assessmentQuestions = assessmentQuestions;
        this.answers = answers;
        this.profiles = profiles;
        this.topics = topics;
        this.mlGateway = mlGateway;
        this.transactions = new TransactionTemplate(transactionManager);
        if (processingLease.isZero() || processingLease.isNegative()) {
            throw new IllegalArgumentException("assessment processing lease must be positive");
        }
        this.processingLease = processingLease;
    }

    public AssessmentCompletionResponse complete(long sessionId) {
        CompletionClaim claim = transactions.execute(status -> claim(sessionId));
        if (claim == null) {
            throw new IllegalStateException("진단 완료 트랜잭션 결과가 없습니다.");
        }
        if (claim.completedResponse() != null) {
            return claim.completedResponse();
        }

        MlProfileResult result;
        try {
            result = MlProfileResponseValidator.validate(
                    claim.request(), mlGateway.calculateProfile(claim.request()));
        } catch (RuntimeException exception) {
            recover(claim.attemptId(), sessionId, exception);
            if (exception instanceof MlGatewayException gatewayException) {
                throw gatewayException;
            }
            throw new MlGatewayException("ML_CALCULATION_FAILED", FAILURE_MESSAGE, exception);
        }

        AssessmentCompletionResponse response = transactions.execute(
                status -> finish(sessionId, claim.attemptId(), result));
        if (response == null) {
            throw new IllegalStateException("진단 완료 저장 결과가 없습니다.");
        }
        return response;
    }

    private CompletionClaim claim(long sessionId) {
        AssessmentSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("진단 세션을 찾을 수 없습니다."));
        if (session.getStatus() == AssessmentStatus.COMPLETED) {
            ReaderProfile profile = profiles.findBySessionId(sessionId)
                    .orElseThrow(() -> new IllegalStateException("완료된 세션의 프로필을 찾을 수 없습니다."));
            return new CompletionClaim(null, null, toResponse(session, profile));
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (session.hasActiveProcessingLease(now)) {
            throw new AssessmentStateConflictException("진단 완료 처리가 이미 진행 중입니다.");
        }

        List<AssessmentQuestion> issued = assessmentQuestions.findBySessionIdOrderByOrderIndex(sessionId);
        Map<Long, AssessmentAnswer> answerByQuestion = answers
                .findByAssessmentQuestionIdIn(issued.stream().map(AssessmentQuestion::getId).toList())
                .stream()
                .collect(Collectors.toMap(
                        AssessmentAnswer::getAssessmentQuestionId,
                        Function.identity()));
        if (issued.size() != REQUIRED_QUESTION_COUNT || answerByQuestion.size() != issued.size()) {
            throw new AssessmentStateConflictException("모든 진단 문항에 답변한 뒤 완료할 수 있습니다.");
        }

        String topicCode = topics.findById(session.getTopicId())
                .orElseThrow(() -> new IllegalStateException("진단 분야를 찾을 수 없습니다."))
                .getCode();
        UUID attemptId = UUID.randomUUID();
        session.beginProcessing(attemptId, now.plus(processingLease));
        List<MlProfileRequest.Answer> mlAnswers = issued.stream()
                .map(question -> new MlProfileRequest.Answer(
                        question.getId().toString(),
                        question.getMeasurementAreaSnapshot(),
                        question.getConceptIdSnapshot(),
                        question.getDifficultySnapshot(),
                        answerByQuestion.get(question.getId()).isKnowsConcept(),
                        1.0))
                .toList();
        MlProfileRequest request = new MlProfileRequest(
                attemptId,
                CONTRACT_VERSION,
                session.getUserId(),
                session.getId().toString(),
                topicCode,
                mlAnswers);
        return new CompletionClaim(attemptId, request, null);
    }

    private AssessmentCompletionResponse finish(
            long sessionId,
            UUID attemptId,
            MlProfileResult result) {
        AssessmentSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("진단 세션을 찾을 수 없습니다."));
        if (!session.ownsAttempt(attemptId)) {
            throw new AssessmentStateConflictException("진단 완료 처리 권한이 만료되거나 교체되었습니다.");
        }
        if (profiles.findBySessionId(sessionId).isPresent()) {
            throw new IllegalStateException("진단 세션에 프로필이 이미 저장되어 있습니다.");
        }

        ReaderProfile profile = profiles.save(new ReaderProfile(
                sessionId,
                result.vocabulary(),
                result.backgroundKnowledge(),
                result.comprehension(),
                result.calculationVersion(),
                result.evidence()));
        session.complete(OffsetDateTime.now(ZoneOffset.UTC));
        return toResponse(session, profile);
    }

    private void recover(UUID attemptId, long sessionId, RuntimeException exception) {
        String failureCode = exception instanceof MlGatewayException gatewayException
                ? gatewayException.getFailureCode()
                : "ML_CALCULATION_FAILED";
        transactions.executeWithoutResult(status -> {
            AssessmentSession session = sessions.findByIdForUpdate(sessionId).orElse(null);
            if (session != null && session.ownsAttempt(attemptId)) {
                session.failProcessing(failureCode, FAILURE_MESSAGE);
            }
        });
    }

    private AssessmentCompletionResponse toResponse(
            AssessmentSession session,
            ReaderProfile profile) {
        return new AssessmentCompletionResponse(
                session.getId(),
                session.getUserId(),
                session.getTopicId(),
                session.getStatus().name(),
                ReaderProfileService.toResponse(profile, session));
    }

    private record CompletionClaim(
            UUID attemptId,
            MlProfileRequest request,
            AssessmentCompletionResponse completedResponse) {
    }
}
