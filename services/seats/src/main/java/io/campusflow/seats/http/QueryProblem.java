package io.campusflow.seats.http;

final class QueryProblem extends RuntimeException {
    final int status;
    final String code;
    QueryProblem(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    static QueryProblem upstream() { return new QueryProblem(503,"D_UNAVAILABLE","身份或资源服务暂不可用，请稍后重试"); }
    static QueryProblem invalid() { return new QueryProblem(400,"INVALID_QUERY","查询参数无效，请检查教室、整点时段和分页"); }
}
