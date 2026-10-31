package io.campusflow.seats;

public final class CheckInException extends RuntimeException {
    public enum Code {
        RESERVATION_NOT_FOUND,
        CHECK_IN_STATE_CONFLICT,
        CHECK_IN_DEADLINE_PASSED,
        LEAVE_SEAT_STATE_CONFLICT,
        CHECK_IN_CODE_INVALID,
        CHECK_IN_RATE_LIMITED
    }
    private final Code code;
    public CheckInException(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
