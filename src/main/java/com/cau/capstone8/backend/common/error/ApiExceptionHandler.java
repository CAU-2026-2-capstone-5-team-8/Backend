package com.cau.capstone8.backend.common.error;

import com.cau.capstone8.backend.assessment.AssessmentStateConflictException;
import com.cau.capstone8.backend.assessment.QuestionBankUnavailableException;
import com.cau.capstone8.backend.integration.ml.MlGatewayException;
import com.cau.capstone8.backend.recommendation.RecommendationConflictException;
import com.cau.capstone8.backend.recommendation.RecommendationFailureException;
import com.cau.capstone8.backend.recommendation.RecommendationInputException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    public record ApiError(String code, String message, String traceId) {}

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> missing(ResourceNotFoundException ex) {
        return error(404, "NOT_FOUND", ex.getMessage());
    }
    @ExceptionHandler(QuestionBankUnavailableException.class)
    ResponseEntity<ApiError> questionBankUnavailable(QuestionBankUnavailableException ex) {
        return error(409, "QUESTION_BANK_UNAVAILABLE", ex.getMessage());
    }
    @ExceptionHandler(AssessmentStateConflictException.class)
    ResponseEntity<ApiError> assessmentStateConflict(AssessmentStateConflictException ex) {
        return error(409, "ASSESSMENT_STATE_CONFLICT", ex.getMessage());
    }
    @ExceptionHandler(MlGatewayException.class)
    ResponseEntity<ApiError> mlGateway(MlGatewayException ex) {
        int status = switch (ex.getFailureCode()) {
            case "ML_TIMEOUT" -> 504;
            case "ML_UNAVAILABLE" -> 503;
            default -> 502;
        };
        return error(status, ex.getFailureCode(), "ML 서비스 응답을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }
    @ExceptionHandler(RecommendationConflictException.class)
    ResponseEntity<ApiError> recommendationConflict(RecommendationConflictException ex) {
        return error(409, "RECOMMENDATION_CONFLICT", ex.getMessage());
    }
    @ExceptionHandler(RecommendationInputException.class)
    ResponseEntity<ApiError> recommendationInput(RecommendationInputException ex) {
        return error(422, "RECOMMENDATION_INPUT_UNAVAILABLE", ex.getMessage());
    }
    @ExceptionHandler(RecommendationFailureException.class)
    ResponseEntity<ApiError> recommendationFailure(RecommendationFailureException ex) {
        return error(ex.getHttpStatus(), ex.getFailureCode(), ex.getMessage());
    }
    @ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    ResponseEntity<ApiError> invalid(Exception ex) {
        return error(400, "INVALID_REQUEST", "요청 값의 형식과 범위를 확인해 주세요.");
    }
    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> route(NoResourceFoundException ex) {
        return error(404, "NOT_FOUND", "요청한 경로를 찾을 수 없습니다.");
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception ex) {
        if (ex instanceof ErrorResponse response && response.getStatusCode().is4xxClientError()) {
            return error(response.getStatusCode().value(), "INVALID_REQUEST", "지원하지 않는 요청입니다.");
        }
        String trace = UUID.randomUUID().toString();
        LOG.error("Unhandled API failure traceId={}", trace, ex);
        return ResponseEntity.status(500).body(new ApiError("INTERNAL_ERROR", "요청 처리 중 오류가 발생했습니다.", trace));
    }
    private ResponseEntity<ApiError> error(int status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, UUID.randomUUID().toString()));
    }
}
