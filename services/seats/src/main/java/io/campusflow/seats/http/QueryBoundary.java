package io.campusflow.seats.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Writes require an explicitly installed ordinary or legacy-demo gateway; other methods stay closed. */
@Component
public final class QueryBoundary extends OncePerRequestFilter {
    private final ObjectMapper json;
    private final org.springframework.beans.factory.ObjectProvider<ReservationWriteAccess> permit;
    public QueryBoundary(ObjectMapper json,org.springframework.beans.factory.ObjectProvider<ReservationWriteAccess> permit) { this.json=json;this.permit=permit; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        String id=UUID.randomUUID().toString(); request.setAttribute("m1.requestId",id);
        response.setHeader("X-Request-ID",id); response.setHeader("Cache-Control","no-store");
        if(!request.getMethod().equals("GET") && !(request.getMethod().equals("POST") && request.getRequestURI().equals("/api/m1/v1/reservations") && permit.getIfAvailable()!=null)) {
            response.setStatus(405); response.setHeader("Allow","GET"); response.setContentType("application/json");
            json.writeValue(response.getOutputStream(),Map.of("code","READ_ONLY","message","当前仅支持查询，预约暂未开放","requestId",id));
            return;
        }
        chain.doFilter(request,response);
    }
}
