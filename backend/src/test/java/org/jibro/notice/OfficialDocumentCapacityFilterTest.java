package org.jibro.notice;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OfficialDocumentCapacityFilterTest {
    private MockHttpServletRequest request(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "123/page/1", "123/text", "123/manifest", "123/fingerprint"})
    void rejectsBeforeAnyDocumentWorkUntilBothResponseWritersFinish(String suffix) throws Exception {
        var filter = new OfficialDocumentCapacityFilter();
        var entered = new CountDownLatch(2);
        var finish = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        var failures = new ConcurrentLinkedQueue<Throwable>();
        try {
            for (int i = 0; i < 2; i++)
                pool.submit(() -> {
                    try {
                        filter.doFilter(request("GET", "/api/v1/notices/official-pdf/123/page/1"), new MockHttpServletResponse(), (req, res) -> {
                            entered.countDown();
                            try {
                                if (!finish.await(5, TimeUnit.SECONDS)) throw new IOException("test writer timed out");
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IOException(interrupted);
                            }
                            res.getWriter().write("completed body");
                        });
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (String method : new String[]{"GET", "HEAD"}) {
                var response = new MockHttpServletResponse();
                var downstream = new AtomicBoolean();
                filter.doFilter(request(method, "/api/v1/notices/official-pdf/" + suffix), response, (req, res) -> downstream.set(true));
                assertEquals(429, response.getStatus());
                assertEquals("2", response.getHeader("Retry-After"));
                assertFalse(downstream.get(), "download/render must never start after capacity is exhausted");
            }
            var health = new AtomicBoolean();
            filter.doFilter(request("GET", "/api/v1/health"), new MockHttpServletResponse(), (req, res) -> health.set(true));
            assertTrue(health.get());
        } finally {
            finish.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertTrue(failures.isEmpty(), failures.toString());
        var next = new AtomicBoolean();
        filter.doFilter(request("GET", "/api/v1/notices/official-pdf/123"), new MockHttpServletResponse(), (req, res) -> next.set(true));
        assertTrue(next.get());
    }

    @Test
    void exceptionsCannotLeakPermitsAndStoredRelocationDoesNotAcquireCapacity() throws Exception {
        var filter = new OfficialDocumentCapacityFilter();
        for (int i = 0; i < 3; i++)
            assertThrows(IOException.class, () -> filter.doFilter(request("GET", "/api/v1/notices/official-pdf/123"),
                    new MockHttpServletResponse(), (req, res) -> {
                        throw new IOException("write failed");
                    }));
        var relocation = new AtomicBoolean();
        filter.doFilter(request("GET", "/api/v1/notices/official-pdf/123/relocate"), new MockHttpServletResponse(), (req, res) -> relocation.set(true));
        assertTrue(relocation.get());
    }
}
