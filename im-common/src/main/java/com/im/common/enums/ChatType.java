package com.im.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ChatType {
    PRIVATE(0, "私聊"),
    GROUP(1, "群聊");

    private final int code;
    private final String desc;

    public static ChatType fromCode(int code) {
        for (ChatType type : values()) {
            if (type.code == code) return type;
        }
        throw new IllegalArgumentException("Unknown chat type: " + code);
    }
}
