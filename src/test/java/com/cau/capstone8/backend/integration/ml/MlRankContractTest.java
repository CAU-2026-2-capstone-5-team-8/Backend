package com.cau.capstone8.backend.integration.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cau.capstone8.backend.recommendation.ChallengeLevel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MlRankContractTest {
    @Test
    void stubRanksByFourComponentMeanThenBookId() {
        MlRankRequest request = request(null, 2);

        MlRankResult first = MlRankResponseValidator.validate(
                request, new StubMlGateway().rankBooks(request));
        MlRankResult second = MlRankResponseValidator.validate(
                request, new StubMlGateway().rankBooks(request));

        assertThat(first).isEqualTo(second);
        assertThat(first.modelVersion()).isEqualTo("stub-rank-v1");
        assertThat(first.items()).extracting(MlRankResult.Item::bookId)
                .containsExactly(10L, 20L);
        assertThat(first.items().getFirst().score()).isEqualTo(1.0);
        assertThat(first.items().getFirst().reasons()).hasSize(4);
    }

    @Test
    void targetModeReturnsOnlyRequestedBook() {
        MlRankRequest request = request(20L, 20);

        MlRankResult result = MlRankResponseValidator.validate(
                request, new StubMlGateway().rankBooks(request));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().bookId()).isEqualTo(20L);
        assertThat(result.items().getFirst().rank()).isEqualTo(1);
    }

    @Test
    void validatorRejectsUnknownBooksAndNonFiniteScores() {
        MlRankRequest request = request(null, 2);
        MlRankResult valid = new StubMlGateway().rankBooks(request);
        MlRankResult unknownBook = new MlRankResult(
                valid.requestId(),
                valid.contractVersion(),
                valid.modelVersion(),
                List.of(
                        item(999, 1, 0.5),
                        item(20, 2, 0.5)));
        MlRankResult nonFinite = new MlRankResult(
                valid.requestId(),
                valid.contractVersion(),
                valid.modelVersion(),
                List.of(
                        item(10, 1, Double.NaN),
                        item(20, 2, 0.5)));
        MlRankResult oversizedVersion = new MlRankResult(
                valid.requestId(),
                valid.contractVersion(),
                "v".repeat(81),
                valid.items());

        assertThatThrownBy(() -> MlRankResponseValidator.validate(request, unknownBook))
                .isInstanceOf(MlGatewayException.class);
        assertThatThrownBy(() -> MlRankResponseValidator.validate(request, nonFinite))
                .isInstanceOf(MlGatewayException.class);
        assertThatThrownBy(() -> MlRankResponseValidator.validate(request, oversizedVersion))
                .isInstanceOf(MlGatewayException.class);
    }

    private MlRankRequest request(Long targetBookId, int limit) {
        return new MlRankRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000010"),
                "v1",
                new MlRankRequest.Reader(1, 0.5, 0.5, 0.5, "reader-v1"),
                ChallengeLevel.BALANCED,
                List.of(
                        new MlRankRequest.Candidate(100, 10, "feature-v1", 0.5, 0.5, 0.5, 1),
                        new MlRankRequest.Candidate(200, 20, "feature-v1", 0.8, 0.8, 0.8, 1)),
                targetBookId,
                limit);
    }

    private MlRankResult.Item item(long bookId, int rank, double score) {
        return new MlRankResult.Item(
                bookId,
                rank,
                score,
                1,
                0.5,
                0.5,
                0.5,
                List.of("첫 번째 근거", "두 번째 근거"));
    }
}
