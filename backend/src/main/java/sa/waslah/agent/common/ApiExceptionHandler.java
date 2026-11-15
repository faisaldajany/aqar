package sa.waslah.agent.common;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("error", e.code(), "message", e.getMessage()));
    }

    /**
     * Names the field that is wrong and what is wrong with it. "message" is for the first invalid field in the form's
     * order; "fields" has a message for each invalid field, so a form can mark every one.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        Object target = e.getBindingResult().getTarget();
        List<FieldError> errors = new ArrayList<>(e.getBindingResult().getFieldErrors());
        errors.sort(Comparator.<FieldError>comparingInt(fe -> order(target, fe.getField()))
                .thenComparingInt(fe -> ValidationMessages.required(fe) ? 0 : 1));
        Map<String, String> fields = new LinkedHashMap<>();
        for (FieldError fe : errors) fields.putIfAbsent(fe.getField(), ValidationMessages.of(fe));
        String first = fields.isEmpty() ? "" : fields.keySet().iterator().next();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "invalid_input");
        body.put("field", first);
        body.put("fields", fields);
        body.put("message", fields.isEmpty() ? ValidationMessages.GENERIC : fields.get(first));
        return ResponseEntity.badRequest().body(body);
    }

    /** Position of the field in the request record, so the first message is for the first wrong field on the form. */
    static int order(Object target, String field) {
        if (target == null || !target.getClass().isRecord() || field == null) return 0;
        String base = field.split("[.\\[]", 2)[0];
        RecordComponent[] components = target.getClass().getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            if (components[i].getName().equals(base)) return i;
        }
        return components.length;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> conflict(DataIntegrityViolationException e) {
        log.warn("Integrity violation: {}", e.getMostSpecificCause().getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "conflict", "message", "البيانات موجودة مسبقاً أو مرتبطة بمكتب آخر."));
    }

    /**
     * Anything unexpected. Logged here, while the request id is still on the log line, and answered with a message
     * the portal can show plus that id, so a reported error can be found in the logs. Errors Spring maps itself
     * (404, 405, unreadable JSON, a security refusal...) are passed on and keep their status.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception e, HttpServletRequest req) throws Exception {
        if (springMaps(e)) throw e;
        log.error("Unexpected error on {} {}", req.getMethod(), req.getRequestURI(), e);
        String lang = req.getHeader("Accept-Language");
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "internal");
        body.put("message", lang != null && lang.startsWith("en")
                ? "Something went wrong on our side and your request didn't complete. Try again; if it happens again, report it."
                : "حدث خطأ من جهتنا ولم يكتمل طلبك. أعد المحاولة، وإن تكرر فأبلغنا.");
        String id = MDC.get("requestId");
        if (id != null) body.put("requestId", id);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /** Errors Spring already answers with the right status. */
    static boolean springMaps(Exception e) {
        return e instanceof ErrorResponse || e instanceof TypeMismatchException || e instanceof HttpMessageNotReadableException
                || e instanceof BindException || e instanceof AccessDeniedException || e instanceof AuthenticationException
                || AnnotatedElementUtils.hasAnnotation(e.getClass(), ResponseStatus.class);
    }
}
