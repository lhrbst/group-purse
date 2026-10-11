package de.grouppurse.web;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {
    @Test
    void setsHeaderAndMdcAndClearsAfterwards() throws Exception {
        var res = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();
        new RequestIdFilter().doFilter(new MockHttpServletRequest(), res, (rq, rs) -> seen.set(MDC.get("requestId")));
        assertThat(res.getHeader("X-Request-Id")).isNotBlank().isEqualTo(seen.get());
        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void keepsAWellFormedClientSuppliedId() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader("X-Request-Id", "abc12345-def");
        var res = new MockHttpServletResponse();
        new RequestIdFilter().doFilter(req, res, (rq, rs) -> { });
        assertThat(res.getHeader("X-Request-Id")).isEqualTo("abc12345-def");
    }

    @Test
    void replacesClientSuppliedIdsThatLookUnsafe() throws Exception {
        var req = new MockHttpServletRequest();
        req.addHeader("X-Request-Id", "evil value\nwith newline");
        var res = new MockHttpServletResponse();
        new RequestIdFilter().doFilter(req, res, (rq, rs) -> { });
        assertThat(res.getHeader("X-Request-Id")).matches("[0-9a-f-]{36}");
    }
}
