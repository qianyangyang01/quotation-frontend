package com.milano.quotation.common;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.junit.jupiter.api.Assertions.*;

class RequestIdFilterTest {
    @Test void preservesSafeRequestIdAndRejectsUnsafeInput() throws Exception {
        var filter = new RequestIdFilter();
        var safeRequest = new MockHttpServletRequest();
        safeRequest.addHeader("X-Request-Id", "quote-request-123");
        var safeResponse = new MockHttpServletResponse();
        filter.doFilter(safeRequest, safeResponse, (request, response) -> assertEquals("quote-request-123", org.slf4j.MDC.get("requestId")));
        assertEquals("quote-request-123", safeResponse.getHeader("X-Request-Id"));

        var unsafeRequest = new MockHttpServletRequest();
        unsafeRequest.addHeader("X-Request-Id", "bad\r\nheader");
        var unsafeResponse = new MockHttpServletResponse();
        filter.doFilter(unsafeRequest, unsafeResponse, (request, response) -> {});
        assertNotEquals("bad\r\nheader", unsafeResponse.getHeader("X-Request-Id"));
    }
}
