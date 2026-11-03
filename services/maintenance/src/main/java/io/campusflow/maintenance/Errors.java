package io.campusflow.maintenance;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.*;
@RestControllerAdvice
class Errors {
    private ResponseEntity<Map<String,String>> error(int status,String code) { return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("code",code,"message",code,"requestId",UUID.randomUUID().toString())); }
    @ExceptionHandler(Failure.class) ResponseEntity<Map<String,String>> fail(Failure e) { return error(e.status,e.code); }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class}) ResponseEntity<Map<String,String>> input(Exception e) { return error(400,"INVALID_INPUT"); }
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class) ResponseEntity<Map<String,String>> method(Exception e) { return error(405,"METHOD_NOT_ALLOWED"); }
    @ExceptionHandler(Exception.class) ResponseEntity<Map<String,String>> other(Exception e) { return error(500,"INTERNAL_ERROR"); }
}
