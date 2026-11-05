package com.nutrilog.session;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;
@RestControllerAdvice(assignableTypes=SessionController.class)
public class SessionErrorHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> handle(ResponseStatusException error) {
        String code=switch(error.getStatusCode().value()) {
            case 401 -> "UNAUTHORIZED";
            case 403 -> "SESSION_ACCESS_DENIED";
            case 409 -> "SESSION_BUSY";
            default -> "INVALID_REQUEST";
        };
        return ResponseEntity.status(error.getStatusCode()).body(Map.of("code",code));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<Map<String,String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST"));
    }
}
