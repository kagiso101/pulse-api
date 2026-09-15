package pulse_api.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    /** A clock the test can move forward. */
    static class MutableClock extends Clock {
        final AtomicLong millis = new AtomicLong(1_700_000_000_000L);
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        @Override public long millis() { return millis.get(); }
        void advance(long ms) { millis.addAndGet(ms); }
    }

    @Test
    void fixedWindowAllowsLimitThenRejectsUntilWindowRolls() {
        MutableClock clock = new MutableClock();
        RateLimitFilter filter = new RateLimitFilter(new ClientIpResolver(0), clock);

        assertThat(filter.allow("k", 2)).isTrue();
        assertThat(filter.allow("k", 2)).isTrue();
        assertThat(filter.allow("k", 2)).isFalse();
        clock.advance(RateLimitFilter.WINDOW_MILLIS - 1);
        assertThat(filter.allow("k", 2)).isFalse();
        clock.advance(1);
        assertThat(filter.allow("k", 2)).isTrue();
    }

    @Test
    void bucketsMatchTheContract() {
        assertThat(RateLimitFilter.bucketFor(req("POST", "/api/auth/google"))).isEqualTo("auth");
        assertThat(RateLimitFilter.bucketFor(req("POST", "/api/actions/cloud-run/x/restart"))).isEqualTo("actions");
        assertThat(RateLimitFilter.bucketFor(req("POST", "/api/ask"))).isEqualTo("ask");
        assertThat(RateLimitFilter.bucketFor(req("GET", "/api/public/client-view/abc"))).isEqualTo("public");
        assertThat(RateLimitFilter.bucketFor(req("GET", "/api/projects"))).isNull();
        assertThat(RateLimitFilter.limitFor("auth")).isEqualTo(10);
        assertThat(RateLimitFilter.limitFor("actions")).isEqualTo(20);
        assertThat(RateLimitFilter.limitFor("ask")).isEqualTo(10);
        assertThat(RateLimitFilter.limitFor("public")).isEqualTo(60);
    }

    @Test
    void eleventhAuthAttemptFromSameIpIs429WithErrorJson() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new ClientIpResolver(0), new MutableClock());
        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse ok = new MockHttpServletResponse();
            filter.doFilter(req("POST", "/api/auth/google"), ok, new MockFilterChain());
            assertThat(ok.getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse limited = new MockHttpServletResponse();
        filter.doFilter(req("POST", "/api/auth/google"), limited, new MockFilterChain());
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getContentAsString()).contains("\"status\":\"error\"").contains("RATE_LIMITED");

        // a different client IP has its own window
        MockHttpServletRequest other = req("POST", "/api/auth/google");
        other.setRemoteAddr("10.0.0.9");
        MockHttpServletResponse fresh = new MockHttpServletResponse();
        filter.doFilter(other, fresh, new MockFilterChain());
        assertThat(fresh.getStatus()).isEqualTo(200);
    }

    private static MockHttpServletRequest req(String method, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
        r.setRemoteAddr("192.0.2.1");
        return r;
    }
}
