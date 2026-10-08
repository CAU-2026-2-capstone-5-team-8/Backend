package com.cau.capstone8.backend.book;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.cau.capstone8.backend.learning.LearningEvidence;
import tools.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Explicit file handoff only: no scraping, sibling-path discovery, or ML inference. */
@Service
public class LocalCatalogImportService {
    private static final JsonMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .build();
    private final JdbcTemplate jdbc;

    public LocalCatalogImportService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record TopicMapping(String code, String name, String parentCode,
                               String parentName, String mlTopicId) {}
    public record Manifest(String contractVersion, String snapshotId, String booksPath,
                           String booksSha256, String candidatesPath, String candidatesSha256,
                           List<String> selectedBookIds, TopicMapping topic) {}
    public record CanonicalBook(String bookId, @JsonProperty("isbn_10") String isbn10,
                                @JsonProperty("isbn_13") String isbn13, String title,
                                String subtitle, List<String> authors, String publisher,
                                Integer publishedYear, String language, List<String> topics) {}
    public record Concept(String concept, double weight, List<LearningEvidence> evidence) {
        public Concept(String concept, double weight) { this(concept, weight, List.of()); }
        public Concept { evidence = List.copyOf(evidence); }

        // Keep strict old fields while allowing an omitted, additive evidence array.
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Concept parse(JsonNode node) {
            require(node.isObject() && node.path("concept").isString() && node.path("weight").isNumber(),
                    "concept and numeric weight required");
            node.propertyNames().forEach(name -> require(Set.of("concept", "weight", "evidence").contains(name), "unknown concept field"));
            List<LearningEvidence> evidence = node.has("evidence")
                    ? JSON.convertValue(node.path("evidence"), new tools.jackson.core.type.TypeReference<List<LearningEvidence>>() {})
                    : List.of();
            require(evidence != null, "evidence must be an array");
            for (var row : evidence) require(row != null && node.path("concept").asString().equals(row.conceptId()), "evidence concept mismatch");
            return new Concept(node.path("concept").asString(), node.path("weight").asDouble(), evidence);
        }
    }
    public record Candidate(String bookId, Map<String, Double> topicDistribution,
                            List<Concept> coveredConcepts, List<Concept> prerequisiteConcepts,
                            Double lexicalDifficulty, Double syntacticComplexity,
                            Double conceptDensity, Double prerequisiteDemand,
                            String featureVersion, String configVersion, String configHash) {
        public Candidate {
            if (coveredConcepts != null)
                LearningEvidence.validateIdentities(coveredConcepts.stream().flatMap(c -> c.evidence().stream()).toList());
        }
    }
    public record Result(String snapshotId, long topicId, Map<String, Long> bookIds,
                         Map<String, Long> projectionIds, boolean replayed) {}
    public record ProjectionSource(String snapshotId, String candidatesSha256) {}

    @Transactional
    public Result importManifest(Path path) {
        return importManifest(path, manifestBytes(path));
    }

    static byte[] manifestBytes(Path path) {
        try { return read(path); }
        catch (java.io.IOException e) { throw new IllegalArgumentException("catalog manifest could not be read", e); }
    }

    static Manifest parseManifest(byte[] bytes) { return JSON.readValue(bytes, Manifest.class); }

    // Caller owns the transaction. Shared with the explicit preview/transition command;
    // the manifest bytes used to confirm a plan are exactly those imported.
    Result importManifest(Path path, byte[] manifestBytes) {
        try {
            Manifest manifest = JSON.readValue(manifestBytes, Manifest.class);
            require("local-catalog-import-v1".equals(manifest.contractVersion()), "unsupported contract");
            text(manifest.snapshotId(), 120);
            require(manifest.topic() != null, "topic mapping is required");
            TopicMapping topic = manifest.topic();
            text(topic.code(), 80); text(topic.name(), 120);
            text(topic.parentCode(), 80); text(topic.parentName(), 120); text(topic.mlTopicId(), 120);
            require(!topic.code().equals(topic.parentCode()), "topic cannot be its own parent");
            require(manifest.selectedBookIds() != null && !manifest.selectedBookIds().isEmpty(),
                    "selected_book_ids must not be empty");
            require(new HashSet<>(manifest.selectedBookIds()).size() == manifest.selectedBookIds().size(),
                    "duplicate selected book IDs");
            manifest.selectedBookIds().forEach(id -> text(id, 200));
            Path base = path.toAbsolutePath().getParent();
            byte[] booksBytes = checked(base.resolve(manifest.booksPath()), manifest.booksSha256());
            byte[] candidatesBytes = checked(base.resolve(manifest.candidatesPath()), manifest.candidatesSha256());
            Map<String, CanonicalBook> books = new LinkedHashMap<>();
            for (String line : lines(booksBytes)) {
                CanonicalBook book = JSON.readValue(line, CanonicalBook.class);
                text(book.bookId(), 200);
                require(books.putIfAbsent(book.bookId(), book) == null, "duplicate canonical book_id");
            }
            Map<String, Candidate> candidates = new LinkedHashMap<>();
            for (String line : lines(candidatesBytes)) {
                Candidate candidate = JSON.readValue(line, Candidate.class);
                text(candidate.bookId(), 200);
                require(candidates.putIfAbsent(candidate.bookId(), candidate) == null, "duplicate candidate book_id");
            }
            // Serialize this opt-in local writer; no overwrite or implicit identity merge.
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtext('local-catalog-import-v1'))", Object.class);
            String manifestHash = hash(manifestBytes);
            var existing = jdbc.queryForList(
                    "select manifest_hash from backend.local_catalog_import where snapshot_id=?",
                    manifest.snapshotId());
            boolean replayed = !existing.isEmpty();
            if (replayed) require(manifestHash.equals(existing.getFirst().get("manifest_hash")),
                    "snapshot_id already has different immutable input");
            long parentId = topic(topic.parentCode(), topic.parentName(), null, null);
            long topicId = topic(topic.code(), topic.name(), parentId, topic.mlTopicId());
            Map<String, Long> bookIds = new LinkedHashMap<>();
            Map<String, Long> projectionIds = new LinkedHashMap<>();
            for (String id : manifest.selectedBookIds()) {
                CanonicalBook book = books.get(id);
                Candidate candidate = candidates.get(id);
                require(book != null && candidate != null, "selected book missing from handoff: " + id);
                validate(book, candidate, topic.mlTopicId());
                long bookId = book(book);
                link(bookId, topicId);
                long projectionId = projection(bookId, topicId, candidate, manifest.snapshotId(),
                        manifest.candidatesSha256(), List.of());
                bookIds.put(id, bookId); projectionIds.put(id, projectionId);
            }
            if (!replayed) jdbc.update("""
                    insert into backend.local_catalog_import(snapshot_id,manifest_hash,books_hash,
                        candidates_hash,topic_id,selected_book_ids) values (?,?,?,?,?,cast(? as jsonb))
                    """, manifest.snapshotId(), manifestHash, manifest.booksSha256(),
                    manifest.candidatesSha256(), topicId, JSON.writeValueAsString(manifest.selectedBookIds()));
            return new Result(manifest.snapshotId(), topicId, bookIds, projectionIds, replayed);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("local catalog handoff could not be read", exception);
        }
    }

    long topic(String code, String name, Long parent, String mlId) {
        var rows = jdbc.queryForList("select id,code,name,parent_id,ml_topic_id from backend.topic where code=? or ml_topic_id=?",
                code, mlId);
        if (!rows.isEmpty()) {
            require(rows.size() == 1, "topic identity conflict");
            var row = rows.getFirst();
            require(code.equals(row.get("code")) && name.equals(row.get("name"))
                    && java.util.Objects.equals(parent, row.get("parent_id"))
                    && java.util.Objects.equals(mlId, row.get("ml_topic_id")), "existing topic mapping differs");
            return ((Number) row.get("id")).longValue();
        }
        return jdbc.queryForObject("insert into backend.topic(code,name,parent_id,ml_topic_id) values (?,?,?,?) returning id",
                Long.class, code, name, parent, mlId);
    }

    long book(CanonicalBook book) {
        String isbn = book.isbn13() == null ? book.isbn10() : book.isbn13();
        // Canonical [] explicitly means unavailable; preserve it in the immutable handoff.
        // The current display column is non-null/nonblank, so use a visible missing-value label.
        String author = book.authors().isEmpty() ? "저자 정보 없음" : String.join(", ", book.authors());
        text(author, 200);
        var rows = jdbc.queryForList("select id,ml_book_id,isbn,title,author from backend.book where ml_book_id=? or isbn=?",
                book.bookId(), isbn);
        if (!rows.isEmpty()) {
            require(rows.size() == 1, "ISBN and ml_book_id identify different books");
            var row = rows.getFirst();
            require(book.bookId().equals(row.get("ml_book_id")) && java.util.Objects.equals(isbn, row.get("isbn"))
                    && book.title().equals(row.get("title")) && author.equals(row.get("author")),
                    "existing ISBN/ml_book_id has different immutable metadata");
            return ((Number) row.get("id")).longValue();
        }
        return jdbc.queryForObject("insert into backend.book(title,author,description,isbn,ml_book_id) values (?,?,'',?,?) returning id",
                Long.class, book.title(), author, isbn, book.bookId());
    }

    void link(long bookId, long topicId) {
        var links = jdbc.queryForList(
                "select is_primary, topic_weight from backend.book_topic where book_id=? and topic_id=?",
                bookId, topicId);
        if (links.isEmpty()) {
            require(jdbc.queryForObject(
                    "select count(*) from backend.book_topic where book_id=? and is_primary",
                    Integer.class, bookId) == 0, "book already has another primary topic");
            jdbc.update("insert into backend.book_topic(book_id,topic_id,is_primary,topic_weight) values (?,?,true,1)",
                    bookId, topicId);
        } else require(Boolean.TRUE.equals(links.getFirst().get("is_primary"))
                && ((Number) links.getFirst().get("topic_weight")).doubleValue() == 1,
                "existing book-topic assignment differs");
    }

    long projection(long bookId, long topicId, Candidate c, String snapshotId,
                    String candidatesHash, List<ProjectionSource> acceptedSources) {
        var rows = jdbc.queryForList("""
                select id,active,topic_distribution,covered_concepts,prerequisite_concepts,
                    lexical_difficulty,syntactic_complexity,concept_density,prerequisite_demand,
                    version,config_version,config_hash,source_artifact_version,source_artifact_hash
                from backend.book_ranking_v2_projection where book_id=? and topic_id=? and version=?
                """, bookId, topicId, c.featureVersion());
        if (!rows.isEmpty()) {
            var row = rows.getFirst();
            Candidate stored = new Candidate(c.bookId(),
                    JSON.readValue(row.get("topic_distribution").toString(), new tools.jackson.core.type.TypeReference<>() {}),
                    JSON.readValue(row.get("covered_concepts").toString(), new tools.jackson.core.type.TypeReference<>() {}),
                    JSON.readValue(row.get("prerequisite_concepts").toString(), new tools.jackson.core.type.TypeReference<>() {}),
                    (Double) row.get("lexical_difficulty"), (Double) row.get("syntactic_complexity"),
                    (Double) row.get("concept_density"), (Double) row.get("prerequisite_demand"),
                    (String) row.get("version"), (String) row.get("config_version"), (String) row.get("config_hash"));
            boolean sameSource = snapshotId.equals(row.get("source_artifact_version"))
                    && candidatesHash.equals(row.get("source_artifact_hash"));
            boolean acceptedSource = acceptedSources.stream().anyMatch(source ->
                    source.snapshotId().equals(row.get("source_artifact_version"))
                    && source.candidatesSha256().equals(row.get("source_artifact_hash"))
                    && jdbc.queryForObject("""
                        select count(*) from backend.local_catalog_import
                        where snapshot_id=? and candidates_hash=? and selected_book_ids @> cast(? as jsonb)
                        """, Integer.class, source.snapshotId(), source.candidatesSha256(),
                            JSON.writeValueAsString(List.of(c.bookId()))) == 1);
            require(c.equals(stored) && Boolean.TRUE.equals(row.get("active"))
                    && (sameSource || acceptedSource),
                    "existing feature version has different content or provenance");
            return ((Number) row.get("id")).longValue();
        }
        require(jdbc.queryForObject("select count(*) from backend.book_ranking_v2_projection where book_id=? and topic_id=? and active",
                Integer.class, bookId, topicId) == 0, "another active projection exists; explicit version transition required");
        return jdbc.queryForObject("""
                insert into backend.book_ranking_v2_projection(book_id,topic_id,version,active,
                    topic_distribution,covered_concepts,prerequisite_concepts,lexical_difficulty,
                    syntactic_complexity,concept_density,prerequisite_demand,config_version,config_hash,
                    source_artifact_version,source_artifact_hash)
                values (?,?,?,true,cast(? as jsonb),cast(? as jsonb),cast(? as jsonb),?,?,?,?,?,?,?,?) returning id
                """, Long.class, bookId, topicId, c.featureVersion(),
                JSON.writeValueAsString(c.topicDistribution()), JSON.writeValueAsString(c.coveredConcepts()),
                JSON.writeValueAsString(c.prerequisiteConcepts()), c.lexicalDifficulty(), c.syntacticComplexity(),
                c.conceptDensity(), c.prerequisiteDemand(), c.configVersion(), c.configHash(),
                snapshotId, candidatesHash);
    }

    static void validateBook(CanonicalBook b, String topic) {
        text(b.bookId(), 200);
        text(b.title(), 300);
        require(b.authors() != null, "canonical authors array required");
        b.authors().forEach(a -> text(a, 200));
        require(b.topics() != null && b.topics().contains(topic), "canonical topic mismatch");
        if (b.isbn13() != null) {
            require(b.isbn13().matches("[0-9]{13}") && validIsbn13(b.isbn13()), "invalid ISBN-13");
            require(b.bookId().equals("isbn13:" + b.isbn13()), "book_id/ISBN mismatch");
        }
    }

    static void validate(CanonicalBook b, Candidate c, String topic) {
        validateBook(b, topic);
        require(c.topicDistribution() != null && c.topicDistribution().keySet().equals(Set.of(topic)),
                "candidate topic mismatch");
        c.topicDistribution().values().forEach(LocalCatalogImportService::score);
        require(c.coveredConcepts() != null && c.prerequisiteConcepts() != null, "concept arrays required");
        for (List<Concept> concepts : List.of(c.coveredConcepts(), c.prerequisiteConcepts())) {
            Set<String> ids = new HashSet<>();
            for (Concept concept : concepts) {
                require(concept != null, "null concept"); text(concept.concept(), 120); score(concept.weight());
                require(ids.add(concept.concept()), "duplicate concept");
            }
        }
        for (Double value : new Double[]{c.lexicalDifficulty(), c.syntacticComplexity(), c.conceptDensity(), c.prerequisiteDemand()}) {
            if (value != null) score(value);
        }
        text(c.featureVersion(), 80); text(c.configVersion(), 80);
        require(c.configHash() != null && c.configHash().matches("sha256:[0-9a-f]{64}"), "invalid config hash");
    }

    private static boolean validIsbn13(String isbn) {
        int sum = 0;
        for (int i = 0; i < 13; i++) sum += (isbn.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        return sum % 10 == 0;
    }
    private static void score(Double score) { require(score != null && Double.isFinite(score) && score >= 0 && score <= 1, "invalid score"); }
    private static void text(String value, int max) { require(value != null && !value.isBlank() && value.length() <= max, "invalid text"); }
    private static List<String> lines(byte[] bytes) {
        List<String> lines = new ArrayList<>();
        new String(bytes, StandardCharsets.UTF_8).lines().filter(s -> !s.isBlank()).forEach(lines::add);
        require(!lines.isEmpty(), "empty JSONL"); return lines;
    }
    private static byte[] read(Path path) throws java.io.IOException {
        require(Files.isRegularFile(path) && Files.size(path) <= 20 * 1024 * 1024, "missing or oversized input: " + path);
        return Files.readAllBytes(path);
    }
    private static byte[] checked(Path path, String expected) throws Exception {
        byte[] bytes = read(path);
        require(hash(bytes).equals(expected), "input SHA-256 mismatch: " + path);
        return bytes;
    }
    public static String hash(byte[] bytes) {
        try { return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
