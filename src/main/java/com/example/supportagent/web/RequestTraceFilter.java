package com.example.supportagent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

@Component
public class RequestTraceFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(RequestTraceFilter.class);
    private static final String TRACE_ATTRIBUTE = RequestTraceFilter.class.getName() + ".traceId";

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = (String) request.getAttribute(TRACE_ATTRIBUTE);
        if (traceId == null) {
            traceId = UUID.randomUUID().toString();
            request.setAttribute(TRACE_ATTRIBUTE, traceId);
        }
        String previous = MDC.get("traceId");
        long started = System.nanoTime();
        MDC.put("traceId", traceId);
        response.setHeader("X-Trace-Id", traceId);
        try {
            log.info("HTTP started method={} path={}", request.getMethod(), request.getRequestURI());
            chain.doFilter(request, response);
        } finally {
            log.info("HTTP dispatched method={} path={} status={} async={} durationMs={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(),
                    request.isAsyncStarted(), (System.nanoTime() - started) / 1_000_000);
            if (previous == null) MDC.remove("traceId"); else MDC.put("traceId", previous);
        }
    }
}
