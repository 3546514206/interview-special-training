package edu.zjnu.interview.springlabs.web;

import edu.zjnu.interview.springlabs.web.dto.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理：所有错误响应体固定为 {"error":"<msg>"}。
 *
 * <ul>
 *   <li>参数校验失败 → 400，msg 取字段上的校验提示，如 "message 不能为空"</li>
 *   <li>请求体缺失/格式错误 → 400，msg 固定为 "message 不能为空"（与契约保持一致）</li>
 *   <li>未匹配到任何 handler/静态资源 → 404</li>
 *   <li>请求方法 / Content-Type 不支持 → 405 / 415</li>
 *   <li>其它未捕获异常 → 500，msg 取异常 message（如 /api/boom 的 "boom"）</li>
 * </ul>
 *
 * <p>注意：这里逐个声明 Spring MVC 的标准异常，而不是继承 ResponseEntityExceptionHandler，
 * 目的是完全掌控状态码与响应文案，避免兜底分支把 404/405 误判成 500。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** @Valid 校验失败（POST /api/echo 的空 message 走这里）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        return badRequest(firstFieldMessage(ex.getBindingResult().getFieldErrors()));
    }

    /** 表单/查询参数绑定失败。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ErrorResponse> handleBind(BindException ex) {
        return badRequest(firstFieldMessage(ex.getBindingResult().getFieldErrors()));
    }

    /** 请求体缺失或不是合法 JSON。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        return badRequest("message 不能为空");
    }

    /** 缺少必填查询参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex) {
        return badRequest("缺少必要参数: " + ex.getParameterName());
    }

    /** 查询参数类型不匹配，例如 /api/slow?ms=abc。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("参数格式不正确: " + ex.getName());
    }

    /** 方法级参数校验失败（@Validated + 约束注解）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(violation -> violation.getMessage())
                .filter(msg -> msg != null && !msg.isEmpty())
                .findFirst()
                .orElse("参数校验失败");
        return badRequest(message);
    }

    /** 请求方法不支持，例如 GET /api/echo。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(new ErrorResponse("不支持的请求方法: " + ex.getMethod()));
    }

    /** Content-Type 不支持，例如用 text/plain 调 POST /api/echo。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ErrorResponse("不支持的 Content-Type: " + ex.getContentType()));
    }

    /** 没有匹配的 handler 或静态资源。 */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(Exception ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("资源不存在"));
    }

    /** 兜底：未预期的异常统一返回 500，msg 取异常 message。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        String message = (ex.getMessage() == null || ex.getMessage().isEmpty())
                ? "服务器内部错误"
                : ex.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(message));
    }

    private ResponseEntity<ErrorResponse> badRequest(String message) {
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }

    private String firstFieldMessage(java.util.List<FieldError> fieldErrors) {
        return fieldErrors.stream()
                .map(FieldError::getDefaultMessage)
                .filter(msg -> msg != null && !msg.isEmpty())
                .findFirst()
                .orElse("参数校验失败");
    }
}
