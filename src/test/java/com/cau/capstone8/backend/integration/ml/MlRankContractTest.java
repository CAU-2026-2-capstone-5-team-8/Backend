package com.cau.capstone8.backend.integration.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cau.capstone8.backend.recommendation.ChallengeLevel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MlRankContractTest {
    @Test
    void ranksByDescendingTotalThenAscendingBookIdOnTies() {
        // Both candidates end up with total=0.875 under BALANCED; book 1 must win the tie.
        MlRankRequest request = new MlRankRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000002"), "v1", "stub-profile-v1",
                0.6, 0.4, 0.8, ChallengeLevel.BALANCED, 5, List.of(
                        new MlRankRequest.Candidate(1, "v1", 0.5, 0.5, 0.5, 1.0),
                        new MlRankRequest.Candidate(2, "v1", 0.6, 0.4, 0.8, 0.5)));

        MlRankResult result = MlRankResponseValidator.validate(request, new StubMlGateway().rank(request));

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).bookId()).isEqualTo(1);
        assertThat(result.items().get(0).rank()).isEqualTo(1);
        assertThat(result.items().get(0).totalScore()).isEqualTo(0.875);
        assertThat(result.items().get(1).bookId()).isEqualTo(2);
        assertThat(result.items().get(1).rank()).isEqualTo(2);
        assertThat(result.modelVersion()).isEqualTo("stub-rank-v1");
        result.items().forEach(item -> assertThat(item.reasons()).hasSizeGreaterThanOrEqualTo(2));
    }

    @Test
    void challengeLevelShiftsTargetAbilityAndClampsToOne() {
        MlRankRequest balanced = request(ChallengeLevel.BALANCED);
        MlRankRequest challenging = request(ChallengeLevel.CHALLENGING);

        MlRankResult balancedResult = new StubMlGateway().rank(balanced);
        MlRankResult challengingResult = new StubMlGateway().rank(challenging);

        // profile vocabulary=0.9: BALANCED target=0.9, CHALLENGING target=clamp(1.1,0,1)=1.0.
        assertThat(balancedResult.items().get(0).vocabularyFit()).isEqualTo(0.9);
        assertThat(challengingResult.items().get(0).vocabularyFit()).isEqualTo(1.0);
    }

    @Test
    void limitsResultsToTopK() {
        MlRankRequest request = new MlRankRequest(
                UUID.randomUUID(), "v1", "stub-profile-v1", 0.5, 0.5, 0.5, ChallengeLevel.BALANCED, 1, List.of(
                        new MlRankRequest.Candidate(1, "v1", 0.5, 0.5, 0.5, 1.0),
                        new MlRankRequest.Candidate(2, "v1", 0.4, 0.4, 0.4, 0.4)));

        MlRankResult result = MlRankResponseValidator.validate(request, new StubMlGateway().rank(request));

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).bookId()).isEqualTo(1);
    }

    @Test
    void rejectsResponsesThatViolateTheContract() {
        MlRankRequest request = request(ChallengeLevel.BALANCED);
        MlRankResult valid = new StubMlGateway().rank(request);

        MlRankResult wrongCount = new MlRankResult(
                request.requestId(), request.contractVersion(), "stub-rank-v1", List.of());
        MlRankResult unrequestedBook = new MlRankResult(
                request.requestId(), request.contractVersion(), "stub-rank-v1", List.of(new MlRankResult.Item(
                        999, 1, 0.9, 0.9, 0.9, 0.9, 0.9, List.of("이유1", "이유2"))));
        MlRankResult tooFewReasons = new MlRankResult(
                request.requestId(), request.contractVersion(), "stub-rank-v1",
                List.of(new MlRankResult.Item(1, 1, 0.9, 0.9, 0.9, 0.9, 0.9, List.of("이유1"))));

        assertThatThrownBy(() -> MlRankResponseValidator.validate(request, wrongCount))
                .isInstanceOf(MlGatewayException.class);
        assertThatThrownBy(() -> MlRankResponseValidator.validate(request, unrequestedBook))
                .isInstanceOf(MlGatewayException.class);
        assertThatThrownBy(() -> MlRankResponseValidator.validate(request, tooFewReasons))
                .isInstanceOf(MlGatewayException.class);
        assertThat(valid).isNotNull();
    }

    private MlRankRequest request(ChallengeLevel level) {
        return new MlRankRequest(
                UUID.randomUUID(), "v1", "stub-profile-v1", 0.9, 0.5, 0.5, level, 5, List.of(
                        new MlRankRequest.Candidate(1, "v1", 1.0, 0.5, 0.5, 1.0)));
    }
}
