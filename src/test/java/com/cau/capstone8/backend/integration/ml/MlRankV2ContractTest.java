package com.cau.capstone8.backend.integration.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cau.capstone8.backend.profile.ReaderProfile;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MlRankV2ContractTest {
    private static final String HASH =
            "sha256:6230d12facf94e46c1daa11d9d83b3929322af2f69a36614d3f553684bfb21d6";

    @Test
    void projectsPersistedHttpProfileEvidenceWithoutRecalculation() {
        var profile = new ReaderProfile(10L, 0.7, 0.6, 0.8, "reader-v1", Map.of(
                "method", "ml-http-reader-profile",
                "profileVersion", "reader-v1",
                "configVersion", "reader-config-v1",
                "configHash", HASH,
                "conceptReadiness", List.of(
                        Map.of("conceptId", "process", "score", 0.9,
                                "responseCount", 1, "earnedWeight", 0.9, "availableWeight", 1.0))));

        MlRankV2Request.Reader reader =
                MlRankV2ProfileProjector.project(profile, "operating-systems");

        assertThat(reader.conceptReadiness()).containsExactly(
                new MlRankV2Request.ConceptReadiness("process", 0.9));
        assertThat(reader.configHash()).isEqualTo(HASH);
        assertThat(reader.topicId()).isEqualTo("operating-systems");
    }

    @Test
    void rejectsLegacyProfileAndMalformedReadiness() {
        var stub = new ReaderProfile(10L, 0.7, 0.6, 0.8, "stub-profile-v1",
                Map.of("method", "known_response_ratio"));
        assertThatThrownBy(() -> MlRankV2ProfileProjector.project(stub, "operating-systems"))
                .isInstanceOf(IllegalArgumentException.class);

        var malformed = new ReaderProfile(11L, 0.7, 0.6, 0.8, "reader-v1", Map.of(
                "method", "ml-http-reader-profile", "profileVersion", "reader-v1",
                "configVersion", "reader-config-v1", "configHash", HASH,
                "conceptReadiness", List.of(Map.of("conceptId", "process"))));
        assertThatThrownBy(() -> MlRankV2ProfileProjector.project(malformed, "operating-systems"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void projectsExplicitV2PythonWireShapeAndCanonicalIds() {
        MlRankV2Request request = request(null, 2);

        MlRankV2HttpContract.RankRequest wire = MlRankV2HttpContract.toWire(request);

        assertThat(wire.rankingModel()).isEqualTo("rank-prerequisite-first-v2");
        assertThat(wire.readerProfile().topicId()).isEqualTo("operating-systems");
        assertThat(wire.readerProfile().conceptReadiness()).hasSize(2);
        assertThat(wire.candidateBooks()).extracting(MlRankV2HttpContract.CandidateBook::bookId)
                .containsExactly("isbn13:9780132199087", "isbn13:9780201498387");
        assertThat(wire.candidateBooks().getFirst().coveredConcepts()).hasSize(1);
    }

    @Test
    void acceptsPersonalizedShortageAndEmptySuccess() {
        MlRankV2Request request = request(null, 2);
        MlRankV2Result empty = result(List.of(),
                new MlRankV2Result.Diagnostics(2, 0, 2, 0, 1, 1, 2, 2));

        assertThat(MlRankV2ResponseValidator.validate(request, empty).items()).isEmpty();

        MlRankV2Result.Item item = item("isbn13:9780132199087", 1, 0.8);
        MlRankV2Result shortage = result(List.of(item),
                new MlRankV2Result.Diagnostics(2, 1, 2, 1, 1, 0, 1, 1));
        assertThat(MlRankV2ResponseValidator.validate(request, shortage).items()).hasSize(1);
    }

    @Test
    void acceptsTargetGlobalRankGreaterThanOneAndNullableDirectOpportunity() {
        MlRankV2Request base = request(null, 2);
        MlRankV2Request target = new MlRankV2Request(
                base.requestId(), base.userId(), base.reader(), base.candidates(),
                "isbn13:9780201498387", 1);
        MlRankV2Result.Item item = new MlRankV2Result.Item(
                "isbn13:9780201498387", 2, "personalizable", 0.7,
                1, 2, 0.5, null, 0, 1, 0.0,
                List.of("thread"), List.of("process", "concurrency"),
                List.of("선행 개념 근거", "직접 개념 미평가"), MlRankV2Request.MODEL,
                "book-v1", "features-v1", HASH);
        MlRankV2Result response = result(List.of(item),
                new MlRankV2Result.Diagnostics(1, 1, 2, 2, 0, 0, 0, 0));

        assertThat(MlRankV2ResponseValidator.validate(target, response).items().getFirst().rank())
                .isEqualTo(2);
    }

    @Test
    void rejectsUnrequestedBookInvalidHashAndMalformedCoverage() {
        MlRankV2Request request = request(null, 2);
        MlRankV2Result unrequested = result(
                List.of(item("isbn13:9780000000000", 1, 0.8)),
                new MlRankV2Result.Diagnostics(2, 1, 2, 1, 1, 0, 1, 1));
        assertInvalid(request, unrequested);

        MlRankV2Result duplicate = result(
                List.of(item("isbn13:9780132199087", 1, 0.8),
                        item("isbn13:9780132199087", 2, 0.7)),
                new MlRankV2Result.Diagnostics(2, 2, 2, 2, 0, 0, 0, 0));
        assertInvalid(request, duplicate);

        MlRankV2Result badHash = new MlRankV2Result(
                42, "operating-systems", List.of(),
                new MlRankV2Result.Diagnostics(2, 0, 2, 0, 1, 1, 2, 2),
                MlRankV2Request.MODEL, "ranking-v2-config-v1", "bad",
                "concept-graph-v1", HASH, "concept-graph-reviews-v1", HASH,
                "reader-v1", "reader-config-v1", HASH);
        assertInvalid(request, badHash);

        MlRankV2Result.Item malformed = new MlRankV2Result.Item(
                "isbn13:9780132199087", 1, "personalizable", 0.8,
                1, 2, 0.75, 0.2, 1, 1, 1.0,
                List.of("process"), List.of("concurrency", "thread"),
                List.of("근거 1", "근거 2"), MlRankV2Request.MODEL,
                "book-v1", "features-v1", HASH);
        assertInvalid(request, result(List.of(malformed),
                new MlRankV2Result.Diagnostics(2, 1, 2, 1, 1, 0, 1, 1)));
    }

    private void assertInvalid(MlRankV2Request request, MlRankV2Result result) {
        assertThatThrownBy(() -> MlRankV2ResponseValidator.validate(request, result))
                .isInstanceOfSatisfying(MlGatewayException.class,
                        ex -> assertThat(ex.getFailureCode()).isEqualTo("ML_INVALID_RESPONSE"));
    }

    private MlRankV2Request request(String target, int limit) {
        var reader = new MlRankV2Request.Reader(
                "operating-systems", 0.7, 0.6, 0.8,
                List.of(new MlRankV2Request.ConceptReadiness("process", 0.9),
                        new MlRankV2Request.ConceptReadiness("concurrency", 0.7)),
                "reader-v1", "reader-config-v1", HASH);
        return new MlRankV2Request(UUID.randomUUID(), 42, reader, List.of(
                candidate(1, 101, "isbn13:9780132199087", "process"),
                candidate(2, 102, "isbn13:9780201498387", "thread")), target, limit);
    }

    private MlRankV2Request.Candidate candidate(
            long projectionId, long bookId, String mlBookId, String concept) {
        return new MlRankV2Request.Candidate(
                projectionId, bookId, mlBookId, Map.of("operating-systems", 1.0),
                List.of(new MlRankV2Request.Concept(concept, 1.0)), List.of(),
                null, null, null, null, "book-v1", "features-v1", HASH);
    }

    private MlRankV2Result.Item item(String bookId, int rank, double readiness) {
        return new MlRankV2Result.Item(
                bookId, rank, "personalizable", readiness,
                1, 2, 0.5, 0.2, 1, 1, 1.0,
                List.of("process"), List.of("concurrency", "thread"),
                List.of("선행 개념 근거", "직접 학습 기회"), MlRankV2Request.MODEL,
                "book-v1", "features-v1", HASH);
    }

    private MlRankV2Result result(
            List<MlRankV2Result.Item> items, MlRankV2Result.Diagnostics diagnostics) {
        return new MlRankV2Result(
                42, "operating-systems", items, diagnostics,
                MlRankV2Request.MODEL, "ranking-v2-config-v1", HASH,
                "concept-graph-v1", HASH, "concept-graph-reviews-v1", HASH,
                "reader-v1", "reader-config-v1", HASH);
    }
}
