package io.campusflow.platform;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    final HttpStatus status;
    final String code;
    public ApiException(HttpStatus status, String code, String message) { super(message); this.status=status; this.code=code; }
    public static ApiException bad(String code, String message) { return new ApiException(HttpStatus.BAD_REQUEST,code,message); }
    public static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND,"NOT_FOUND","记录不存在"); }
    public static ApiException denied() { return new ApiException(HttpStatus.FORBIDDEN,"FORBIDDEN","没有执行此操作的权限或数据范围"); }
    public static ApiException conflict(String message) { return new ApiException(HttpStatus.CONFLICT,"CONFLICT",message); }
}
