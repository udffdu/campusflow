package io.campusflow.seats;

public final class ReservationPersistenceException extends RuntimeException {
    public enum Code { USER_TIME_CONFLICT, SEAT_TIME_CONFLICT, IDEMPOTENCY_CONFLICT, STORAGE_UNAVAILABLE }
    private final Code code;

    public ReservationPersistenceException(Code code) {
        super(code.name());
        this.code = code;
    }

    public ReservationPersistenceException(Code code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public Code code() { return code; }
}
