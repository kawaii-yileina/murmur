package com.im.auth.controller;

import com.im.auth.crypto.EncryptedResponse;
import com.im.common.model.Result;
import com.im.core.asr.VoiceTranscriptionService;
import com.im.core.conversation.entity.Conversation;
import com.im.core.conversation.service.ConversationService;
import com.im.core.friend.service.FriendService;
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
    private final FriendService friendService;
    private final VoiceTranscriptionService voiceTranscriptionService;

    @EncryptedResponse
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

    @EncryptedResponse
    @GetMapping("/around")
    public Result<List<Message>> getAround(
            @RequestAttribute("userId") String userId,
            @RequestParam Long targetId,
            @RequestParam Integer chatType,
            @RequestParam long seq,
            @RequestParam(defaultValue = "30") int limit) {
        return Result.ok(messageService.getAround(
            Long.parseLong(userId), targetId, chatType, seq, limit));
    }

    @EncryptedResponse
    @GetMapping("/conversations")
    public Result<List<Conversation>> getConversations(@RequestAttribute("userId") String userId) {
        return Result.ok(conversationService.getConversations(Long.parseLong(userId)));
    }

    /**
     * 会话增量同步：返回 seq &gt; sinceSeq 的消息，用于断线重连/多端补拉。
     * 客户端以返回的 lastSeq 作为下次 sinceSeq。
     */
    @EncryptedResponse
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
    @EncryptedResponse
    @PostMapping("/recall")
    public Result<Message> recallMessage(@RequestAttribute("userId") String userId,
                                      @RequestBody RecallRequest request) {
        if (request.getMsgId() == null || request.getMsgId().isBlank()) {
            return Result.error("msgId 不能为空");
        }
        return Result.ok(messageService.recallMessage(Long.parseLong(userId), request.getMsgId()));
    }

    /** 语音转文字（本地 ASR）：按 msgId 转写语音消息，仅返回文本不落库。 */
    @EncryptedResponse
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
    public Result<Boolean> isUserOnline(@RequestAttribute("userId") String callerId,
                                        @PathVariable String userId) {
        if (!canProbeOnline(Long.parseLong(callerId), userId)) {
            return Result.error(403, "无权查询该用户在线状态");
        }
        return Result.ok(onlineStatusService.isOnline(userId));
    }

    @GetMapping("/online/batch")
    public Result<Map<String, Boolean>> checkOnlineStatus(@RequestAttribute("userId") String callerId,
                                                          @RequestParam List<String> userIds) {
        Long me = Long.parseLong(callerId);
        Map<String, Boolean> statusMap = new java.util.HashMap<>();
        for (String userId : userIds) {
            if (canProbeOnline(me, userId)) {
                statusMap.put(userId, onlineStatusService.isOnline(userId));
            }
        }
        return Result.ok(statusMap);
    }

    /** 在线状态仅本人与好友可查，防止任意用户被探测行为画像 */
    private boolean canProbeOnline(Long callerId, String targetUserId) {
        if (String.valueOf(callerId).equals(targetUserId)) {
            return true;
        }
        try {
            return friendService.isFriend(callerId, Long.parseLong(targetUserId));
        } catch (NumberFormatException e) {
            return false;
        }
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
