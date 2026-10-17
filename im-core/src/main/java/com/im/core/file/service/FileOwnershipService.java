package com.im.core.file.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.im.core.file.entity.FileOwnership;
import com.im.core.file.mapper.FileOwnershipMapper;
import com.im.storage.strategy.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 文件归属登记与查询。本服务位于消息持久化链路上（MessageService 落库事务内调用），
 * 只依赖 Mapper，禁止注入 MessageGateway / GroupService 等推送侧组件，否则启动成环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileOwnershipService {

    /** 附件类消息类型：content 为文件 URL，需登记归属 */
    private static final Set<String> ATTACHMENT_MSG_TYPES = Set.of("IMAGE", "VIDEO", "FILE", "VOICE");

    private final FileOwnershipMapper fileOwnershipMapper;

    /**
     * 附件类消息落库时，把消息中的文件 key 绑定到本会话（与消息同事务调用）。
     * 头像/表情等非附件消息不登记，按公开放行；同一文件在同一会话只登记一条，
     * 转发到其它会话会另建一条归属（一个文件可被多个会话引用）。
     */
    public void bindIfAttachment(Long senderId, Integer chatType, Long receiverId, String msgType, String content) {
        String fileKey = extractFileKey(msgType, content);
        if (fileKey == null) {
            return;
        }
        if (fileOwnershipMapper.selectCount(
                new LambdaQueryWrapper<FileOwnership>()
                        .eq(FileOwnership::getFileKey, fileKey)
                        .eq(FileOwnership::getChatType, chatType)
                        .eq(FileOwnership::getTargetId, receiverId)) > 0) {
            return;
        }
        FileOwnership ownership = new FileOwnership();
        ownership.setFileKey(fileKey);
        ownership.setOwnerUserId(senderId);
        ownership.setChatType(chatType);
        ownership.setTargetId(receiverId);
        ownership.setCreatedAt(LocalDateTime.now());
        fileOwnershipMapper.insert(ownership);
    }

    /** 查该文件的所有归属记录，未登记返回空列表（公开文件） */
    public List<FileOwnership> findAllByKey(String fileKey) {
        return fileOwnershipMapper.selectList(
            new LambdaQueryWrapper<FileOwnership>().eq(FileOwnership::getFileKey, fileKey)
        );
    }

    /**
     * 从消息 content 提取文件 key：仅附件类消息且 content 为本服务统一地址时返回。
     */
    private String extractFileKey(String msgType, String content) {
        if (msgType == null || content == null || !ATTACHMENT_MSG_TYPES.contains(msgType)) {
            return null;
        }
        String prefix = FileStorage.SERVED_URL_PREFIX;
        if (!content.startsWith(prefix)) {
            return null;
        }
        String key = content.substring(prefix.length());
        return key.isBlank() ? null : key;
    }
}
