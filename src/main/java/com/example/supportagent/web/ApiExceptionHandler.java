package com.example.supportagent.web;

import com.example.supportagent.tool.ToolAccessDeniedException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class})
    ResponseEntity<Map<String, Object>> missingEndpoint(Exception error) {
        return response(HttpStatus.NOT_FOUND, "NOT_FOUND", "接口不存在或未启用");
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> malformedBody(Exception error) {
        log.warn("HTTP rejected code=BAD_REQUEST malformed JSON");
        return response(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "请求 JSON 格式或字段类型错误，请显式提供 confirmWrite");
    }

    @ExceptionHandler(com.example.supportagent.concurrency.CapacityExceededException.class)
    ResponseEntity<Map<String, Object>> overloaded(com.example.supportagent.concurrency.CapacityExceededException error) {
        log.warn("HTTP rejected code=CAPACITY_EXCEEDED scope={}", error.scope());
        var result = response(HttpStatus.TOO_MANY_REQUESTS, "CAPACITY_EXCEEDED", error.getMessage());
        return ResponseEntity.status(result.getStatusCode()).header("Retry-After", "5").body(result.getBody());
    }

    @ExceptionHandler({com.example.supportagent.concurrency.LeaseLostException.class,
            org.springframework.data.redis.RedisConnectionFailureException.class,
            org.springframework.dao.QueryTimeoutException.class,
            org.springframework.dao.CannotAcquireLockException.class})
    ResponseEntity<Map<String, Object>> unavailable(RuntimeException error) {
        log.warn("Concurrency protection or database unavailable", error);
        return response(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", "服务暂时不可用；如涉及写入，请先核实操作结果");
    }

    @ExceptionHandler(com.example.supportagent.memory.ConversationBusyException.class)
    ResponseEntity<Map<String, Object>> busy(RuntimeException error) {
        return response(HttpStatus.CONFLICT, "CONVERSATION_BUSY", error.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class,
            ConstraintViolationException.class})
    ResponseEntity<Map<String, Object>> badRequest(Exception error) {
        log.warn("HTTP rejected code=BAD_REQUEST type={}", error.getClass().getSimpleName());
        return response(HttpStatus.BAD_REQUEST, "BAD_REQUEST", error.getMessage());
    }

    @ExceptionHandler({ForbiddenException.class, ToolAccessDeniedException.class})
    ResponseEntity<Map<String, Object>> forbidden(RuntimeException error) {
        log.warn("HTTP rejected code=FORBIDDEN type={}", error.getClass().getSimpleName());
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", error.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> internal(Exception error) {
        log.error("Unhandled request failure", error);
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "request failed");
    }

    private ResponseEntity<Map<String, Object>> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "code", code,
                "traceId", MDC.get("traceId") == null ? "" : MDC.get("traceId"),
                "message", message == null ? code : message
        ));
    }
}
