package com.im.auth;

import com.im.core.auth.service.WsTicketService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WsTicketServiceTest {

    @Test
    void issueStoresShortLivedTicketAndConsumeDeletesIt() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);

        WsTicketService service = new WsTicketService(redis);
        WsTicketService.IssuedTicket issued = service.issue("42");

        assertThat(issued.ticket()).isNotBlank().doesNotContain("=");
        assertThat(issued.expiresIn()).isEqualTo(30);
        verify(values).set("im:ws:ticket:" + issued.ticket(), "42", Duration.ofSeconds(30));

        when(values.getAndDelete("im:ws:ticket:" + issued.ticket())).thenReturn("42");
        assertThat(service.consume(issued.ticket())).isEqualTo("42");

        assertThat(service.consume("")).isNull();
        assertThat(service.consume("x".repeat(129))).isNull();
    }

    @Test
    void invalidTicketReturnsNull() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.getAndDelete(anyString())).thenReturn(null);

        assertThat(new WsTicketService(redis).consume("missing")).isNull();
    }
}
