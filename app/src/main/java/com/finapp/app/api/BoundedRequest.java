package com.finapp.app.api;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A request whose body cannot be read past a limit.
 *
 * <p>The half of the size limit that {@code Content-Length} cannot provide. A request using
 * chunked transfer encoding declares no length, so a filter checking the header alone enforces a
 * bound the caller opts out of by omitting a header — which is not a bound. The only way to know
 * such a body is too large is to notice while reading it.
 *
 * <p>The stream therefore counts, and stops. It does not buffer: the point is to refuse a large
 * body without holding it, so a limit implemented by reading everything first and measuring
 * afterwards would supply the attacker with exactly the memory the limit exists to protect.
 */
final class BoundedRequest extends HttpServletRequestWrapper {

    private final long maxBytes;

    BoundedRequest(HttpServletRequest request, long maxBytes) {
        super(request);
        this.maxBytes = maxBytes;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        return new BoundedStream(super.getInputStream(), maxBytes);
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    /** Counts bytes and refuses to hand over more than the limit. */
    private static final class BoundedStream extends ServletInputStream {

        private final ServletInputStream delegate;
        private final long maxBytes;
        private long read;

        BoundedStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value != -1) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = delegate.read(buffer, offset, length);
            if (count > 0) {
                count(count);
            }
            return count;
        }

        private void count(int bytes) throws IOException {
            read += bytes;
            if (read > maxBytes) {
                // An IOException rather than a runtime exception: this happens while a message
                // converter is reading, and IOException is what that code is written to expect.
                // ApiErrorHandler unwraps it back to api.PayloadTooLarge.
                throw new RequestTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /**
     * The body exceeded the limit while being read.
     *
     * <p>An {@link IOException} because it is thrown from inside a stream, where callers expect
     * one. It surfaces to the dispatcher wrapped by whatever message converter was reading, so
     * {@link ApiErrorHandler} looks for it in the cause chain rather than at the top.
     */
    static final class RequestTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        private final long maxBytes;

        RequestTooLargeException(long maxBytes) {
            super("request body exceeded " + maxBytes + " bytes");
            this.maxBytes = maxBytes;
        }

        long maxBytes() {
            return maxBytes;
        }
    }
}
