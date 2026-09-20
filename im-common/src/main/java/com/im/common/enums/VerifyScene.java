package com.im.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 邮箱验证码使用场景。
 */
@Getter
@AllArgsConstructor
public enum VerifyScene {

    REGISTER("register"),
    LOGIN("login"),
    RESET_PASSWORD("reset_password");

    private final String scene;

    public static VerifyScene from(String value) {
        if (value == null) return null;
        for (VerifyScene scene : values()) {
            if (scene.scene.equals(value)) {
                return scene;
            }
        }
        return null;
    }
}
