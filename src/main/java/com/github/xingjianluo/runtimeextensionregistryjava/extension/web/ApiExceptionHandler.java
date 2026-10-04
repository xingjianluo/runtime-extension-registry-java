package com.github.xingjianluo.runtimeextensionregistryjava.extension.web;

import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.extension.error.ExtensionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 将所有异常归一化为 {@link ApiError}。
 *
 * <p>对外只暴露：错误码、面向调用方的消息、可定位上下文（如 extensionId、blockedBy、
 * 回显的入参 config）。底层异常的原始消息与堆栈仅记录在服务端日志，不随响应透出。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** 这些错误码的 detail 可能携带扩展内部异常原文，在 API 边界一律剥离。 */
    private static final Set<ErrorCode> STRIP_DETAIL = Set.of(
            ErrorCode.INVALID_CONFIG,
            ErrorCode.INITIALIZATION_FAILED,
            ErrorCode.EXECUTION_FAILED,
            ErrorCode.INTERNAL_ERROR);

    @ExceptionHandler(ExtensionException.class)
    public ResponseEntity<ApiError> handleExtension(ExtensionException ex) {
        // 完整原因（含 cause 链）只进服务端日志，便于定位。
        if (ex.getCause() != null || ex.code() == ErrorCode.INTERNAL_ERROR
                || ex.code() == ErrorCode.EXECUTION_FAILED
                || ex.code() == ErrorCode.INITIALIZATION_FAILED) {
            log.warn("api failure: code={}, message={}", ex.code(), ex.getMessage(), ex);
        } else {
            log.warn("api rejection: code={}, message={}, context={}", ex.code(), ex.getMessage(), ex.context());
        }
        Map<String, Object> context = new LinkedHashMap<>(ex.context());
        if (STRIP_DETAIL.contains(ex.code())) {
            context.remove("detail");
        }
        return ResponseEntity.status(statusOf(ex.code()))
                .body(new ApiError(ex.code().name(), safeMessage(ex), Map.copyOf(context)));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        log.warn("api bad request: unreadable body");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError(ErrorCode.BAD_REQUEST.name(), "request body is missing or malformed", Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        // 兜底：绝不向调用方透出堆栈或内部类名/消息。
        log.error("api internal error", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(ErrorCode.INTERNAL_ERROR.name(), "internal server error", Map.of()));
    }

    private static String safeMessage(ExtensionException ex) {
        // INVALID_CONFIG 的说明可能直接来自扩展抛出的原始异常，统一不外泄原文。
        if (STRIP_DETAIL.contains(ex.code())) {
            return switch (ex.code()) {
                case INVALID_CONFIG -> "extension configuration rejected";
                case INITIALIZATION_FAILED -> "extension initialization failed";
                case EXECUTION_FAILED -> "extension execution failed";
                case INTERNAL_ERROR -> "internal server error";
                default -> ex.getMessage();
            };
        }
        return ex.getMessage();
    }

    private static HttpStatus statusOf(ErrorCode code) {
        return switch (code) {
            case EXTENSION_NOT_FOUND, VERSION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BAD_REQUEST -> HttpStatus.BAD_REQUEST;
            case EXECUTION_FAILED -> HttpStatus.BAD_GATEWAY;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            // 所有"变更被拒绝/当前不可执行"类错误：状态冲突，请求未被接受、系统状态未变。
            default -> HttpStatus.CONFLICT;
        };
    }
}
