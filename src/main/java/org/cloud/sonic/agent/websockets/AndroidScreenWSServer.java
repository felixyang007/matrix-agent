/*
 *   sonic-agent  Agent of Sonic Cloud Real Machine Platform.
 *   Copyright (C) 2022 SonicCloudOrg
 *
 *   This program is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU Affero General Public License as published
 *   by the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   This program is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU Affero General Public License for more details.
 *
 *   You should have received a copy of the GNU Affero General Public License
 *   along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.cloud.sonic.agent.websockets;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.android.ddmlib.IDevice;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.bridge.android.AndroidDeviceBridgeTool;
import org.cloud.sonic.agent.common.config.WsEndpointConfigure;
import org.cloud.sonic.agent.common.maps.AndroidAPKMap;
import org.cloud.sonic.agent.common.maps.AndroidDeviceManagerMap;
import org.cloud.sonic.agent.common.maps.OccupyMap;
import org.cloud.sonic.agent.common.maps.ScreenMap;
import org.cloud.sonic.agent.common.maps.WebSocketSessionMap;
import org.cloud.sonic.agent.tests.android.minicap.MiniCapUtil;
import org.cloud.sonic.agent.tests.android.scrcpy.ScrcpyServerUtil;
import org.cloud.sonic.agent.tests.handlers.AndroidMonitorHandler;
import org.cloud.sonic.agent.tools.BytesTool;
import org.cloud.sonic.agent.tools.ScheduleTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

@Component
@Slf4j
@ServerEndpoint(value = "/websockets/android/screen/{key}/{udId}/{token}", configurator = WsEndpointConfigure.class)
public class AndroidScreenWSServer implements IAndroidWSServer {
    @Value("${sonic.agent.key}")
    private String key;
    private Map<String, String> typeMap = new ConcurrentHashMap<>();
    private Map<String, String> picMap = new ConcurrentHashMap<>();
    /**
     * udId -> 正在只读观看（?mode=watch）的会话。静态：占用释放在 TransportClient 里，
     * 要从那边找到并断开它；同时用 putIfAbsent 保证一台设备只有一路观看。
     */
    private static final Map<String, Session> WATCHERS = new ConcurrentHashMap<>();

    private AndroidMonitorHandler androidMonitorHandler = new AndroidMonitorHandler();

    @OnOpen
    public void onOpen(Session session, @PathParam("key") String secretKey,
                       @PathParam("udId") String udId, @PathParam("token") String token) throws Exception {
        if (secretKey.length() == 0 || (!secretKey.equals(key)) || token.length() == 0) {
            log.info("Auth Failed!");
            return;
        }
        IDevice iDevice = AndroidDeviceBridgeTool.getIDeviceByUdId(udId);
        if (iDevice == null) {
            log.info("Target device is not connecting, please check the connection.");
            return;
        }
        if (isWatchRequest(session)) {
            openWatch(session, iDevice, udId);
            return;
        }
        AndroidDeviceBridgeTool.screen(iDevice, "abort");

        session.getUserProperties().put("udId", udId);
        session.getUserProperties().put("id", String.format("%s-%s", this.getClass().getSimpleName(), udId));
        WebSocketSessionMap.addSession(session);
        saveUdIdMapAndSet(session, iDevice);

        int wait = 0;
        boolean isInstall = true;
        while (AndroidAPKMap.getMap().get(udId) == null || (!AndroidAPKMap.getMap().get(udId))) {
            Thread.sleep(500);
            wait++;
            if (wait >= 40) {
                isInstall = false;
                break;
            }
        }
        if (!isInstall) {
            log.info("Waiting for apk install timeout!");
            exit(session);
            return; // 同 issue #3：漏掉这个 return 会继续跑到 schedule 还没设置时就退出。
        }

        session.getUserProperties().put("schedule",ScheduleTool.schedule(() -> {
            log.info("time up!");
            if (session.isOpen()) {
                JSONObject errMsg = new JSONObject();
                errMsg.put("msg", "error");
                BytesTool.sendText(session, errMsg.toJSONString());
                exit(session);
            }
        }, BytesTool.remoteTimeout));

    }

    /**
     * 只读观看：给通过 REST API「远程占用」（occupy）的设备在网页上看画面用，URL 带 {@code ?mode=watch}。
     * <p>
     * 和完整远程控制页（AndroidWSServer + 本类）的区别：不拿设备锁、不碰远程 ADB 共享 / uia2 /
     * 输入法、不 {@code am start}、不改自动旋转设置（上面的 {@code screen(iDevice, "abort")}），
     * 所以 API 那边正在跑的自动化和 {@code adb connect} 都不受影响；关页面也不释放占用。
     * 占用结束（release / 强制解除 / 到点）时由 {@link #closeWatch(String)} 断开。
     * <p>
     * 一台设备同时只允许一路投屏：scrcpy server 用固定的 abstract socket 名，旋转监听
     * （AndroidMonitorHandler）也是按设备共用的，第二路会把第一路的监听踢掉。
     */
    private void openWatch(Session session, IDevice iDevice, String udId) {
        if (!OccupyMap.map.containsKey(udId)) {
            refuseWatch(session, udId, "notOccupied");
            return;
        }
        Session normal = WebSocketSessionMap.getSession(String.format("%s-%s", this.getClass().getSimpleName(), udId));
        if ((normal != null && normal.isOpen()) || !claimWatch(udId, session)) {
            refuseWatch(session, udId, "busy");
            return;
        }
        // 认领成功之后才写 udId / id：被拒绝的会话 exit() 时什么都不碰，免得按同一个 id
        // 把正在观看的那路会话从 WebSocketSessionMap 里删掉。
        session.getUserProperties().put("watch", Boolean.TRUE);
        session.getUserProperties().put("udId", udId);
        session.getUserProperties().put("id", String.format("%s-watch-%s", this.getClass().getSimpleName(), udId));
        WebSocketSessionMap.addSession(session);
        saveUdIdMapAndSet(session, iDevice);

        // 旋转监听（startMonitor）跑的是 Sonic APK 里的插件，完整页面靠 AndroidWSServer 装，
        // 这里自己检查。只装不启动 Activity，不会把被测 App 顶到后台。
        if (!AndroidDeviceBridgeTool.installSonicApk(iDevice)) {
            log.info("{} : watch refused (apk).", udId);
            JSONObject refused = new JSONObject();
            refused.put("msg", "watchRefused");
            refused.put("reason", "apk");
            BytesTool.sendText(session, refused.toJSONString());
            exit(session); // 已经认领并注册过，走完整清理
            return;
        }

        session.getUserProperties().put("schedule", ScheduleTool.schedule(() -> {
            log.info("time up!");
            if (session.isOpen()) {
                JSONObject errMsg = new JSONObject();
                errMsg.put("msg", "error");
                BytesTool.sendText(session, errMsg.toJSONString());
                exit(session);
            }
        }, BytesTool.remoteTimeout));

        log.info("{} : watch started.", udId);
        JSONObject ready = new JSONObject();
        ready.put("msg", "watchReady");
        BytesTool.sendText(session, ready.toJSONString());
    }

    /**
     * 原子地认领这台设备的观看位。已有认领但那路会话已经关了（理论上 exit 会摘掉，
     * 防御一下），就顶替它，免得残留记录让这台设备永远显示「有人在看」。
     */
    private static boolean claimWatch(String udId, Session session) {
        Session prev = WATCHERS.putIfAbsent(udId, session);
        return prev == null || (!prev.isOpen() && WATCHERS.replace(udId, prev, session));
    }

    private static boolean isWatchRequest(Session session) {
        List<String> mode = session.getRequestParameterMap().get("mode");
        return mode != null && mode.contains("watch");
    }

    private static void refuseWatch(Session session, String udId, String reason) {
        log.info("{} : watch refused ({}).", udId, reason);
        JSONObject refused = new JSONObject();
        refused.put("msg", "watchRefused");
        refused.put("reason", reason);
        BytesTool.sendText(session, refused.toJSONString());
        try {
            session.close();
        } catch (IOException e) {
            log.info(e.getMessage());
        }
    }

    /**
     * 远程占用结束时断开该设备的只读观看（没有就什么都不做）。
     */
    public static void closeWatch(String udId) {
        Session session = WATCHERS.get(udId);
        if (session == null || !session.isOpen()) {
            return;
        }
        JSONObject end = new JSONObject();
        end.put("msg", "watchEnd");
        BytesTool.sendText(session, end.toJSONString());
        try {
            session.close(); // 会触发 onClose -> exit，停掉 scrcpy 和旋转监听
        } catch (IOException e) {
            log.info(e.getMessage());
        }
    }

    @OnClose
    public void onClose(Session session) {
        exit(session);
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error(error.getMessage());
        error.printStackTrace();
        JSONObject errMsg = new JSONObject();
        errMsg.put("msg", "error");
        BytesTool.sendText(session, errMsg.toJSONString());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        JSONObject msg = JSON.parseObject(message);
        Object udIdProp = session.getUserProperties().get("udId");
        if (udIdProp == null) {
            // onOpen 提前返回的会话（鉴权失败、观看被拒绝等）：前端在 open 时就会发 switch，忽略即可。
            return;
        }
        log.info("{} send: {}", session.getUserProperties().get("id"), msg);
        String udId = udIdProp.toString();
        switch (msg.getString("type")) {
            case "switch" -> {
                typeMap.put(udId, msg.getString("detail"));
                IDevice iDevice = udIdMap.get(session);
                if (!androidMonitorHandler.isMonitorRunning(iDevice)) {
                    androidMonitorHandler.startMonitor(iDevice, res -> {
                        JSONObject rotationJson = new JSONObject();
                        rotationJson.put("msg", "rotation");
                        rotationJson.put("value", Integer.parseInt(res) * 90);
                        BytesTool.sendText(session, rotationJson.toJSONString());
                        startScreen(session);
                    });
                } else {
                    startScreen(session);
                }
            }
            case "pic" -> {
                picMap.put(udId, msg.getString("detail"));
                startScreen(session);
            }
        }
    }

    private void startScreen(Session session) {
        IDevice iDevice = udIdMap.get(session);
        if (iDevice != null) {
            Thread old = ScreenMap.getMap().get(session);
            if (old != null) {
                old.interrupt();
                do {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                while (ScreenMap.getMap().get(session) != null);
            }
            typeMap.putIfAbsent(iDevice.getSerialNumber(), "scrcpy");
            switch (typeMap.get(iDevice.getSerialNumber())) {
                case "scrcpy" -> {
                    ScrcpyServerUtil scrcpyServerUtil = new ScrcpyServerUtil();
                    Thread scrcpyThread = scrcpyServerUtil.start(iDevice.getSerialNumber(), AndroidDeviceManagerMap.getRotationMap().get(iDevice.getSerialNumber()), session);
                    ScreenMap.getMap().put(session, scrcpyThread);
                }
                case "minicap" -> {
                    MiniCapUtil miniCapUtil = new MiniCapUtil();
                    AtomicReference<String[]> banner = new AtomicReference<>(new String[24]);
                    Thread miniCapThread = miniCapUtil.start(
                            iDevice.getSerialNumber(), banner, null,
                            picMap.get(iDevice.getSerialNumber()) == null ? "high" : picMap.get(iDevice.getSerialNumber()),
                            AndroidDeviceManagerMap.getRotationMap().get(iDevice.getSerialNumber()), session
                    );
                    ScreenMap.getMap().put(session, miniCapThread);
                }
            }
            JSONObject picFinish = new JSONObject();
            picFinish.put("msg", "picFinish");
            BytesTool.sendText(session, picFinish.toJSONString());
        }
    }

    private void exit(Session session) {
        synchronized (session) {
            // 同 AndroidTerminalWSServer（issue #3）：onOpen 在 apk 安装等待超时时会提前
            // exit()，此时 "schedule" 还没写入；onClose 又会再调用一次。这里全部改成
            // null-safe，"udId" 在这个提前 return 分支之前已经写入（见 onOpen），所以
            // 这里读它本身没问题，只有 future 和 id 需要 guard。
            ScheduledFuture<?> future = (ScheduledFuture<?>) session.getUserProperties().get("schedule");
            if (future != null) {
                future.cancel(true);
            }
            Object udIdProp = session.getUserProperties().get("udId");
            String udId = udIdProp != null ? udIdProp.toString() : null;
            // stopMonitor 内部直接解引用，不能传 null（exit() 被二次调用时 udIdMap 已被
            // 第一次调用 remove 掉，这里会拿到 null）。
            IDevice targetDevice = udIdMap.get(session);
            if (targetDevice != null) {
                androidMonitorHandler.stopMonitor(targetDevice);
            }
            // onOpen 的提前 return（鉴权失败、设备不在线、观看被拒绝）都在写 "id" 之前，
            // 这里不能直接 removeSession(session)，否则在 Assert / NPE 上抛异常。
            Object idProp = session.getUserProperties().get("id");
            if (idProp != null) {
                WebSocketSessionMap.removeSession(idProp.toString());
            }
            removeUdIdMapAndSet(session);
            if (udId != null) {
                AndroidDeviceManagerMap.getRotationMap().remove(udId);
                typeMap.remove(udId);
                picMap.remove(udId);
            }
            if (ScreenMap.getMap().get(session) != null) {
                ScreenMap.getMap().get(session).interrupt();
            }
            if (udId != null) {
                WATCHERS.remove(udId, session); // 只摘自己的认领，不影响别的会话
            }
            try {
                session.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            Object id = session.getUserProperties().get("id");
            log.info("{} : quit.", id != null ? id.toString() : session.getId());
        }
    }
}
