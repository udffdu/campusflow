package io.campusflow.platform;

import jakarta.servlet.http.*;
import jakarta.validation.ConstraintViolationException;
import java.io.IOException;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import com.fasterxml.jackson.databind.ObjectMapper;

@RestControllerAdvice
public class ApiErrors {
    public record Error(String code, String message, String requestId) {}
    static String requestId(HttpServletRequest request) { return Objects.toString(request.getAttribute("requestId"),"unknown"); }
    static void write(HttpServletRequest req, HttpServletResponse res, ObjectMapper mapper, int status, String code, String message) throws IOException {
        res.setStatus(status); res.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(res.getWriter(),new Error(code,message,requestId(req)));
    }
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Error> api(ApiException ex,HttpServletRequest req) { return ResponseEntity.status(ex.status).body(new Error(ex.code,ex.getMessage(),requestId(req))); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class, ConstraintViolationException.class})
    ResponseEntity<Error> invalid(Exception ex,HttpServletRequest req) { return ResponseEntity.badRequest().body(new Error("VALIDATION","输入格式不正确，请检查必填项、长度、时间和字段名称",requestId(req))); }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Error> integrity(Exception ex,HttpServletRequest req) { return ResponseEntity.status(409).body(new Error("CONFLICT","编号已存在，或关联数据已发生变化",requestId(req))); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Error> unexpected(Exception ex,HttpServletRequest req) {
        // Never log request payloads or exception messages that may contain credentials or SQL parameters.
        LoggerFactory.getLogger(ApiErrors.class).error("Request {} failed: {}",requestId(req),ex.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(new Error("INTERNAL","服务暂时不可用，请稍后重试",requestId(req)));
    }
}
