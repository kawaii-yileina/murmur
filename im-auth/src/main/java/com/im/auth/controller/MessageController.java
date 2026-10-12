package com.im.auth.controller;

import com.im.common.model.Result;
import com.im.core.asr.VoiceTranscriptionService;
import com.im.core.conversation.entity.Conversation;
import com.im.core.conversation.service.ConversationService;
import com.im.core.message.dto.MessageSyncResult;
import com.im.core.message.entity.Message;
import com.im.core.message.service.MessageService;
import com.im.core.online.service.OnlineStatusService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;
    private final ConversationService conversationService;
    private final OnlineStatusService onlineStatusService;
    private final VoiceTranscriptionService voiceTranscriptionService;

    @GetMapping("/history")
    public Result<List<Message>> getHistory(
            @RequestAttribute("userId") String userId,
            @RequestParam Long targetId,
            @RequestParam Integer chatType,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(messageService.getHistory(
            Long.parseLong(userId), targetId, chatType, page, size));
    }

    @GetMapping("/conversations")
    public Result<List<Conversation>> getConversations(@RequestAttribute("userId") String userId) {
        return Result.ok(conversationService.getConversations(Long.parseLong(userId)));
    }

    /**
     * 会话增量同步：返回 seq &gt; sinceSeq 的消息，用于断线重连/多端补拉。
     * 客户端以返回的 lastSeq 作为下次 sinceSeq。
     */
    @GetMapping("/sync")
    public Result<MessageSyncResult> sync(
            @RequestAttribute("userId") String userId,
            @RequestParam Long targetId,
            @RequestParam Integer chatType,
            @RequestParam(defaultValue = "0") long sinceSeq,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(messageService.sync(
            Long.parseLong(userId), targetId, chatType, sinceSeq, limit));
    }

    /**
     * 撤回消息（仅本人、2 分钟内），撤回后向会话内其他成员推送 SYSTEM 事件。
     */
    @PostMapping("/recall")
    public Result<Message> recallMessage(@RequestAttribute("userId") String userId,
                                      @RequestBody RecallRequest request) {
        if (request.getMsgId() == null || request.getMsgId().isBlank()) {
            return Result.error("msgId 不能为空");
        }
        ;
        return Result.ok(messageService.recallMessage(Long.parseLong(userId), request.getMsgId()));
    }

    /** 语音转文字（本地 ASR）：按 msgId 转写语音消息，仅返回文本不落库。 */
    @PostMapping("/voice/transcribe")
    public Result<Map<String, String>> transcribeVoice(@RequestAttribute("userId") String userId,
                                                       @RequestBody TranscribeRequest request) {
        String text = voiceTranscriptionService.transcribe(request.getMsgId(), Long.parseLong(userId));
        return Result.ok(Map.of("text", text));
    }

    @PutMapping("/conversations/read")
    public Result<Void> markAsRead(@RequestAttribute("userId") String userId,
                                   @RequestBody ReadRequest request) {
        conversationService.markAsRead(
            Long.parseLong(userId), request.getTargetId(), request.getChatType());
        return Result.ok();
    }

    @DeleteMapping("/conversations/{targetId}/{chatType}")
    public Result<Void> deleteConversation(@RequestAttribute("userId") String userId,
                                           @PathVariable Long targetId,
                                           @PathVariable Integer chatType) {
        conversationService.deleteConversation(
            Long.parseLong(userId), targetId, chatType);
        return Result.ok();
    }

    @GetMapping("/unread")
    public Result<Map<String, Integer>> getUnreadCounts(@RequestAttribute("userId") String userId) {
        List<Conversation> conversations = conversationService.getConversations(Long.parseLong(userId));
        Map<String, Integer> unreadCounts = new java.util.HashMap<>();
        for (Conversation conv : conversations) {
            String key = conv.getTargetId() + "_" + conv.getChatType();
            unreadCounts.put(key, conv.getUnreadCount());
        }
        return Result.ok(unreadCounts);
    }

    @GetMapping("/online/{userId}")
    public Result<Boolean> isUserOnline(@PathVariable String userId) {
        return Result.ok(onlineStatusService.isOnline(userId));
    }

    @GetMapping("/online/batch")
    public Result<Map<String, Boolean>> checkOnlineStatus(@RequestParam List<String> userIds) {
        Map<String, Boolean> statusMap = new java.util.HashMap<>();
        for (String userId : userIds) {
            statusMap.put(userId, onlineStatusService.isOnline(userId));
        }
        return Result.ok(statusMap);
    }

    @GetMapping("/online/friends")
    public Result<List<String>> getOnlineFriends(@RequestAttribute("userId") String userId) {
        return Result.ok(onlineStatusService.getOnlineFriendIds(userId));
    }

    @Data
    public static class ReadRequest {
        private Long targetId;
        private Integer chatType;
    }

    @Data
    public static class RecallRequest {
        private String msgId;
    }

    @Data
    public static class TranscribeRequest {
        private String msgId;
    }
}
