package com.nicodim.ocapp.api;

import com.nicodim.ocapp.support.ConversionException;
import com.nicodim.ocapp.support.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ConversionException.class)
    ResponseEntity<Map<String, Object>> conversion(ConversionException ex, HttpServletRequest request) {
        log.warn("request failed code={} status={} method={} path={}", ex.code(), ex.status().value(), request.getMethod(), request.getRequestURI());
        HttpHeaders headers = new HttpHeaders();
        if (ex.status() == HttpStatus.TOO_MANY_REQUESTS) headers.set(HttpHeaders.RETRY_AFTER, "1");
        return response(ex.status(), ex.code(), ex.getMessage(), request, headers);
    }

    @Override protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                                        HttpStatusCode statusCode, WebRequest webRequest) {
        HttpServletRequest request = ((ServletWebRequest) webRequest).getRequest();
        HttpHeaders preserved = new HttpHeaders();
        preserved.putAll(headers);
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        if (status == null) status = HttpStatus.INTERNAL_SERVER_ERROR;
        String code = isInvalidRequest(ex) ? "INVALID_REQUEST" : "HTTP_" + status.value();
        String detail = isInvalidRequest(ex) ? "Request body must contain a non-empty URL string" : status.getReasonPhrase();
        ResponseEntity<Map<String, Object>> result = response(status, code, detail, request, preserved);
        return new ResponseEntity<>(result.getBody(), result.getHeaders(), result.getStatusCode());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception ex, HttpServletRequest request) {
        log.error("unexpected request failure method={} path={} exceptionType={}", request.getMethod(), request.getRequestURI(), ex.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An internal error occurred", request, new HttpHeaders());
    }

    private static boolean isInvalidRequest(Exception ex) {
        return ex instanceof MethodArgumentNotValidException || ex instanceof HandlerMethodValidationException
            || ex instanceof HttpMessageNotReadableException;
    }

    private static ResponseEntity<Map<String, Object>> response(HttpStatus status, String code, String detail, HttpServletRequest request, HttpHeaders headers) {
        String correlationId = MDC.get("correlationId");
        if (correlationId == null) correlationId = request.getHeader(CorrelationIdFilter.HEADER);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", URI.create("about:blank").toString()); body.put("title", status.getReasonPhrase()); body.put("status", status.value());
        body.put("code", code); body.put("detail", detail); body.put("correlationId", correlationId == null ? "unknown" : correlationId); body.put("timestamp", Instant.now().toString());
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(body, headers, status);
    }
}
