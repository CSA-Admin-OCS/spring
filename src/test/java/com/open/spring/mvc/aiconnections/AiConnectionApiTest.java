package com.open.spring.mvc.aiconnections;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.cors.CorsConfiguration;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiConnectionApiTest {
    private final CorsConfiguration cors = new CorsConfiguration();
    private final AiConnectionService service = mock(AiConnectionService.class);
    private final AiRequestGuard guard = new AiRequestGuard(request -> cors);
    private final AiConnectionApiController controller = new AiConnectionApiController(service, guard);
    @Test void onlyAllowedOriginsAndCustomHeadersCanWrite() {
        cors.addAllowedOrigin("http://localhost:4500");
        var request = new MockHttpServletRequest(); request.addHeader("Origin", "http://localhost:4500");
        assertEquals(403, assertThrows(AiConnectionException.class, () -> guard.validate(request)).getStatus());
        request.addHeader("X-Origin", "client"); assertDoesNotThrow(() -> guard.validate(request));
        request.removeHeader("Origin"); request.addHeader("Origin", "https://foreign.test");
        assertThrows(AiConnectionException.class, () -> guard.validate(request));
    }
    @Test void managementUsesAuthenticatedOwnerAndNoCacheResponses() {
        cors.addAllowedOrigin("http://localhost:4500");
        var request = new MockHttpServletRequest(); request.addHeader("Origin", "http://localhost:4500"); request.addHeader("X-Origin", "client");
        var auth = new UsernamePasswordAuthenticationToken("alice", null, java.util.List.of());
        var body = new AiConnectionApiController.KeyRequest(); body.setKey("test-key");
        var status = new AiConnectionService.Status(true, Map.of());
        when(service.connect(auth, AiProvider.gemini, "test-key")).thenReturn(status);
        var response = controller.connect("gemini", auth, body, request);
        assertEquals("no-store", response.getHeaders().getCacheControl());
        assertSame(status, response.getBody());
        assertEquals(204, controller.disconnect("groq", auth, request).getStatusCode().value());
        verify(service).disconnect(auth, AiProvider.groq);
        assertThrows(AiConnectionException.class, () -> controller.connect("other", auth, body, request));
    }
}
