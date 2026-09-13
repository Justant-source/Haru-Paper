package com.harupaper.server.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.util.List;

/**
 * Global exception handler for the entire application.
 * Converts exceptions to ProblemDetail (RFC 9457) responses.
 *
 * Note: Domain packages throw exceptions, and this handler processes them.
 * 일부러 ResponseEntityExceptionHandler를 상속하지 않는다 — 상속하면 부모의
 * MethodArgumentNotValidException 처리기와 이 클래스의 처리기가 충돌한다(Ambiguous @ExceptionHandler).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NotFoundException ex, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        pd.setInstance(URI.create(request.getDescription(false).replace("uri=", "")));
        pd.setTitle("Not Found");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ProblemDetail> handleConflict(ConflictException ex, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        pd.setInstance(URI.create(request.getDescription(false).replace("uri=", "")));
        pd.setTitle("Conflict");
        pd.setProperty("errors", ex.getConflictingIds().stream()
                .map(id -> new ConflictError(id))
                .toList());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(pd);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ProblemDetail> handleValidation(ValidationException ex, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
        pd.setInstance(URI.create(request.getDescription(false).replace("uri=", "")));
        pd.setTitle("Validation failed");
        pd.setProperty("errors", ex.getFieldErrors().stream()
                .map(fe -> new ErrorDetail(fe.path(), fe.message()))
                .toList());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(pd);
    }

    @ExceptionHandler(UnsupportedMediaTypeAppException.class)
    public ResponseEntity<ProblemDetail> handleUnsupportedMediaType(UnsupportedMediaTypeAppException ex, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ex.getMessage());
        pd.setInstance(URI.create(request.getDescription(false).replace("uri=", "")));
        pd.setTitle("Unsupported Media Type");
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(pd);
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    public ResponseEntity<ProblemDetail> handlePayloadTooLarge(PayloadTooLargeException ex, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, ex.getMessage());
        pd.setInstance(URI.create(request.getDescription(false).replace("uri=", "")));
        pd.setTitle("Payload Too Large");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(pd);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleMaxUploadSizeExceeded(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex, WebRequest request) {
        // multipart 파서가 spring.servlet.multipart.max-file-size를 넘는 순간 컨트롤러 진입 전에
        // 던진다 — AssetService의 자체 크기 검증(413)보다 먼저 발생하므로 여기서도 413으로 잡는다.
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "upload exceeds size limit");
        pd.setInstance(URI.create(request.getDescription(false).replace("uri=", "")));
        pd.setTitle("Payload Too Large");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(pd);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                      WebRequest request) {
        List<ErrorDetail> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorDetail(fe.getField(), fe.getDefaultMessage()))
                .toList();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request");
        pd.setInstance(URI.create(request.getDescription(false).replace("uri=", "")));
        pd.setTitle("Bad Request");
        pd.setProperty("errors", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleNotReadable(HttpMessageNotReadableException ex, WebRequest request) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "malformed JSON request body");
        pd.setInstance(URI.create(request.getDescription(false).replace("uri=", "")));
        pd.setTitle("Bad Request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneral(Exception ex, WebRequest request) {
        // 500은 원인을 반드시 로그에 남긴다 — 무반응/무로그로 삼키지 않는다(CLAUDE.md 기록 규칙).
        log.error("Unhandled exception at {}: {}", request.getDescription(false), ex.getMessage(), ex);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred");
        pd.setInstance(URI.create(request.getDescription(false).replace("uri=", "")));
        pd.setTitle("Internal Server Error");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(pd);
    }

    record ErrorDetail(String path, String message) {
    }

    record ConflictError(String id) {
    }
}
