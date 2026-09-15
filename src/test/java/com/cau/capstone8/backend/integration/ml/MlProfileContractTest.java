package com.cau.capstone8.backend.integration.ml;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.cau.capstone8.backend.assessment.MeasurementArea;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MlProfileContractTest {
    @Test
    void stubCalculatesKnownRatioPerDimensionDeterministically() {
        MlProfileRequest request = request();
        StubMlGateway gateway = new StubMlGateway();

        MlProfileResult first = MlProfileResponseValidator.validate(
                request, gateway.calculateProfile(request));
        MlProfileResult second = MlProfileResponseValidator.validate(
                request, gateway.calculateProfile(request));

        assertThat(first).isEqualTo(second);
        assertThat(first.vocabulary()).isEqualTo(0.5);
        assertThat(first.backgroundKnowledge()).isEqualTo(1.0);
        assertThat(first.comprehension()).isEqualTo(0.0);
        assertThat(first.calculationVersion()).isEqualTo("stub-profile-v1");
        assertThat(first.dimensionCounts()).containsExactlyInAnyOrderEntriesOf(Map.of(
                MeasurementArea.VOCABULARY, 2,
                MeasurementArea.BACKGROUND_KNOWLEDGE, 1,
                MeasurementArea.COMPREHENSION, 1));
    }

    @Test
    void rejectsMismatchedRequestIdAndInvalidScores() {
        MlProfileRequest request = request();
        MlProfileResult valid = new StubMlGateway().calculateProfile(request);
        MlProfileResult wrongRequest = new MlProfileResult(
                UUID.randomUUID(),
                valid.contractVersion(),
                valid.calculationVersion(),
                valid.vocabulary(),
                valid.backgroundKnowledge(),
                valid.comprehension(),
                valid.dimensionCounts(),
                valid.evidence());
        MlProfileResult invalidScore = new MlProfileResult(
                valid.requestId(),
                valid.contractVersion(),
                valid.calculationVersion(),
                Double.NaN,
                valid.backgroundKnowledge(),
                valid.comprehension(),
                valid.dimensionCounts(),
                valid.evidence());

        assertThatThrownBy(() -> MlProfileResponseValidator.validate(request, wrongRequest))
                .isInstanceOf(MlGatewayException.class)
                .hasMessageContaining("계약");
        assertThatThrownBy(() -> MlProfileResponseValidator.validate(request, invalidScore))
                .isInstanceOf(MlGatewayException.class)
                .hasMessageContaining("계약");
    }

    @Test
    void rejectsRequestWithoutEveryMeasurementArea() {
        assertThatThrownBy(() -> new MlProfileRequest(
                UUID.randomUUID(),
                "v1",
                1,
                "assessment-1",
                "OS",
                List.of(answer("1", MeasurementArea.VOCABULARY, true))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("measurement areas");
    }

    private MlProfileRequest request() {
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        return new MlProfileRequest(
                requestId,
                "v1",
                1,
                "assessment-1",
                "OS",
                List.of(
                        answer("1", MeasurementArea.VOCABULARY, true),
                        answer("2", MeasurementArea.VOCABULARY, false),
                        answer("3", MeasurementArea.BACKGROUND_KNOWLEDGE, true),
                        answer("4", MeasurementArea.COMPREHENSION, false)));
    }

    private MlProfileRequest.Answer answer(
            String questionId,
            MeasurementArea area,
            boolean knowsConcept) {
        return new MlProfileRequest.Answer(
                questionId, area, "concept-" + questionId, 3, knowsConcept, 1.0);
    }
}
