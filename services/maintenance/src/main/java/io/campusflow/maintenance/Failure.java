package io.campusflow.maintenance;
final class Failure extends RuntimeException {
    final int status; final String code;
    Failure(int status,String code) { super(code);this.status=status;this.code=code; }
}
