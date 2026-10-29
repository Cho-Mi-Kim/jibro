package org.jibro.global.common;

import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
    public record Error(String code, String message, List<?> fieldErrors, Instant timestamp) {}
    @ExceptionHandler(ApiException.class)
    ResponseEntity<Error> handle(ApiException e) {
        return ResponseEntity.status(e.status).body(new Error(e.code,e.getMessage(),List.of(),Instant.now()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,IllegalArgumentException.class})
    ResponseEntity<Error> invalid(Exception e) {
        return handle(new ApiException(400,"INVALID_INPUT","입력 형식과 필수 항목을 확인해 주세요."));
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Error> duplicate(DataIntegrityViolationException e) {
        return handle(new ApiException(409,"DUPLICATE","이미 등록되었거나 다른 작업과 충돌했습니다."));
    }
}
