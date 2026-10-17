package com.im.core.asr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.common.enums.ChatType;
import com.im.common.enums.MessageType;
import com.im.common.exception.BizException;
import com.im.core.group.entity.GroupMember;
import com.im.core.group.mapper.GroupMemberMapper;
import com.im.core.message.entity.Message;
import com.im.core.message.service.MessageService;
import com.im.storage.strategy.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;

/**
 * 语音转文字：按 msgId 取语音消息 → 归属校验 → 读取存储的音频 → 调本地 ASR 服务 → 返回文本。
 * 不落库（仅返回文本）。归属校验：私聊限收发双方，群聊校验成员身份（直接查 GroupMemberMapper，避免引入 MessageGateway 成环）。
 * 音频读取按 maxAudioBytes 限长，避免超大文件整体入内存。
 * 说明：文件定位依赖后端统一访问 URL 前缀（FileStorage.SERVED_URL_PREFIX），与存储实现（local/s3）无关。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceTranscriptionService {

    private final MessageService messageService;
    private final GroupMemberMapper groupMemberMapper;
    private final FileStorage fileStorage;
    private final AsrClient asrClient;
    private final AsrProperties asrProperties;

    public String transcribe(String msgId, Long userId) {
        if (!asrProperties.isEnabled()) {
            throw new BizException("语音转文字未启用");
        }
        if (isBlank(msgId)) {
            throw new BizException("msgId 不能为空");
        }
        Message msg = messageService.getByMsgId(msgId);
        if (msg == null) {
            throw new BizException("消息不存在");
        }
        if (!canAccess(msg, userId)) {
            throw new BizException(403, "无权访问该消息");
        }
        if (!MessageType.VOICE.getType().equals(msg.getMsgType())) {
            throw new BizException("仅支持语音消息转写");
        }
        String key = extractKey(msg.getContent());
        if (isBlank(key)) {
            throw new BizException("语音文件不可用");
        }

        SizeLimitedOutputStream out = new SizeLimitedOutputStream(asrProperties.getMaxAudioBytes());
        try {
            fileStorage.download(key, out);
        } catch (SizeLimitExceededException e) {
            throw new BizException("语音文件过大，无法转写");
        } catch (Exception e) {
            log.warn("Read voice file failed: key={}, {}", key, e.getMessage());
            throw new BizException("读取语音文件失败");
        }
        byte[] audio = out.toByteArray();
        if (audio.length == 0) {
            throw new BizException("语音文件为空");
        }
        return asrClient.transcribe(audio, safeFilename(key));
    }

    private boolean canAccess(Message msg, Long userId) {
        if (userId.equals(msg.getSenderId()) || userId.equals(msg.getReceiverId())) {
            return true;
        }
        if (msg.getChatType() != null && msg.getChatType() == ChatType.GROUP.getCode()) {
            return groupMemberMapper.selectCount(new LambdaQueryWrapper<GroupMember>()
                    .eq(GroupMember::getGroupId, msg.getReceiverId())
                    .eq(GroupMember::getUserId, userId)) > 0;
        }
        return false;
    }

    private String extractKey(String content) {
        if (isBlank(content)) {
            return null;
        }
        int idx = content.indexOf(FileStorage.SERVED_URL_PREFIX);
        if (idx >= 0) {
            return content.substring(idx + FileStorage.SERVED_URL_PREFIX.length());
        }
        return content;
    }

    /** 取文件名并消毒（去除路径分隔/引号/换行），避免 multipart 头注入 */
    private String safeFilename(String key) {
        int idx = key.lastIndexOf('/');
        String name = idx >= 0 ? key.substring(idx + 1) : key;
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "audio" : cleaned;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** 限长输出流：超过上限即抛出，避免整文件读入内存 */
    private static final class SizeLimitedOutputStream extends ByteArrayOutputStream {
        private final long max;

        private SizeLimitedOutputStream(long max) {
            this.max = max;
        }

        @Override
        public synchronized void write(int b) {
            if (exceeds(1)) {
                throw new SizeLimitExceededException();
            }
            super.write(b);
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            if (exceeds(len)) {
                throw new SizeLimitExceededException();
            }
            super.write(b, off, len);
        }

        private boolean exceeds(int len) {
            return max > 0 && (long) count + len > max;
        }
    }

    private static final class SizeLimitExceededException extends RuntimeException {
    }
}
