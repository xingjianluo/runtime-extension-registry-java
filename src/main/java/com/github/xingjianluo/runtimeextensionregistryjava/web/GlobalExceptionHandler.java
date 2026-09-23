package com.github.xingjianluo.runtimeextensionregistryjava.web;

import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionErrorCode;
import com.github.xingjianluo.runtimeextensionregistryjava.error.ExtensionException;
import com.github.xingjianluo.runtimeextensionregistryjava.web.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Maps every failure to a stable error code + safe message.
 * Raw exception messages and stack traces are logged server-side only and never
 * serialized to clients.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ExtensionException.class)
    public ResponseEntity<ErrorResponse> handleExtension(ExtensionException e) {
        // Cause details are kept on the server; context carries only locating information.
        if (e.getCause() != null) {
            LOG.warn("normalized failure code={} message={} context={}",
                    e.errorCode(), e.getMessage(), e.context(), e.getCause());
        }
        return ResponseEntity.status(statusFor(e.errorCode()))
                .body(new ErrorResponse(e.errorCode().name(), e.getMessage(), e.context()));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentNotValidException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        LOG.warn("bad request: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ExtensionErrorCode.INVALID_REQUEST.name(),
                        "malformed request", Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleOther(Exception e) {
        LOG.error("unexpected failure", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(ExtensionErrorCode.EXECUTION_FAILED.name(),
                        "internal processing error", Map.of()));
    }

    static HttpStatus statusFor(ExtensionErrorCode e) {
        return switch (e) {
            case UNKNOWN_EXTENSION -> HttpStatus.NOT_FOUND;
            case DUPLICATE_EXTENSION_ID, CYCLIC_DEPENDENCY, CHANGE_SET_REJECTED ->
                    HttpStatus.CONFLICT;
            case UNKNOWN_EXTENSION_KIND, INVALID_VERSION, INVALID_VERSION_RANGE,
                    VERSION_INCOMPATIBLE, DEPENDENCY_NOT_FOUND, DEPENDENCY_NOT_READY,
                    EXTENSION_NOT_READY, INVALID_CONFIG, INITIALIZATION_FAILED,
                    INVALID_REQUEST -> HttpStatus.UNPROCESSABLE_ENTITY;
            case EXECUTION_FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
