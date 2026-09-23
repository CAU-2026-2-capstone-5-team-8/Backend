package com.cau.capstone8.backend.common.error;

import static org.assertj.core.api.Assertions.assertThat;
import com.cau.capstone8.backend.integration.ml.MlGatewayException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MlHttpErrorMappingTest {
    @ParameterizedTest
    @CsvSource({"ML_TIMEOUT,504", "ML_UNAVAILABLE,503", "ML_INVALID_RESPONSE,502", "ML_UPSTREAM_ERROR,502"})
    void mapsSanitizedFailures(String code, int status) {
        var response = new ApiExceptionHandler().mlGateway(new MlGatewayException(code, "secret upstream"));
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().toString()).doesNotContain("secret upstream");
    }
}
