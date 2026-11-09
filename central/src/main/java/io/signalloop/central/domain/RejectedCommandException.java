package io.signalloop.central.domain;

import java.util.List;

public class RejectedCommandException extends RuntimeException {

    private final List<FieldViolation> violations;

    public RejectedCommandException(List<FieldViolation> violations) {
        super("Request rejected");
        this.violations = List.copyOf(violations);
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
