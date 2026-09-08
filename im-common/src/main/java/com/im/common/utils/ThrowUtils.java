package com.im.common.utils;

import com.im.common.exception.BizException;

/**
 * ClassName: ThrowUtils
 * Package: com.im.common.utils
 * Description:
 *
 * @Author: 灰之魔女-伊蕾娜
 * @Create: 2026/9/8 15:45
 * @Version:
 */
public class ThrowUtils {
    private ThrowUtils() {
    }

    /**
     * 条件为真时抛出业务异常
     */
    public static void throwIf(boolean condition, int errorCode, String message) {
        if (condition) {
            throw new BizException(errorCode, message);
        }
    }
}
