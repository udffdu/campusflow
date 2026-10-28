package io.campusflow.seats.http;

import io.campusflow.seats.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public final class QueryErrors {
    @ExceptionHandler(Exception.class) ResponseEntity<Map<String,Object>> error(Exception e,HttpServletRequest request) {
        int status=500; String code="INTERNAL_ERROR",message="查询暂不可用，请稍后重试";
        if(e instanceof QueryProblem p) {status=p.status;code=p.code;message=p.getMessage();}
        else if(e instanceof ReservationRuleException p) {status=400;code=p.code().name();message=p.getMessage();}
        else if(e instanceof SecurityException) {status=403;code="FORBIDDEN";message="缺少权限或教室不在本人开放范围内";}
        else if(e instanceof ReservationPersistenceException || e instanceof org.springframework.dao.DataAccessException) {status=503;code="STORAGE_UNAVAILABLE";}
        else if(e instanceof NoResourceFoundException) {status=404;code="NOT_FOUND";message="查询接口不存在";}
        return ResponseEntity.status(status).body(Map.of("code",code,"message",message,"requestId",String.valueOf(request.getAttribute("m1.requestId"))));
    }
}
