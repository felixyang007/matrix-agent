package org.cloud.sonic.agent.common.maps;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * status
 */
public class OccupyMap {
    public static Map<String, ScheduledFuture<?>> map = new ConcurrentHashMap<>();

    /**
     * udId -> 占用时的平台（PlatformType）。stopDebug（强制解除）的消息里不带平台，
     * 释放时又必须分 Android / iOS 走不同流程，所以在占用时记下来。
     */
    public static Map<String, Integer> platformMap = new ConcurrentHashMap<>();
}
