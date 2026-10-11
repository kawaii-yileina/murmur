package com.im.core.cluster;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 节点运行计数：轻量、无第三方依赖，由跨节点投递/离线等组件累加。
 * 仅统计本进程内存值（不跨节点聚合），由 im-app 侧绑定到 Micrometer 暴露为指标。
 */
@Component
public class ClusterStats {

    private final AtomicLong dispatchSuccess = new AtomicLong();
    private final AtomicLong dispatchFailure = new AtomicLong();
    private final AtomicLong dispatchDead = new AtomicLong();
    private final AtomicLong consumerReceived = new AtomicLong();
    private final AtomicLong consumerFailure = new AtomicLong();
    private final AtomicLong offlineStored = new AtomicLong();

    public void incDispatchSuccess() {
        dispatchSuccess.incrementAndGet();
    }

    public void incDispatchFailure() {
        dispatchFailure.incrementAndGet();
    }

    public void incDispatchDead() {
        dispatchDead.incrementAndGet();
    }

    public void incConsumerReceived() {
        consumerReceived.incrementAndGet();
    }

    public void incConsumerFailure() {
        consumerFailure.incrementAndGet();
    }

    public void incOfflineStored() {
        offlineStored.incrementAndGet();
    }

    public double getDispatchSuccess() {
        return dispatchSuccess.get();
    }

    public double getDispatchFailure() {
        return dispatchFailure.get();
    }

    public double getDispatchDead() {
        return dispatchDead.get();
    }

    public double getConsumerReceived() {
        return consumerReceived.get();
    }

    public double getConsumerFailure() {
        return consumerFailure.get();
    }

    public double getOfflineStored() {
        return offlineStored.get();
    }
}
