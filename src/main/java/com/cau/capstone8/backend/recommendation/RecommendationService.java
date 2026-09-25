package com.cau.capstone8.backend.recommendation;

import com.cau.capstone8.backend.book.Book;
import com.cau.capstone8.backend.book.BookFeature;
import com.cau.capstone8.backend.book.BookFeatureRepository;
import com.cau.capstone8.backend.book.BookRankingV2Projection;
import com.cau.capstone8.backend.book.BookRankingV2ProjectionRepository;
import com.cau.capstone8.backend.book.BookRepository;
import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import com.cau.capstone8.backend.integration.ml.MlGateway;
import com.cau.capstone8.backend.integration.ml.MlGatewayException;
import com.cau.capstone8.backend.integration.ml.MlRankRequest;
import com.cau.capstone8.backend.integration.ml.MlRankResponseValidator;
import com.cau.capstone8.backend.integration.ml.MlRankResult;
import com.cau.capstone8.backend.integration.ml.MlRankV2ProfileProjector;
import com.cau.capstone8.backend.integration.ml.MlRankV2Request;
import com.cau.capstone8.backend.integration.ml.MlRankV2ResponseValidator;
import com.cau.capstone8.backend.integration.ml.MlRankV2Result;
import com.cau.capstone8.backend.profile.ReaderProfile;
import com.cau.capstone8.backend.profile.ReaderProfileRepository;
import com.cau.capstone8.backend.topic.TopicRepository;
import com.cau.capstone8.backend.topic.Topic;
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
    private final BookRankingV2ProjectionRepository v2Projections;
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
            BookRankingV2ProjectionRepository v2Projections,
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
        this.v2Projections = v2Projections;
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

        Finish finished;
        if (claim.rankV2Request() != null) {
            MlRankV2Result result;
            try {
                result = MlRankV2ResponseValidator.validate(
                        claim.rankV2Request(), mlGateway.rankBooksV2(claim.rankV2Request()));
            } catch (RuntimeException exception) {
                RecommendationFailureException failure = failureFrom(exception);
                throw recover(claim.runId(), claim.attemptId(), failure);
            }
            finished = transactions.execute(status -> finishV2(
                    claim.runId(), claim.attemptId(), claim.rankV2Request(), result));
        } else {
            MlRankResult result;
            try {
                result = MlRankResponseValidator.validate(
                        claim.rankRequest(), mlGateway.rankBooks(claim.rankRequest()));
            } catch (RuntimeException exception) {
                RecommendationFailureException failure = failureFrom(exception);
                throw recover(claim.runId(), claim.attemptId(), failure);
            }
            finished = transactions.execute(status -> finish(
                    claim.runId(), claim.attemptId(), claim.rankRequest(), result));
        }
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
                return new Claim(null, null, null, null, null,
                        new RecommendationFailureException(
                                existing.getLastFailureCode(),
                                existing.getLastFailureMessage(),
                                existing.getFailureHttpStatus()));
            }
            return new Claim(null, null, null, null, toResponse(existing), null);
        }

        Topic topic = topics.findById(request.topicId())
                .orElseThrow(() -> new ResourceNotFoundException("분야를 찾을 수 없습니다."));
        ReaderProfile profile = profiles.findLatestCompleted(request.userId(), request.topicId())
                .orElseThrow(() -> new RecommendationConflictException(
                        "선택한 분야의 완료된 독자 프로필이 없습니다."));
        if ("http".equalsIgnoreCase(mlMode)) {
            return claimV2(request, requestKey, requestHash, topic, profile);
        }

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
                candidates.size(), excludedCandidateCount, RecommendationRankingMode.LEGACY_SCALAR));
        return new Claim(run.getId(), attemptId, rankRequest, null, null, null);
    }

    private Claim claimV2(
            RecommendationCreateRequest request,
            String requestKey,
            String requestHash,
            Topic topic,
            ReaderProfile profile) {
        if (topic.getMlTopicId() == null || topic.getMlTopicId().isBlank()) {
            throw new RecommendationInputException("선택한 분야에 ML topic 매핑이 없습니다.");
        }
        MlRankV2Request.Reader reader;
        try {
            reader = MlRankV2ProfileProjector.project(profile, topic.getMlTopicId());
        } catch (IllegalArgumentException exception) {
            throw new RecommendationInputException(
                    "독자 프로필에 rank-v2용 concept readiness와 provenance가 없습니다.");
        }

        if (request.targetBookId() != null && !books.existsById(request.targetBookId())) {
            throw new ResourceNotFoundException("도서를 찾을 수 없습니다.");
        }
        List<BookRankingV2Projection> projections =
                v2Projections.findByTopicIdAndActiveTrueOrderByBookId(request.topicId());
        if (projections.isEmpty()) {
            throw new RecommendationInputException("rank-v2 추천 후보 projection이 없습니다.");
        }
        Map<Long, Book> mappedBooks = books.findAllById(
                        projections.stream().map(BookRankingV2Projection::getBookId).toList())
                .stream().collect(Collectors.toMap(Book::getId, Function.identity()));
        List<MlRankV2Request.Candidate> candidates = projections.stream()
                .map(projection -> toV2Candidate(projection, mappedBooks.get(projection.getBookId()),
                        topic.getMlTopicId()))
                .toList();

        String targetMlBookId = null;
        if (request.targetBookId() != null) {
            targetMlBookId = candidates.stream()
                    .filter(candidate -> candidate.backendBookId() == request.targetBookId())
                    .map(MlRankV2Request.Candidate::mlBookId)
                    .findFirst()
                    .orElseThrow(() -> new RecommendationInputException(
                            "선택한 도서에 활성 rank-v2 projection이 없습니다."));
        }
        UUID attemptId = UUID.randomUUID();
        MlRankV2Request rankRequest = new MlRankV2Request(
                attemptId, request.userId(), reader, candidates, targetMlBookId,
                effectiveLimit(request));
        long assignedCount = books.countCatalog(request.topicId());
        int excluded = Math.toIntExact(Math.max(0, assignedCount - projections.size()));
        RecommendationRun run = runs.save(new RecommendationRun(
                request.userId(), request.topicId(), profile.getId(), requestKey, requestHash,
                request.challengeLevel(), request.targetBookId(), effectiveLimit(request),
                attemptId, OffsetDateTime.now(ZoneOffset.UTC).plus(processingLease),
                inputSnapshot(rankRequest), candidates.size(), excluded,
                RecommendationRankingMode.PREREQUISITE_FIRST_V2));
        return new Claim(run.getId(), attemptId, null, rankRequest, null, null);
    }

    private MlRankV2Request.Candidate toV2Candidate(
            BookRankingV2Projection projection, Book book, String mlTopicId) {
        if (book == null || book.getMlBookId() == null || book.getMlBookId().isBlank()
                || projection.getTopicDistribution().getOrDefault(mlTopicId, 0.0) <= 0) {
            throw new RecommendationInputException("rank-v2 후보의 canonical ID/topic 매핑이 올바르지 않습니다.");
        }
        return new MlRankV2Request.Candidate(
                projection.getId(), projection.getBookId(), book.getMlBookId(),
                projection.getTopicDistribution(), concepts(projection.getCoveredConcepts()),
                concepts(projection.getPrerequisiteConcepts()), projection.getLexicalDifficulty(),
                projection.getSyntacticComplexity(), projection.getConceptDensity(),
                projection.getPrerequisiteDemand(), projection.getVersion(),
                projection.getConfigVersion(), projection.getConfigHash());
    }

    private List<MlRankV2Request.Concept> concepts(List<Map<String, Object>> raw) {
        try {
            return raw.stream().map(item -> new MlRankV2Request.Concept(
                    (String) item.get("concept"), ((Number) item.get("weight")).doubleValue())).toList();
        } catch (RuntimeException exception) {
            throw new RecommendationInputException("rank-v2 후보 concept projection이 올바르지 않습니다.");
        }
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

    private Finish finishV2(
            long runId, UUID attemptId, MlRankV2Request request, MlRankV2Result result) {
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
        Map<String, MlRankV2Request.Candidate> candidates = request.candidates().stream()
                .collect(Collectors.toMap(MlRankV2Request.Candidate::mlBookId, Function.identity()));
        for (MlRankV2Result.Item ranked : result.items()) {
            items.save(RecommendationItem.fromV2(runId, candidates.get(ranked.mlBookId()), ranked));
        }
        MlRankV2Result.Diagnostics d = result.diagnostics();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("requestedLimit", d.requestedLimit());
        diagnostics.put("returnedCount", d.returnedCount());
        diagnostics.put("topicCandidateCount", d.topicCandidateCount());
        diagnostics.put("personalizableCount", d.personalizableCount());
        diagnostics.put("conceptOnlyCount", d.conceptOnlyCount());
        diagnostics.put("evidenceUnavailableCount", d.evidenceUnavailableCount());
        diagnostics.put("fallbackCount", d.fallbackCount());
        diagnostics.put("personalizedCandidateShortage", d.personalizedCandidateShortage());
        run.succeedV2(
                result.modelVersion(), result.configVersion(), result.configHash(),
                result.conceptGraphVersion(), result.conceptGraphHash(),
                result.graphReviewVersion(), result.graphReviewHash(),
                result.readerProfileVersion(), result.readerConfigVersion(),
                result.readerConfigHash(), diagnostics,
                OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
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
        LOG.warn("ML 추천 계산 실패 code={}", code, exception);
        if ("ML_RANK_TARGET_UNAVAILABLE".equals(code)) {
            return new RecommendationFailureException(
                    code, "선택한 도서는 현재 프로필 근거로 개인화할 수 없습니다.", 422);
        }
        int status = switch (code) {
            case "ML_TIMEOUT" -> 504;
            case "ML_UNAVAILABLE" -> 503;
            default -> 502;
        };
        return new RecommendationFailureException(code, FAILURE_MESSAGE, status);
    }

    private RecommendationResponse toResponse(RecommendationRun run) {
        List<RecommendationItem> storedItems = run.getStatus() == RecommendationStatus.SUCCEEDED
                ? items.findByRunIdOrderByRank(run.getId()) : List.of();
        Map<Long, Book> responseBooks = books.findAllById(
                        storedItems.stream().map(RecommendationItem::getBookId).toList())
                .stream().collect(Collectors.toMap(Book::getId, Function.identity()));
        List<RecommendationResponse.Item> responseItems = storedItems.stream().map(item -> {
            Book book = responseBooks.get(item.getBookId());
            if (book == null) throw new IllegalStateException("추천 도서가 존재하지 않습니다.");
            return new RecommendationResponse.Item(
                    item.getId(), item.getBookId(), book.getMlBookId(), book.getTitle(), book.getAuthor(),
                    item.getRankingMode(), item.getRank(), item.getScore(), item.getTopicFit(),
                    item.getVocabularyFit(), item.getKnowledgeFit(), item.getComprehensionFit(),
                    item.getBookFeatureVersion(), item.getPrerequisiteReadiness(),
                    item.getPrerequisiteAssessedCount(), item.getPrerequisiteTotalCount(),
                    item.getPrerequisiteCoverage(), item.getDirectLearningOpportunity(),
                    item.getDirectAssessedCount(), item.getDirectTotalCount(), item.getDirectCoverage(),
                    item.getAvailabilityStatus(), item.getCoveredConcepts(), item.getInferredPrerequisites(),
                    item.getBookConfigVersion(), item.getBookConfigHash(), item.getReasons());
        }).toList();
        RecommendationResponse.Provenance provenance =
                run.getRankingMode() == RecommendationRankingMode.PREREQUISITE_FIRST_V2
                        && run.getStatus() == RecommendationStatus.SUCCEEDED
                ? new RecommendationResponse.Provenance(
                        run.getRankingConfigVersion(), run.getRankingConfigHash(),
                        run.getConceptGraphVersion(), run.getConceptGraphHash(),
                        run.getGraphReviewVersion(), run.getGraphReviewHash(),
                        run.getReaderProfileVersion(), run.getReaderConfigVersion(),
                        run.getReaderConfigHash())
                : null;
        return new RecommendationResponse(
                run.getId(), run.getUserId(), run.getTopicId(), run.getProfileId(),
                run.getStatus(), run.getRankingMode(), run.getChallengeLevel(), run.getTargetBookId(),
                run.getRequestedTopK(), run.getEligibleCandidateCount(),
                run.getExcludedCandidateCount(), run.getModelVersion(),
                provenance, run.getRankingDiagnostics(),
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

    private Map<String, Object> inputSnapshot(MlRankV2Request request) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("contractVersion", "rank-v2-wire-v1");
        snapshot.put("requestId", request.requestId().toString());
        snapshot.put("mlMode", mlMode);
        snapshot.put("rankingModel", MlRankV2Request.MODEL);
        snapshot.put("challengeLevelAcceptedButUnused", true);
        snapshot.put("targetBookId", request.targetBookId());
        snapshot.put("limit", request.limit());
        snapshot.put("reader", Map.of(
                "topicId", request.reader().topicId(),
                "profileVersion", request.reader().profileVersion(),
                "configVersion", request.reader().configVersion(),
                "configHash", request.reader().configHash(),
                "conceptReadiness", request.reader().conceptReadiness()));
        snapshot.put("candidates", request.candidates().stream().map(candidate -> Map.of(
                "projectionId", candidate.projectionId(),
                "backendBookId", candidate.backendBookId(),
                "mlBookId", candidate.mlBookId(),
                "featureVersion", candidate.featureVersion(),
                "configVersion", candidate.configVersion(),
                "configHash", candidate.configHash())).toList());
        return snapshot;
    }

    private String hash(RecommendationCreateRequest request) {
        String canonical = CONTRACT_VERSION + "\n" + mlMode + "\n" + request.userId() + "\n" + request.topicId()
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
            MlRankV2Request rankV2Request,
            RecommendationResponse existingResponse,
            RecommendationFailureException savedFailure) {
    }

    private record Finish(
            RecommendationResponse response,
            RecommendationFailureException failure) {
    }
}
