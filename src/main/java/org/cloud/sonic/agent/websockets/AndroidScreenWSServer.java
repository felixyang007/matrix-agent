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
        log.info("{} send: {}", session.getUserProperties().get("id").toString(), msg);
        String udId = session.getUserProperties().get("udId").toString();
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
            WebSocketSessionMap.removeSession(session);
            removeUdIdMapAndSet(session);
            if (udId != null) {
                AndroidDeviceManagerMap.getRotationMap().remove(udId);
                typeMap.remove(udId);
                picMap.remove(udId);
            }
            if (ScreenMap.getMap().get(session) != null) {
                ScreenMap.getMap().get(session).interrupt();
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
