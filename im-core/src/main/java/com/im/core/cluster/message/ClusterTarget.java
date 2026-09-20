package com.im.core.cluster.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 跨节点投递的目标：某个用户的具体设备。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClusterTarget {

    private String userId;

    private String deviceId;

    private String deviceType;
}
