package com.aimall.server.common;

import cn.dev33.satoken.exception.NotLoginException;
import com.aimall.server.exception.BusinessException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException exception) {
        if (exception.getStatus().is5xxServerError()) {
            log.error("Business dependency failure, errorCode={}", exception.getErrorCode(), exception);
        } else {
            log.warn("Business request rejected, errorCode={}, message={}",
                    exception.getErrorCode(), exception.getMessage());
        }
        return ResponseEntity.status(exception.getStatus()).body(ApiResponse.fail(
                exception.getErrorCode(), safeBusinessMessage(exception.getMessage()), exception.getDetails()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException exception) {
        log.warn("Invalid request argument: {}", exception.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(
                "INVALID_ARGUMENT", safeBusinessMessage(exception.getMessage()), Map.of()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException exception) {
        Map<String, Object> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(ApiResponse.fail(
                "VALIDATION_FAILED", "请求参数校验失败", Map.of("fields", fields)
        ));
    }

    @ExceptionHandler({ConstraintViolationException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(Exception exception) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(
                "VALIDATION_FAILED", "请求参数校验失败", Map.of("reason", safeBusinessMessage(exception.getMessage()))
        ));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(
                "MALFORMED_REQUEST", "请求体格式错误", Map.of()
        ));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUpload(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ApiResponse.fail(
                "PAYLOAD_TOO_LARGE", "上传文件超过大小限制", Map.of()
        ));
    }

    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotLoginException(NotLoginException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail(
                "UNAUTHORIZED", "请先登录或登录已失效", Map.of()
        ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatus(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(ApiResponse.fail(
                "HTTP_" + exception.getStatusCode().value(),
                exception.getReason() == null ? "请求无法处理" : safeBusinessMessage(exception.getReason()),
                Map.of()
        ));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException exception) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ApiResponse.fail(
                "METHOD_NOT_ALLOWED", "请求方法不支持", Map.of("method", exception.getMethod())
        ));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiResponse<Void>> handleLegacyRuntimeException(RuntimeException exception) {
        log.error("Legacy runtime request failure", exception);
        if (isDatabaseUnavailable(exception)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(ApiResponse.fail(
                    "DEPENDENCY_UNAVAILABLE",
                    "数据库服务暂时不可用，请稍后重试",
                    Map.of("dependency", "mysql", "retryable", true)
            ));
        }
        String message = safeBusinessMessage(exception.getMessage());
        HttpStatus status = legacyStatusFor(message);
        String errorCode = switch (status) {
            case UNAUTHORIZED -> "UNAUTHORIZED";
            case FORBIDDEN -> "FORBIDDEN";
            case NOT_FOUND -> "RESOURCE_NOT_FOUND";
            default -> "BUSINESS_CONFLICT";
        };
        return ResponseEntity.status(status).body(ApiResponse.fail(errorCode, message, Map.of("legacy", true)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception exception) {
        log.error("Unexpected request failure", exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.fail(
                "INTERNAL_ERROR", "服务器开小差了，请稍后再试", Map.of()
        ));
    }

    private HttpStatus legacyStatusFor(String message) {
        if (message.contains("请先登录") || message.contains("登录已失效")) return HttpStatus.UNAUTHORIZED;
        if (message.contains("无权限") || message.contains("无管理员权限") || message.contains("缺少权限")) {
            return HttpStatus.FORBIDDEN;
        }
        if (message.contains("不存在")) return HttpStatus.NOT_FOUND;
        return HttpStatus.CONFLICT;
    }

    private boolean isDatabaseUnavailable(Throwable exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 12; depth++, current = current.getCause()) {
            if (current instanceof CannotGetJdbcConnectionException
                    || current instanceof DataAccessResourceFailureException
                    || current instanceof SQLTransientConnectionException
                    || current instanceof SQLNonTransientConnectionException) {
                return true;
            }
            if (current instanceof SQLException sqlException && sqlException.getSQLState() != null
                    && sqlException.getSQLState().startsWith("08")) {
                return true;
            }
            String className = current.getClass().getName().toLowerCase();
            if (className.contains("communicationsException".toLowerCase())
                    || className.contains("jdbcconnectionexception")) {
                return true;
            }
        }
        return false;
    }

    private String safeBusinessMessage(String message) {
        if (message == null || message.isBlank()) return "请求处理失败";
        String lower = message.toLowerCase();
        if (lower.contains("java.") || lower.contains("sql") || lower.contains("exception")
                || lower.contains("mapper") || lower.contains("stack")) {
            return "请求处理失败，请稍后重试";
        }
        return message.substring(0, Math.min(500, message.length()));
    }
}
