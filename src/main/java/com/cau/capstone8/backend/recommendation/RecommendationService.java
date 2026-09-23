package com.cau.capstone8.backend.recommendation;

import com.cau.capstone8.backend.book.BookFeature;
import com.cau.capstone8.backend.book.BookFeatureRepository;
import com.cau.capstone8.backend.book.BookRepository;
import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import com.cau.capstone8.backend.integration.ml.MlGateway;
import com.cau.capstone8.backend.integration.ml.MlGatewayException;
import com.cau.capstone8.backend.integration.ml.MlRankRequest;
import com.cau.capstone8.backend.integration.ml.MlRankResponseValidator;
import com.cau.capstone8.backend.integration.ml.MlRankResult;
import com.cau.capstone8.backend.profile.ReaderProfile;
import com.cau.capstone8.backend.profile.ReaderProfileRepository;
import com.cau.capstone8.backend.topic.TopicRepository;
import com.cau.capstone8.backend.user.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RecommendationService {
    private static final Logger LOG = LoggerFactory.getLogger(RecommendationService.class);
    private static final String CONTRACT_VERSION = "v1";
    private static final String FAILURE_MESSAGE = "추천 계산에 실패했습니다. 잠시 후 다시 시도해 주세요.";
    private static final String EXPIRED_MESSAGE = "추천 처리 시간이 초과되었습니다. 새 요청으로 다시 시도해 주세요.";

    private final RecommendationRunRepository runs;
    private final RecommendationItemRepository items;
    private final ReaderProfileRepository profiles;
    private final BookRepository books;
    private final BookFeatureRepository features;
    private final TopicRepository topics;
    private final AppUserRepository users;
    private final MlGateway mlGateway;
    private final TransactionTemplate transactions;
    private final Duration processingLease;
    private final String mlMode;

    public RecommendationService(
            RecommendationRunRepository runs,
            RecommendationItemRepository items,
            ReaderProfileRepository profiles,
            BookRepository books,
            BookFeatureRepository features,
            TopicRepository topics,
            AppUserRepository users,
            MlGateway mlGateway,
            PlatformTransactionManager transactionManager,
            @Value("${recommendation.processing-lease:PT30S}") Duration processingLease,
            @Value("${ml.mode:stub}") String mlMode) {
        this.runs = runs;
        this.items = items;
        this.profiles = profiles;
        this.books = books;
        this.features = features;
        this.topics = topics;
        this.users = users;
        this.mlGateway = mlGateway;
        this.transactions = new TransactionTemplate(transactionManager);
        if (processingLease.isZero() || processingLease.isNegative()) {
            throw new IllegalArgumentException("recommendation processing lease must be positive");
        }
        this.processingLease = processingLease;
        this.mlMode = mlMode;
    }

    public Created create(RecommendationCreateRequest request, String requestKey) {
        String requestHash = hash(request);
        Claim claim = transactions.execute(status -> claim(request, requestKey, requestHash));
        if (claim == null) {
            throw new IllegalStateException("추천 생성 트랜잭션 결과가 없습니다.");
        }
        if (claim.existingResponse() != null) {
            return new Created(claim.existingResponse(), false);
        }
        if (claim.savedFailure() != null) {
            throw claim.savedFailure();
        }

        MlRankResult result;
        try {
            result = MlRankResponseValidator.validate(claim.rankRequest(),
                    mlGateway.rankBooks(claim.rankRequest()));
        } catch (RuntimeException exception) {
            RecommendationFailureException failure = failureFrom(exception);
            throw recover(claim.runId(), claim.attemptId(), failure);
        }

        Finish finished = transactions.execute(
                status -> finish(claim.runId(), claim.attemptId(), claim.rankRequest(), result));
        if (finished == null) {
            throw new IllegalStateException("추천 완료 트랜잭션 결과가 없습니다.");
        }
        if (finished.failure() != null) {
            throw finished.failure();
        }
        return new Created(finished.response(), true);
    }

    public RecommendationResponse get(long runId) {
        RecommendationRun run = transactions.execute(status -> {
            RecommendationRun locked = runs.findByIdForUpdate(runId)
                    .orElseThrow(() -> new ResourceNotFoundException("추천 요청을 찾을 수 없습니다."));
            expireIfNeeded(locked);
            return locked;
        });
        if (run == null) {
            throw new IllegalStateException("추천 조회 트랜잭션 결과가 없습니다.");
        }
        return toResponse(run);
    }

    private Claim claim(RecommendationCreateRequest request, String requestKey, String requestHash) {
        users.findByIdForUpdate(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException("사용자를 찾을 수 없습니다."));
        RecommendationRun existing = runs.findByUserIdAndRequestKey(request.userId(), requestKey)
                .orElse(null);
        if (existing != null) {
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new RecommendationConflictException("같은 멱등성 키에 다른 추천 요청을 사용할 수 없습니다.");
            }
            expireIfNeeded(existing);
            if (existing.getStatus() == RecommendationStatus.PROCESSING) {
                throw new RecommendationConflictException("추천 처리가 이미 진행 중입니다.");
            }
            if (existing.getStatus() == RecommendationStatus.FAILED) {
                return new Claim(null, null, null, null,
                        new RecommendationFailureException(
                                existing.getLastFailureCode(),
                                existing.getLastFailureMessage(),
                                existing.getFailureHttpStatus()));
            }
            return new Claim(null, null, null, toResponse(existing), null);
        }

        if (!topics.existsById(request.topicId())) {
            throw new ResourceNotFoundException("분야를 찾을 수 없습니다.");
        }
        ReaderProfile profile = profiles.findLatestCompleted(request.userId(), request.topicId())
                .orElseThrow(() -> new RecommendationConflictException(
                        "선택한 분야의 완료된 독자 프로필이 없습니다."));

        List<BookFeature> candidates;
        int excludedCandidateCount;
        if (request.targetBookId() != null) {
            if (!books.existsById(request.targetBookId())) {
                throw new ResourceNotFoundException("도서를 찾을 수 없습니다.");
            }
            boolean assigned = books.findMemberships(List.of(request.targetBookId())).stream()
                    .anyMatch(membership -> membership.getTopicId().equals(request.topicId()));
            if (!assigned) {
                throw new RecommendationInputException("도서가 선택한 분야에 속하지 않습니다.");
            }
            BookFeature feature = features.findByBookIdAndTopicIdAndActiveTrue(
                            request.targetBookId(), request.topicId())
                    .orElseThrow(() -> new RecommendationInputException(
                            "선택한 도서에 활성 추천 특성이 없습니다."));
            candidates = List.of(feature);
            excludedCandidateCount = 0;
        } else {
            long assignedCount = books.countCatalog(request.topicId());
            candidates = features.findByTopicIdAndActiveTrueOrderByBookId(request.topicId());
            if (candidates.isEmpty()) {
                throw new RecommendationInputException("추천 가능한 도서가 없습니다.");
            }
            excludedCandidateCount = Math.toIntExact(assignedCount - candidates.size());
        }

        UUID attemptId = UUID.randomUUID();
        List<MlRankRequest.Candidate> mlCandidates = candidates.stream()
                .map(feature -> new MlRankRequest.Candidate(
                        feature.getId(), feature.getBookId(), feature.getVersion(),
                        feature.getVocabulary(), feature.getKnowledge(),
                        feature.getComprehension(), feature.getTopicRelevance()))
                .toList();
        MlRankRequest rankRequest = new MlRankRequest(
                attemptId,
                CONTRACT_VERSION,
                new MlRankRequest.Reader(
                        profile.getId(), profile.getVocabulary(), profile.getBackgroundKnowledge(),
                        profile.getComprehension(), profile.getCalculationVersion()),
                request.challengeLevel(),
                mlCandidates,
                request.targetBookId(),
                effectiveLimit(request));
        Map<String, Object> snapshot = inputSnapshot(rankRequest);
        RecommendationRun run = runs.save(new RecommendationRun(
                request.userId(), request.topicId(), profile.getId(), requestKey, requestHash,
                request.challengeLevel(), request.targetBookId(), effectiveLimit(request),
                attemptId, OffsetDateTime.now(ZoneOffset.UTC).plus(processingLease), snapshot,
                candidates.size(), excludedCandidateCount));
        return new Claim(run.getId(), attemptId, rankRequest, null, null);
    }

    private Finish finish(
            long runId, UUID attemptId, MlRankRequest request, MlRankResult result) {
        RecommendationRun run = runs.findByIdForUpdate(runId)
                .orElseThrow(() -> new ResourceNotFoundException("추천 요청을 찾을 수 없습니다."));
        if (!run.ownsAttempt(attemptId)) {
            if (run.getStatus() == RecommendationStatus.FAILED) {
                return new Finish(null, savedFailure(run));
            }
            throw new RecommendationConflictException("추천 처리 권한이 만료되었습니다.");
        }
        if (!run.hasActiveLease(OffsetDateTime.now(ZoneOffset.UTC))) {
            expireIfNeeded(run);
            return new Finish(null, savedFailure(run));
        }
        Map<Long, MlRankRequest.Candidate> candidates = request.candidates().stream()
                .collect(Collectors.toMap(MlRankRequest.Candidate::bookId, Function.identity()));
        for (MlRankResult.Item ranked : result.items()) {
            MlRankRequest.Candidate candidate = candidates.get(ranked.bookId());
            items.save(new RecommendationItem(
                    runId, ranked.bookId(), candidate.featureId(), ranked.rank(), ranked.score(),
                    ranked.topicFit(), ranked.vocabularyFit(), ranked.knowledgeFit(),
                    ranked.comprehensionFit(), candidate.featureVersion(), candidate.vocabulary(),
                    candidate.knowledge(), candidate.comprehension(), candidate.topicRelevance(),
                    ranked.reasons()));
        }
        // PostgreSQL timestamptz stores microsecond precision; truncate here so the completedAt
        // in this call's response matches what a later replay reads back from the DB exactly.
        run.succeed(result.modelVersion(), OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        return new Finish(toResponse(run), null);
    }

    private RecommendationFailureException recover(
            long runId, UUID attemptId, RecommendationFailureException failure) {
        try {
            RecommendationFailureException recovered = transactions.execute(status -> {
                RecommendationRun run = runs.findByIdForUpdate(runId).orElse(null);
                if (run == null) {
                    return failure;
                }
                if (run.ownsAttempt(attemptId)) {
                    if (!run.hasActiveLease(OffsetDateTime.now(ZoneOffset.UTC))) {
                        expireIfNeeded(run);
                        return savedFailure(run);
                    }
                    run.fail(failure.getFailureCode(), failure.getMessage(),
                            failure.getHttpStatus(), OffsetDateTime.now(ZoneOffset.UTC));
                    return failure;
                }
                return run.getStatus() == RecommendationStatus.FAILED
                        ? savedFailure(run)
                        : failure;
            });
            return recovered == null ? failure : recovered;
        } catch (RuntimeException recoveryFailure) {
            LOG.error("추천 실패 상태 저장 실패 runId={}; 임대 만료 후 재조회에서 복구합니다.",
                    runId, recoveryFailure);
            return failure;
        }
    }

    private RecommendationFailureException savedFailure(RecommendationRun run) {
        return new RecommendationFailureException(
                run.getLastFailureCode(), run.getLastFailureMessage(), run.getFailureHttpStatus());
    }

    private void expireIfNeeded(RecommendationRun run) {
        if (run.getStatus() == RecommendationStatus.PROCESSING
                && !run.hasActiveLease(OffsetDateTime.now(ZoneOffset.UTC))) {
            run.fail("RECOMMENDATION_EXPIRED", EXPIRED_MESSAGE, 409,
                    OffsetDateTime.now(ZoneOffset.UTC));
        }
    }

    private RecommendationFailureException failureFrom(RuntimeException exception) {
        String code = exception instanceof MlGatewayException gatewayException
                ? gatewayException.getFailureCode()
                : "ML_RANK_FAILED";
        return new RecommendationFailureException(code, FAILURE_MESSAGE, 502);
    }

    private RecommendationResponse toResponse(RecommendationRun run) {
        List<RecommendationResponse.Item> responseItems = run.getStatus() == RecommendationStatus.SUCCEEDED
                ? items.findByRunIdOrderByRank(run.getId()).stream()
                        .map(item -> new RecommendationResponse.Item(
                                item.getId(), item.getBookId(), item.getRank(), item.getScore(),
                                item.getTopicFit(), item.getVocabularyFit(), item.getKnowledgeFit(),
                                item.getComprehensionFit(), item.getBookFeatureVersion(), item.getReasons()))
                        .toList()
                : List.of();
        return new RecommendationResponse(
                run.getId(), run.getUserId(), run.getTopicId(), run.getProfileId(),
                run.getStatus(), run.getChallengeLevel(), run.getTargetBookId(),
                run.getRequestedTopK(), run.getEligibleCandidateCount(),
                run.getExcludedCandidateCount(), run.getModelVersion(),
                run.getLastFailureCode(), run.getLastFailureMessage(),
                run.getCompletedAt(), responseItems);
    }

    private Map<String, Object> inputSnapshot(MlRankRequest request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("contractVersion", request.contractVersion());
        snapshot.put("requestId", request.requestId().toString());
        snapshot.put("mlMode", mlMode);
        snapshot.put("rankConfigVersion", "stub-rank-config-v1");
        snapshot.put("formula", "mean(topic_fit,vocabulary_fit,knowledge_fit,comprehension_fit)");
        snapshot.put("challengeLevel", request.challengeLevel().name());
        snapshot.put("challengeOffset", request.challengeLevel().getOffset());
        snapshot.put("targetBookId", request.targetBookId());
        snapshot.put("limit", request.limit());
        snapshot.put("reader", Map.of(
                "profileId", request.reader().profileId(),
                "vocabulary", request.reader().vocabulary(),
                "backgroundKnowledge", request.reader().backgroundKnowledge(),
                "comprehension", request.reader().comprehension(),
                "profileVersion", request.reader().profileVersion()));
        snapshot.put("candidates", request.candidates().stream().map(candidate -> Map.of(
                "featureId", candidate.featureId(),
                "bookId", candidate.bookId(),
                "featureVersion", candidate.featureVersion(),
                "vocabulary", candidate.vocabulary(),
                "knowledge", candidate.knowledge(),
                "comprehension", candidate.comprehension(),
                "topicRelevance", candidate.topicRelevance())).toList());
        return snapshot;
    }

    private String hash(RecommendationCreateRequest request) {
        String canonical = CONTRACT_VERSION + "\n" + request.userId() + "\n" + request.topicId()
                + "\n" + (request.targetBookId() == null ? "" : request.targetBookId())
                + "\n" + request.challengeLevel().name() + "\n" + effectiveLimit(request);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private int effectiveLimit(RecommendationCreateRequest request) {
        return request.targetBookId() == null ? request.effectiveTopK() : 1;
    }

    public record Created(RecommendationResponse response, boolean newlyCreated) {
    }

    private record Claim(
            Long runId, UUID attemptId, MlRankRequest rankRequest,
            RecommendationResponse existingResponse,
            RecommendationFailureException savedFailure) {
    }

    private record Finish(
            RecommendationResponse response,
            RecommendationFailureException failure) {
    }
}
