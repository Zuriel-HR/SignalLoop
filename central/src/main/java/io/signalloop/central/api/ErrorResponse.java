package io.signalloop.central.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.signalloop.central.domain.FieldViolation;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ErrorResponse(String code, String message, List<FieldError> fields) {

    public record FieldError(String field, String message) {
    }

    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, List.of());
    }

    public static ErrorResponse invalid(List<FieldViolation> violations) {
        return new ErrorResponse(
                "INVALID_REQUEST",
                "Request rejected",
                violations.stream().map(violation -> new FieldError(violation.field(), violation.message())).toList());
    }
}
