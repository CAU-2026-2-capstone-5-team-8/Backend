package com.cau.capstone8.backend.recommendation;

import com.cau.capstone8.backend.book.BookFeature;
import com.cau.capstone8.backend.book.BookFeatureRepository;
import com.cau.capstone8.backend.integration.ml.MlGateway;
import com.cau.capstone8.backend.integration.ml.MlGatewayException;
import com.cau.capstone8.backend.integration.ml.MlRankRequest;
import com.cau.capstone8.backend.integration.ml.MlRankResponseValidator;
import com.cau.capstone8.backend.integration.ml.MlRankResult;
import com.cau.capstone8.backend.profile.ReaderProfile;
import com.cau.capstone8.backend.profile.ReaderProfileRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RecommendationService {
    private static final String CONTRACT_VERSION = "v1";
    private static final String FAILURE_MESSAGE = "ML 추천 계산에 실패했습니다.";

    private final RecommendationRunRepository runs;
    private final RecommendationItemRepository items;
    private final ReaderProfileRepository profiles;
    private final BookFeatureRepository bookFeatures;
    private final MlGateway mlGateway;
    private final TransactionTemplate transactions;
    private final Duration processingLease;
    private final String mlMode;

    public RecommendationService(
            RecommendationRunRepository runs,
            RecommendationItemRepository items,
            ReaderProfileRepository profiles,
            BookFeatureRepository bookFeatures,
            MlGateway mlGateway,
            PlatformTransactionManager transactionManager,
            @Value("${recommendation.processing-lease:PT30S}") Duration processingLease,
            @Value("${ml.mode:stub}") String mlMode) {
        this.runs = runs;
        this.items = items;
        this.profiles = profiles;
        this.bookFeatures = bookFeatures;
        this.mlGateway = mlGateway;
        this.transactions = new TransactionTemplate(transactionManager);
        if (processingLease.isZero() || processingLease.isNegative()) {
            throw new IllegalArgumentException("recommendation processing lease must be positive");
        }
        this.processingLease = processingLease;
        this.mlMode = mlMode;
    }

    public RecommendationOutcome create(RecommendationRequest request, String idempotencyKey) {
        String requestHash = hash(request);
        Claim claim = transactions.execute(status -> claim(request, idempotencyKey, requestHash));
        if (claim == null) {
            throw new IllegalStateException("추천 요청 트랜잭션 결과가 없습니다.");
        }
        // Replayed failures (including one just recorded by an expired lease) are thrown here,
        // outside the transaction that persisted them, so the FAILED state itself is never rolled back.
        if (claim.replayFailureCode() != null) {
            throw new MlGatewayException(claim.replayFailureCode(), claim.replayFailureMessage());
        }
        if (claim.existingResponse() != null) {
            return new RecommendationOutcome(claim.existingResponse(), false);
        }

        MlRankResult result;
        try {
            result = MlRankResponseValidator.validate(claim.mlRequest(), mlGateway.rank(claim.mlRequest()));
        } catch (RuntimeException exception) {
            recover(claim.runId(), claim.attemptId(), exception);
            if (exception instanceof MlGatewayException gatewayException) {
                throw gatewayException;
            }
            throw new MlGatewayException("ML_CALCULATION_FAILED", FAILURE_MESSAGE, exception);
        }

        RecommendationResponse response = transactions.execute(status -> finish(claim, result));
        if (response == null) {
            throw new IllegalStateException("추천 결과 저장 트랜잭션 결과가 없습니다.");
        }
        return new RecommendationOutcome(response, true);
    }

    private Claim claim(RecommendationRequest request, String requestKey, String requestHash) {
        Optional<RecommendationRun> existingOpt =
                runs.findByUserIdAndRequestKeyForUpdate(request.userId(), requestKey);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (existingOpt.isPresent()) {
            RecommendationRun existing = existingOpt.get();
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new RecommendationConflictException("같은 Idempotency-Key가 다른 요청 내용과 함께 사용됐습니다.");
            }
            if (existing.getStatus() == RecommendationStatus.SUCCEEDED) {
                return new Claim(null, null, null, null, toResponse(existing), null, null);
            }
            if (existing.getStatus() == RecommendationStatus.PROCESSING) {
                if (existing.hasActiveProcessingLease(now)) {
                    throw new RecommendationConflictException("추천 계산이 이미 처리 중입니다.");
                }
                existing.fail("PROCESSING_EXPIRED", "처리 시간이 초과됐습니다.");
                return new Claim(null, null, null, null, null,
                        "PROCESSING_EXPIRED", "처리 시간이 초과됐습니다. 새 Idempotency-Key로 다시 시도해 주세요.");
            }
            return new Claim(null, null, null, null, null,
                    existing.getLastFailureCode(), existing.getLastFailureMessage());
        }

        ReaderProfile profile = profiles.findLatestCompleted(request.userId(), request.topicId())
                .orElseThrow(() -> new RecommendationConflictException("완료된 진단 프로필이 없습니다."));

        List<BookFeature> candidates;
        int excludedCount;
        int effectiveTopK;
        if (request.targetBookId() == null) {
            candidates = bookFeatures.findActiveCandidates(request.topicId());
            if (candidates.isEmpty()) {
                throw new RecommendationInputUnusableException("추천 가능한 도서 후보가 없습니다.");
            }
            long totalBooks = bookFeatures.countBooksInTopic(request.topicId());
            excludedCount = (int) (totalBooks - candidates.size());
            effectiveTopK = request.topK();
        } else {
            BookFeature target = bookFeatures
                    .findByBookIdAndTopicIdAndActiveTrue(request.targetBookId(), request.topicId())
                    .orElseThrow(() -> new RecommendationInputUnusableException(
                            "지정한 도서에 활성 특성이 없거나 해당 분야에 속하지 않습니다."));
            candidates = List.of(target);
            excludedCount = 0;
            effectiveTopK = 1;
        }

        UUID attemptId = UUID.randomUUID();
        Map<String, Object> snapshot = buildSnapshot(profile, candidates, request);
        RecommendationRun run = runs.save(new RecommendationRun(
                request.userId(), request.topicId(), profile.getId(), requestKey, requestHash,
                attemptId, now.plus(processingLease), candidates.size(), excludedCount, snapshot));

        List<MlRankRequest.Candidate> mlCandidates = candidates.stream()
                .map(f -> new MlRankRequest.Candidate(
                        f.getBookId(), f.getVersion(), f.getVocabulary(), f.getKnowledge(),
                        f.getComprehension(), f.getTopicRelevance()))
                .toList();
        MlRankRequest mlRequest = new MlRankRequest(
                attemptId, CONTRACT_VERSION, profile.getCalculationVersion(),
                profile.getVocabulary(), profile.getBackgroundKnowledge(), profile.getComprehension(),
                request.challengeLevel(), effectiveTopK, mlCandidates);

        Map<Long, Long> featureIdsByBookId = candidates.stream()
                .collect(Collectors.toMap(BookFeature::getBookId, BookFeature::getId));

        return new Claim(run.getId(), attemptId, mlRequest, featureIdsByBookId, null, null, null);
    }

    private RecommendationResponse finish(Claim claim, MlRankResult result) {
        RecommendationRun run = runs.findByIdForUpdate(claim.runId())
                .orElseThrow(() -> new IllegalStateException("추천 실행 결과를 찾을 수 없습니다."));
        if (!run.ownsAttempt(claim.attemptId())) {
            throw new RecommendationConflictException("추천 계산 처리 권한이 만료되거나 교체되었습니다.");
        }
        List<RecommendationItem> saved = items.saveAll(result.items().stream()
                .map(item -> new RecommendationItem(
                        run.getId(), item.bookId(), claim.candidateFeatureIds().get(item.bookId()), item.rank(),
                        item.totalScore(), item.topicFit(), item.vocabularyFit(), item.knowledgeFit(),
                        item.comprehensionFit(), item.reasons()))
                .toList());
        run.succeed(result.modelVersion(), OffsetDateTime.now(ZoneOffset.UTC));
        return toResponse(run, saved);
    }

    private void recover(long runId, UUID attemptId, RuntimeException exception) {
        String failureCode = exception instanceof MlGatewayException gatewayException
                ? gatewayException.getFailureCode()
                : "ML_CALCULATION_FAILED";
        transactions.executeWithoutResult(status -> runs.findByIdForUpdate(runId).ifPresent(run -> {
            if (run.ownsAttempt(attemptId)) {
                run.fail(failureCode, FAILURE_MESSAGE);
            }
        }));
    }

    private RecommendationResponse toResponse(RecommendationRun run) {
        return toResponse(run, items.findByRunIdOrderByRank(run.getId()));
    }

    private RecommendationResponse toResponse(RecommendationRun run, List<RecommendationItem> savedItems) {
        ReaderProfile profile = profiles.findById(run.getProfileId())
                .orElseThrow(() -> new IllegalStateException("추천 실행의 프로필을 찾을 수 없습니다."));
        List<RecommendationResponse.Item> responseItems = savedItems.stream()
                .map(item -> new RecommendationResponse.Item(
                        item.getId(), item.getBookId(), item.getRank(), item.getTotalScore(),
                        item.getTopicFit(), item.getVocabularyFit(), item.getKnowledgeFit(),
                        item.getComprehensionFit(), item.getReasons()))
                .toList();
        return new RecommendationResponse(
                run.getId(), run.getUserId(), run.getTopicId(), run.getStatus().name(), run.getProfileId(),
                profile.getCalculationVersion(), run.getModelVersion(), run.getCandidateCount(),
                run.getExcludedCount(), responseItems);
    }

    private Map<String, Object> buildSnapshot(ReaderProfile profile, List<BookFeature> candidates,
                                               RecommendationRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("mlMode", mlMode);
        snapshot.put("challengeLevel", request.challengeLevel().name());
        snapshot.put("topK", request.topK());
        snapshot.put("targetBookId", request.targetBookId());
        snapshot.put("profile", Map.of(
                "id", profile.getId(),
                "version", profile.getCalculationVersion(),
                "vocabulary", profile.getVocabulary(),
                "backgroundKnowledge", profile.getBackgroundKnowledge(),
                "comprehension", profile.getComprehension()));
        snapshot.put("candidates", candidates.stream()
                .map(f -> Map.of(
                        "bookId", f.getBookId(),
                        "featureVersion", f.getVersion(),
                        "vocabulary", f.getVocabulary(),
                        "knowledge", f.getKnowledge(),
                        "comprehension", f.getComprehension(),
                        "topicRelevance", f.getTopicRelevance()))
                .toList());
        return snapshot;
    }

    private String hash(RecommendationRequest request) {
        String canonical = request.userId() + "|" + request.topicId() + "|"
                + (request.targetBookId() == null ? "" : request.targetBookId()) + "|"
                + request.challengeLevel().name() + "|" + request.topK();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

    private record Claim(Long runId, UUID attemptId, MlRankRequest mlRequest,
                          Map<Long, Long> candidateFeatureIds, RecommendationResponse existingResponse,
                          String replayFailureCode, String replayFailureMessage) {
    }
}
