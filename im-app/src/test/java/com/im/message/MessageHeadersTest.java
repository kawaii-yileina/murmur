package com.im.message;

import com.elaina.chat.core.message.ChatMessage;
import com.im.core.message.util.MessageHeaders;
import com.im.core.message.util.SeqHeaders;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 下行 header 工具单测（无需 Spring/外部依赖） */
class MessageHeadersTest {

    private static ChatMessage text() {
        return ChatMessage.builder()
                .id("m1").type("TEXT").from("1").to("2").group(false).content("x").build();
    }

    @Test
    void withSeq_attachesStringSeqWithoutMutatingOriginal() {
        ChatMessage m = text();
        ChatMessage out = SeqHeaders.withSeq(m, 42L);
        assertThat(String.valueOf(out.getHeader("seq"))).isEqualTo("42");
        assertThat(m.getHeader("seq")).isNull();
        assertThat(out.getId()).isEqualTo(m.getId());
    }

    @Test
    void withSeq_noopForNonPositiveSeq() {
        ChatMessage m = text();
        assertThat(SeqHeaders.withSeq(m, 0L)).isSameAs(m);
    }

    @Test
    void stripReserved_removesStaleSeqAndAckKeys_keepsOthers() {
        ChatMessage m = text();
        m.addHeader("seq", "999");
        m.addHeader("ack", "true");
        m.addHeader("serverMsgId", "srv");
        m.addHeader("delivered", "2");
        m.addHeader("replyTo", "{\"msgId\":\"x\"}");

        ChatMessage out = MessageHeaders.stripReserved(m);

        assertThat(out.getHeader("seq")).isNull();
        assertThat(out.getHeader("ack")).isNull();
        assertThat(out.getHeader("serverMsgId")).isNull();
        assertThat(out.getHeader("delivered")).isNull();
        assertThat(out.getHeader("replyTo")).isEqualTo("{\"msgId\":\"x\"}");
        // 入参不被修改
        assertThat(m.getHeader("seq")).isEqualTo("999");
    }

    @Test
    void stripReserved_noopWhenNoReservedKeys() {
        ChatMessage m = text();
        m.addHeader("replyTo", "r");
        assertThat(MessageHeaders.stripReserved(m)).isSameAs(m);
    }
}
