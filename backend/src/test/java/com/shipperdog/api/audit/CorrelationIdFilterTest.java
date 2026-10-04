package com.shipperdog.api.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.shipperdog.api.config.CorrelationIdFilter;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Correlation-id propagation (issue #6, acceptance criterion 4): a
 * well-formed inbound id is echoed, anything else is regenerated, the id
 * lands in the MDC for logs and audit enrichment, and it is cleared after
 * the request so it cannot leak into another request's thread reuse.
 */
class CorrelationIdFilterTest {

    @Test
    void generatesACorrelationIdWhenAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/audit");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = run(request, response);

        assertThat(seen).isNotBlank();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(seen);
    }

    @Test
    void echoesAWellFormedInboundId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/audit");
        request.addHeader(CorrelationIdFilter.HEADER, "gateway-abc-12345");
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = run(request, response);

        assertThat(seen).isEqualTo("gateway-abc-12345");
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("gateway-abc-12345");
    }

    @Test
    void hostileInboundValuesAreReplaced() throws Exception {
        assertRegenerated("short");
        assertRegenerated("spaces and words are not an identifier");
        assertRegenerated("'; DROP TABLE audit_events; --");
        assertRegenerated("a".repeat(65));
        assertRegenerated("   ");
        assertRegenerated("corr\r\nSet-Cookie: session=evil");
        assertRegenerated("id\twith\ttabs");
        assertRegenerated("../etc/passwd");
    }

    @Test
    void lengthBoundariesAreEnforcedExactly() throws Exception {
        assertThat(echo("a".repeat(64)))
                .as("64 characters is the accepted maximum")
                .isEqualTo("a".repeat(64));
        assertThat(echo("12345678")).as("8 characters is the accepted minimum").isEqualTo("12345678");
        assertThat(echo("1234567"))
                .as("7 characters is too short")
                .isNotEqualTo("1234567")
                .isNotBlank();
    }

    private String echo(String inbound) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/audit");
        request.addHeader(CorrelationIdFilter.HEADER, inbound);
        return run(request, new MockHttpServletResponse());
    }

    @Test
    void mdcIsClearedAfterTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/audit");
        run(request, new MockHttpServletResponse());

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY))
                .as("the correlation id must not leak past the request")
                .isNull();
    }

    private void assertRegenerated(String hostile) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/audit");
        request.addHeader(CorrelationIdFilter.HEADER, hostile);
        MockHttpServletResponse response = new MockHttpServletResponse();

        String seen = run(request, response);

        assertThat(seen)
                .as("hostile value '%s' is discarded", hostile)
                .isNotEqualTo(hostile)
                .isNotBlank();
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(seen);
    }

    /** Runs the filter and returns the correlation id observed inside it. */
    private static String run(MockHttpServletRequest request, MockHttpServletResponse response)
            throws ServletException, IOException {
        String[] seen = new String[1];
        new CorrelationIdFilter()
                .doFilter(request, response, (req, res) -> seen[0] = MDC.get(CorrelationIdFilter.MDC_KEY));
        return seen[0];
    }
}
