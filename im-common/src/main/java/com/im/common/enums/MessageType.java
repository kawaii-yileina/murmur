package com.im.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum MessageType {
    TEXT("TEXT", "文字"),
    IMAGE("IMAGE", "图片"),
    VIDEO("VIDEO", "视频"),
    FILE("FILE", "文件"),
    VOICE("VOICE", "语音"),
    RECALL("RECALL", "撤回");

    private final String type;
    private final String desc;

    public static MessageType fromType(String type) {
        for (MessageType mt : values()) {
            if (mt.type.equalsIgnoreCase(type)) return mt;
        }
        throw new IllegalArgumentException("Unknown message type: " + type);
    }
}
