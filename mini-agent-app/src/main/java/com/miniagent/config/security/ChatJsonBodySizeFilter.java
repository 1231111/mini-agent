package com.miniagent.config.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Caps buffered JSON chat bodies before Jackson materializes their strings and lists. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ChatJsonBodySizeFilter extends OncePerRequestFilter {
    private static final AntPathMatcher PATHS = new AntPathMatcher();
    private static final String TOO_LARGE_ATTRIBUTE = ChatJsonBodySizeFilter.class.getName() + ".tooLarge";
    private static final long DEFAULT_MAX_BYTES = 32L * 1024 * 1024;

    @Value("${agent.chat.max-json-request-bytes:33554432}")
    private long maxBytes = DEFAULT_MAX_BYTES;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String path = request.getRequestURI();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        String contentType = request.getContentType();
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !PATHS.match("/api/conversations/*/messages/stream", path)
                || contentType == null
                || !contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.APPLICATION_JSON_VALUE);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long limit = Math.max(1L, maxBytes);
        if (request.getContentLengthLong() > limit) {
            reject(response, limit);
            return;
        }

        HttpServletRequest bounded = new BoundedRequest(request, limit);
        try {
            filterChain.doFilter(bounded, response);
        } catch (IOException | ServletException failure) {
            if (!Boolean.TRUE.equals(request.getAttribute(TOO_LARGE_ATTRIBUTE))) {
                throw failure;
            }
        }
        if (Boolean.TRUE.equals(request.getAttribute(TOO_LARGE_ATTRIBUTE)) && !response.isCommitted()) {
            response.resetBuffer();
            reject(response, limit);
        }
    }

    private static void reject(HttpServletResponse response, long limit) throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"chat request body exceeds configured limit\",\"maxBytes\":"
                + limit + "}");
    }

    private static final class BoundedRequest extends HttpServletRequestWrapper {
        private final long limit;
        private ServletInputStream input;

        private BoundedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (input == null) {
                input = new BoundedInputStream(super.getInputStream(), limit,
                        () -> setAttribute(TOO_LARGE_ATTRIBUTE, Boolean.TRUE));
            }
            return input;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class BoundedInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long limit;
        private final Runnable onLimitExceeded;
        private long bytesRead;

        private BoundedInputStream(ServletInputStream delegate, long limit, Runnable onLimitExceeded) {
            this.delegate = delegate;
            this.limit = limit;
            this.onLimitExceeded = onLimitExceeded;
        }

        @Override
        public int read() throws IOException {
            if (bytesRead >= limit) {
                int extra = delegate.read();
                if (extra < 0) return -1;
                onLimitExceeded.run();
                throw new IOException("chat request body exceeds configured limit");
            }
            int value = delegate.read();
            if (value >= 0) bytesRead++;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) return 0;
            if (bytesRead >= limit) return read() < 0 ? -1 : 0;
            int permitted = (int) Math.min(length, limit - bytesRead);
            int count = delegate.read(bytes, offset, permitted);
            if (count > 0) bytesRead += count;
            return count;
        }

        @Override public boolean isFinished() { return delegate.isFinished(); }
        @Override public boolean isReady() { return delegate.isReady(); }
        @Override public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
    }
}
