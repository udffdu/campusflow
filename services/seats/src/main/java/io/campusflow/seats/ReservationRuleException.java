package io.campusflow.seats;

public final class ReservationRuleException extends IllegalArgumentException {
    public enum Code { INVALID_INTERVAL, NOT_WHOLE_HOUR, IN_PAST, ADVANCE_WINDOW, DURATION_LIMIT }

    private final Code code;

    public ReservationRuleException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
