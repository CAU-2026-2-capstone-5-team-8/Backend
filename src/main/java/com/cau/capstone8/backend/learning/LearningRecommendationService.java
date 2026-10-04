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
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LearningRecommendationService {
    private final com.cau.capstone8.backend.account.AccountOwnership ownership;
    private final LearningRecommendationRepository runs;
    private final ReaderProfileRepository profiles;
    private final BookRankingV2ProjectionRepository projections;
    private final BookRepository books;
    private final TopicRepository topics;
    private final AppUserRepository users;
    private final MlGateway ml;

    public LearningRecommendationService(LearningRecommendationRepository runs, ReaderProfileRepository profiles,
            BookRankingV2ProjectionRepository projections, BookRepository books, TopicRepository topics,
            AppUserRepository users, MlGateway ml,
            com.cau.capstone8.backend.account.AccountOwnership ownership) {
        this.runs = runs; this.profiles = profiles; this.projections = projections;
        this.books = books; this.topics = topics; this.users = users; this.ml = ml;
        this.ownership = ownership;
    }

    public record Created(Map<String, Object> body, boolean fresh) {}

    @Transactional
    public Created create(LearningRecommendationController.Request request, String key) {
        return create(request, key, "concept-learning-v1");
    }

    @Transactional
    public Created create(LearningRecommendationController.Request request, String key, String modelVersion) {
        if (!Set.of("concept-learning-v1", "concept-learning-v2").contains(modelVersion))
            throw new IllegalArgumentException("unsupported learning model");
        boolean v2 = "concept-learning-v2".equals(modelVersion);
        ownership.user(request.userId());
        // Serialize retries for this user; HTTP has a bounded timeout. A failed call rolls back.
        users.findByIdForUpdate(request.userId())
                .orElseThrow(() -> new ResourceNotFoundException("사용자를 찾을 수 없습니다."));
        String hash = hash(v2 ? modelVersion + "\n" + request : request.toString());
        var prior = runs.findByUserIdAndRequestKey(request.userId(), key);
        if (prior.isPresent()) {
            if (!prior.get().getRequestHash().equals(hash))
                throw new RecommendationConflictException("같은 키로 다른 추천을 요청할 수 없습니다.");
            return new Created(response(prior.get()), false);
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
        Map<String, Book> canonical = new HashMap<>();
        List<Map<String, Object>> wireBooks = new ArrayList<>();
        for (var candidate : candidates) {
            Book book = catalog.get(candidate.getBookId());
            if (book == null || book.getMlBookId() == null)
                throw new RecommendationInputException("도서 개념 연결을 확인하지 못했습니다.");
            canonical.put(book.getMlBookId(), book);
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
        Map<String, Object> result = new LinkedHashMap<>(ml.learningFit(input));
        result.put("conceptProfileVersion", "concept-abilities-v2");
        if (!modelVersion.equals(result.get("modelVersion"))
                || !topic.getMlTopicId().equals(result.get("topicId"))
                || !request.ability().name().equals(result.get("ability"))
                || !(result.get("items") instanceof List<?> ranked) || ranked.size() > request.topK())
            throw new MlGatewayException("ML_INVALID_RESPONSE", "추천 응답을 확인하지 못했습니다.");
        if (v2) LearningReadinessValidator.validate(input, result);
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
            Book book = canonical.get(canonicalId);
            display.put("canonicalBookId", canonicalId);
            display.put("bookId", book.getId());
            display.put("title", book.getTitle());
            display.put("author", book.getAuthor());
            mapped.add(display);
        }
        result.put("items", mapped);
        var saved = runs.saveAndFlush(new LearningRecommendation(request.userId(), request.topicId(), profile.getId(),
                key, hash, input, result));
        return new Created(response(saved), true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(long id) {
        var run = runs.findById(id).orElseThrow(() -> new ResourceNotFoundException("추천을 찾을 수 없습니다."));
        ownership.user(run.getUserId());
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
}
