package com.im.auth.controller;

import com.im.common.model.Result;
import com.im.core.conversation.entity.Conversation;
import com.im.core.conversation.service.ConversationService;
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
}
