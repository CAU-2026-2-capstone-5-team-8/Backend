package com.cau.capstone8.backend.learning;

import com.cau.capstone8.backend.book.*;
import com.cau.capstone8.backend.common.error.ResourceNotFoundException;
import com.cau.capstone8.backend.integration.ml.*;
import com.cau.capstone8.backend.profile.ReaderProfileRepository;
import com.cau.capstone8.backend.recommendation.RecommendationConflictException;
import com.cau.capstone8.backend.recommendation.RecommendationInputException;
import com.cau.capstone8.backend.topic.TopicRepository;
import com.cau.capstone8.backend.user.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class LearningRecommendationService {
    private static final Logger LOG = LoggerFactory.getLogger(LearningRecommendationService.class);
    private final com.cau.capstone8.backend.account.AccountOwnership ownership;
    private final LearningRecommendationRepository runs;
    private final ReaderProfileRepository profiles;
    private final BookRankingV2ProjectionRepository projections;
    private final BookRepository books;
    private final TopicRepository topics;
    private final AppUserRepository users;
    private final MlGateway ml;
    private final TransactionTemplate transactions;
    private final Duration processingLease;

    public LearningRecommendationService(LearningRecommendationRepository runs, ReaderProfileRepository profiles,
            BookRankingV2ProjectionRepository projections, BookRepository books, TopicRepository topics,
            AppUserRepository users, MlGateway ml,
            com.cau.capstone8.backend.account.AccountOwnership ownership,
            PlatformTransactionManager transactionManager,
            @Value("${recommendation.processing-lease:PT30S}") Duration processingLease) {
        this.runs = runs; this.profiles = profiles; this.projections = projections;
        this.books = books; this.topics = topics; this.users = users; this.ml = ml;
        this.ownership = ownership;
        this.transactions = new TransactionTemplate(transactionManager);
        if (processingLease.isZero() || processingLease.isNegative())
            throw new IllegalArgumentException("recommendation processing lease must be positive");
        this.processingLease = processingLease;
    }

    public record Created(Map<String, Object> body, boolean fresh) {}

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Created create(LearningRecommendationController.Request request, String key) {
        return create(request, key, "concept-learning-v1");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Created create(LearningRecommendationController.Request request, String key, String modelVersion) {
        if (!Set.of("concept-learning-v1", "concept-learning-v2").contains(modelVersion))
            throw new IllegalArgumentException("unsupported learning model");
        ownership.user(request.userId());
        Claim claim = Objects.requireNonNull(transactions.execute(status -> claim(request, key, modelVersion)));
        if (claim.existing() != null) return new Created(claim.existing(), false);
        try {
            Map<String, Object> result = calculate(claim, request, modelVersion);
            return Objects.requireNonNull(transactions.execute(status -> finish(claim, result)));
        } catch (RuntimeException exception) {
            recover(claim);
            throw exception;
        }
    }

    private Claim claim(LearningRecommendationController.Request request, String key, String modelVersion) {
        boolean v2 = "concept-learning-v2".equals(modelVersion);
        // Serialize creation of a user's keys only until the input and lease are committed.
        users.findByIdForUpdate(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException("사용자를 찾을 수 없습니다."));
        String hash = hash(v2 ? modelVersion + "\n" + request : request.toString());
        var prior = runs.findByUserIdAndRequestKey(request.userId(), key);
        if (prior.isPresent()) {
            var existing = prior.get();
            if (!existing.isProcessing() || existing.hasActiveLease(now())) {
                if (!existing.getRequestHash().equals(hash))
                    throw new RecommendationConflictException("같은 키로 다른 추천을 요청할 수 없습니다.");
                if (existing.isProcessing())
                    throw new RecommendationConflictException("추천 처리가 이미 진행 중입니다.");
                return new Claim(existing.getId(), null, null, null, response(existing));
            }
            // An expired calculation has the same retry semantics as a rolled-back failure.
            runs.delete(existing);
            runs.flush();
        }
        var topic = topics.findById(request.topicId())
                .orElseThrow(() -> new ResourceNotFoundException("분야를 찾을 수 없습니다."));
        var profile = profiles.findLatestCompleted(request.userId(), request.topicId())
                .orElseThrow(() -> new RecommendationConflictException("완료한 개념 진단이 필요합니다."));
        if (profile.getId() != request.profileId())
            throw new RecommendationConflictException("진단 결과가 바뀌었어요. 최신 결과로 다시 요청해 주세요.");
        if (!(profile.getEvidence().get("conceptProfile") instanceof Map<?, ?> conceptProfile)
                || !"concept-abilities-v2".equals(conceptProfile.get("version"))
                || !(conceptProfile.get("abilities") instanceof List<?> abilities))
            throw new RecommendationConflictException("개념별 평가가 없는 이전 진단입니다. 다시 진단해 주세요.");
        var candidates = projections.findByTopicIdAndActiveTrueOrderByBookId(request.topicId());
        if (candidates.isEmpty()) throw new RecommendationInputException("개념 분석 자료가 아직 없습니다.");
        Map<Long, Book> catalog = books.findAllById(candidates.stream().map(BookRankingV2Projection::getBookId).toList())
                .stream().collect(Collectors.toMap(Book::getId, Function.identity()));
        Map<String, DisplayBook> canonical = new HashMap<>();
        List<Map<String, Object>> wireBooks = new ArrayList<>();
        for (var candidate : candidates) {
            Book book = catalog.get(candidate.getBookId());
            if (book == null || book.getMlBookId() == null)
                throw new RecommendationInputException("도서 개념 연결을 확인하지 못했습니다.");
            canonical.put(book.getMlBookId(), new DisplayBook(book.getId(), book.getTitle(), book.getAuthor()));
            Map<String, Object> wireBook = new LinkedHashMap<>(Map.of("bookId", book.getMlBookId(), "coveredConcepts", candidate.getCoveredConcepts()
                    .stream().map(c -> c.get("concept")).toList(),
                    "sourceArtifactVersion", candidate.getSourceArtifactVersion(),
                    "sourceArtifactHash", candidate.getSourceArtifactHash()));
            if (v2) wireBook.put("conceptEvidence", LearningEvidence.fromConcepts(candidate.getCoveredConcepts()));
            wireBooks.add(wireBook);
        }
        List<Map<String, Object>> observations = new ArrayList<>();
        for (Object item : abilities) {
            if (!(item instanceof Map<?, ?> row))
                throw new RecommendationConflictException("개념 진단 자료의 형식이 올바르지 않습니다.");
            if (!"prior-knowledge".equals(row.get("measurementContext")))
                throw new RecommendationConflictException("새 개념 진단을 완료한 뒤 추천을 받아 주세요.");
            observations.add(Map.of("conceptId", row.get("conceptId"), "ability", row.get("ability"),
                    "responseCount", row.get("responseCount"), "correctCount", row.get("correctCount")));
        }
        Map<String, Object> input = new LinkedHashMap<>(Map.of("topicId", topic.getMlTopicId(), "ability", request.ability().name(),
                "observations", observations, "candidateBooks", wireBooks, "limit", request.topK()));
        if (v2) input.put("modelVersion", modelVersion);
        UUID attemptId = UUID.randomUUID();
        var saved = runs.saveAndFlush(LearningRecommendation.processing(request.userId(), request.topicId(),
                profile.getId(), key, hash, input, attemptId, now().plus(processingLease)));
        return new Claim(saved.getId(), attemptId, input, Map.copyOf(canonical), null);
    }

    private Map<String, Object> calculate(Claim claim, LearningRecommendationController.Request request,
            String modelVersion) {
        Map<String, Object> input = claim.input();
        Map<String, DisplayBook> canonical = claim.canonical();
        Map<String, Object> result = new LinkedHashMap<>(ml.learningFit(input));
        result.put("conceptProfileVersion", "concept-abilities-v2");
        if (!modelVersion.equals(result.get("modelVersion"))
                || !input.get("topicId").equals(result.get("topicId"))
                || !request.ability().name().equals(result.get("ability"))
                || !(result.get("items") instanceof List<?> ranked) || ranked.size() > request.topK())
            throw new MlGatewayException("ML_INVALID_RESPONSE", "추천 응답을 확인하지 못했습니다.");
        if ("concept-learning-v2".equals(modelVersion)) LearningReadinessValidator.validate(input, result);
        List<Map<String, Object>> mapped = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object value : ranked) {
            if (!(value instanceof Map<?, ?> row) || !(row.get("bookId") instanceof String canonicalId)
                    || !seen.add(canonicalId) || !canonical.containsKey(canonicalId)
                    || !(row.get("rank") instanceof Number rank) || rank.intValue() != mapped.size() + 1
                    || !Set.of("ready-to-explore", "check-first", "foundation-gap").contains(row.get("status")))
                throw new MlGatewayException("ML_INVALID_RESPONSE", "추천 도서 연결을 확인하지 못했습니다.");
            Map<String, Object> display = new LinkedHashMap<>();
            row.forEach((k, v) -> display.put(k.toString(), v));
            DisplayBook book = canonical.get(canonicalId);
            display.put("canonicalBookId", canonicalId);
            display.put("bookId", book.id());
            display.put("title", book.title());
            display.put("author", book.author());
            mapped.add(display);
        }
        result.put("items", mapped);
        return result;
    }

    private Created finish(Claim claim, Map<String, Object> result) {
        var run = runs.findByIdForUpdate(claim.runId()).orElse(null);
        if (run == null || !run.ownsAttempt(claim.attemptId()) || !run.hasActiveLease(now()))
            throw new RecommendationConflictException("추천 처리 권한이 만료되었습니다. 다시 요청해 주세요.");
        run.succeed(result);
        runs.flush();
        return new Created(response(run), true);
    }

    private void recover(Claim claim) {
        try {
            transactions.executeWithoutResult(status -> {
                var run = runs.findByIdForUpdate(claim.runId()).orElse(null);
                if (run != null && run.ownsAttempt(claim.attemptId())) runs.delete(run);
            });
        } catch (RuntimeException exception) {
            LOG.error("추천 처리 복구 실패 runId={}; 임대 만료 후 다시 요청할 수 있습니다.", claim.runId(), exception);
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(long id) {
        var run = runs.findById(id).orElseThrow(() -> new ResourceNotFoundException("추천을 찾을 수 없습니다."));
        ownership.user(run.getUserId());
        if (run.isProcessing()) throw new RecommendationConflictException("추천 처리가 진행 중이거나 만료되었습니다. 다시 요청해 주세요.");
        return response(run);
    }
    private Map<String, Object> response(LearningRecommendation run) {
        Map<String, Object> body = new LinkedHashMap<>(run.getResult());
        body.put("id", run.getId()); body.put("userId", run.getUserId());
        body.put("topicId", run.getTopicId()); body.put("profileId", run.getProfileId());
        return body;
    }
    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private record DisplayBook(long id, String title, String author) {}
    private record Claim(long runId, UUID attemptId, Map<String, Object> input,
            Map<String, DisplayBook> canonical, Map<String, Object> existing) {}
}
