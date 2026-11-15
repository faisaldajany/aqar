package sa.waslah.agent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** An unexpected error says what happened in the page's language and carries the request id for the logs. */
class UnexpectedErrorTest {

    final ApiExceptionHandler handler = new ApiExceptionHandler();

    @AfterEach
    void clear() {
        MDC.remove("requestId");
    }

    @Test
    void answersWithAMessageAndTheRequestId() throws Exception {
        MDC.put("requestId", "req-12345678");
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/ops/offices/x/whatsapp/manual");
        ResponseEntity<Map<String, String>> r = handler.unexpected(new IllegalStateException("boom"), req);
        assertThat(r.getStatusCode().value()).isEqualTo(500);
        assertThat(r.getBody()).containsEntry("error", "internal").containsEntry("requestId", "req-12345678");
        assertThat(r.getBody().get("message")).startsWith("حدث خطأ من جهتنا");
    }

    @Test
    void speaksEnglishToAnEnglishPage() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/me");
        req.addHeader("Accept-Language", "en");
        ResponseEntity<Map<String, String>> r = handler.unexpected(new IllegalStateException("boom"), req);
        assertThat(r.getBody().get("message")).startsWith("Something went wrong on our side");
        assertThat(r.getBody()).doesNotContainKey("requestId");
    }

    @Test
    void leavesSpringsOwnErrorsToSpring() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/nope");
        assertThatThrownBy(() -> handler.unexpected(new NoResourceFoundException(HttpMethod.GET, "nope"), req))
                .isInstanceOf(NoResourceFoundException.class);
        HttpMessageNotReadableException unreadable = new HttpMessageNotReadableException("bad json", new ServletServerHttpRequest(req));
        assertThatThrownBy(() -> handler.unexpected(unreadable, req)).isSameAs(unreadable);
    }
}
