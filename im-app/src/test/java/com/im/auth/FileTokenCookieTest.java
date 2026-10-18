package com.im.auth;

import com.im.auth.security.FileTokenCookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class FileTokenCookieTest {

    @Test
    void writesHttpOnlyScopedCookie() {
        FileTokenCookie cookie = new FileTokenCookie();
        ReflectionTestUtils.setField(cookie, "ttlMillis", 7200000L);
        ReflectionTestUtils.setField(cookie, "secure", true);
        ReflectionTestUtils.setField(cookie, "sameSite", "None");

        MockHttpServletResponse response = new MockHttpServletResponse();
        cookie.attach(response, "jwt-value");

        String header = response.getHeader("Set-Cookie");
        assertThat(header)
                .contains("im_file_token=jwt-value")
                .contains("Path=/api/files/local")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=None")
                .contains("Max-Age=7200");
    }

    @Test
    void clearExpiresCookie() {
        FileTokenCookie cookie = new FileTokenCookie();
        ReflectionTestUtils.setField(cookie, "ttlMillis", 7200000L);
        ReflectionTestUtils.setField(cookie, "secure", false);
        ReflectionTestUtils.setField(cookie, "sameSite", "Lax");

        MockHttpServletResponse response = new MockHttpServletResponse();
        cookie.clear(response);

        assertThat(response.getHeader("Set-Cookie"))
                .contains("im_file_token=")
                .contains("Max-Age=0");
    }
}
