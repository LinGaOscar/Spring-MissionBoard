package com.wbsflow.common;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsEntityNotFoundTo404() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleNotFound(new EntityNotFoundException("專案不存在"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody().getMessage()).isEqualTo("專案不存在");
    }

    @Test
    void mapsIllegalArgumentTo400() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleBadRequest(new IllegalArgumentException("父節點超出層級"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody().getMessage()).isEqualTo("父節點超出層級");
    }

    @Test
    void mapsSecurityExceptionTo403() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleForbidden(new SecurityException("非成員"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().getMessage()).isEqualTo("非成員");
    }

    @Test
    void mapsAccessDeniedTo403WithGenericMessage() {
        ResponseEntity<ApiResponse<Void>> res = handler.handleAccessDenied(new AccessDeniedException("denied"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res.getBody().getMessage()).isEqualTo("存取被拒絕");
    }
}
