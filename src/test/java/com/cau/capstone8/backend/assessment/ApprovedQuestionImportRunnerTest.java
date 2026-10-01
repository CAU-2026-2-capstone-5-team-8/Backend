package com.cau.capstone8.backend.assessment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ApprovedQuestionImportRunnerTest {
    @Test
    void acceptsEitherManifestOrSingleFileMode() {
        assertThatCode(() -> runner("manifest.json", "", "", "")).doesNotThrowAnyException();
        assertThatCode(() -> runner("", "generated.json", "reviews.jsonl", ""))
                .doesNotThrowAnyException();
        assertThatCode(() -> runner("", "generated.json", "reviews.jsonl", "grounding.json"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMixedOrIncompleteConfiguration() {
        assertThatThrownBy(() -> runner("manifest.json", "generated.json", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be combined");
        assertThatThrownBy(() -> runner("manifest.json", "", "", "grounding.json"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be combined");
        assertThatThrownBy(() -> runner("", "generated.json", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires manifest-path");
        assertThatThrownBy(() -> runner("", "", "", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires manifest-path");
    }

    private static ApprovedQuestionImportRunner runner(
            String manifest, String generated, String reviews, String grounding) {
        return new ApprovedQuestionImportRunner(null, manifest, generated, reviews, grounding);
    }
}
