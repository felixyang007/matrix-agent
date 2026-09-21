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

import com.alibaba.fastjson.JSONObject;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.cloud.sonic.agent.bridge.ios.SibTool;
import org.cloud.sonic.agent.bridge.ios.SimctlTool;
import org.cloud.sonic.agent.common.config.WsEndpointConfigure;
import org.cloud.sonic.agent.common.maps.WebSocketSessionMap;
import org.cloud.sonic.agent.tests.ios.mjpeg.MjpegInputStream;
import org.cloud.sonic.agent.tools.BytesTool;
import org.cloud.sonic.agent.tools.ScheduleTool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.concurrent.ScheduledFuture;

import static org.cloud.sonic.agent.tools.BytesTool.sendByte;

@Component
@Slf4j
@ServerEndpoint(value = "/websockets/ios/screen/{key}/{udId}/{token}", configurator = WsEndpointConfigure.class)
public class IOSScreenWSServer implements IIOSWSServer {
    @Value("${sonic.agent.key}")
    private String key;
    @Value("${sonic.agent.port}")
    private int port;

    @OnOpen
    public void onOpen(Session session, @PathParam("key") String secretKey,
                       @PathParam("udId") String udId, @PathParam("token") String token) throws InterruptedException {
        if (secretKey.length() == 0 || (!secretKey.equals(key)) || token.length() == 0) {
            log.info("Auth Failed!");
            return;
        }

        if (!SimctlTool.isIOSDevice(udId)) {
            log.info("Target device is not connecting, please check the connection.");
            return;
        }

        session.getUserProperties().put("udId", udId);
        session.getUserProperties().put("id", String.format("%s-%s", this.getClass().getSimpleName(), udId));
        WebSocketSessionMap.addSession(session);
        saveUdIdMapAndSet(session, udId);

        // screenMap 由 IOSWSServer 在 driver 起来之后才写入，所以这里等的其实是
        // 整个 WDA 启动耗时。真机 WDA 已装好、秒级就绪，原来 120*500ms=60s 够用；
        // 模拟器是 xcodebuild build-for-testing + test-without-building 冷启动，
        // 实测 83 秒 —— 60 秒预算必然先到期，然后这里静默 return，页面永远黑屏，
        // 等多久都没用（WDA 其实 20 多秒后就好了）。放宽到 240*500ms=240s 覆盖冷
        // 启动；有热 WDA 可复用时是秒级，这个上限不会真的用满。
        int screenPort = 0;
        int wait = 0;
        int maxWait = 240;
        while (wait < maxWait) {
            Integer p = IOSWSServer.screenMap.get(udId);
            if (p != null) {
                screenPort = p;
                break;
            }
            Thread.sleep(500);
            wait++;
        }
        if (screenPort == 0) {
            // 原来是裸 return，黑屏时日志里没有任何线索，只能靠对时间戳才能发现
            // 是这里放弃的。
            log.info("{} wait for mjpeg port timeout after {}s, screen will stay blank.",
                    udId, maxWait / 2);
            JSONObject errMsg = new JSONObject();
            errMsg.put("msg", "error");
            BytesTool.sendText(session, errMsg.toJSONString());
            return;
        }
        int finalScreenPort = screenPort;
        new Thread(() -> {
            URL url;
            try {
                url = new URL("http://localhost:" + finalScreenPort);
            } catch (MalformedURLException e) {
                return;
            }
            MjpegInputStream mjpegInputStream = null;
            int waitMjpeg = 0;
            while (mjpegInputStream == null) {
                try {
                    mjpegInputStream = new MjpegInputStream(url.openStream());
                } catch (IOException e) {
                    log.info(e.getMessage());
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    log.info(e.getMessage());
                    return;
                }
                waitMjpeg++;
                if (waitMjpeg >= 20) {
                    log.info("mjpeg server connect fail");
                    return;
                }
            }
            ByteBuffer bufferedImage;
            int i = 0;
            while (true) {
                try {
                    if ((bufferedImage = mjpegInputStream.readFrameForByteBuffer()) == null) break;
                } catch (IOException e) {
                    log.info(e.getMessage());
                    break;
                }
                i++;
                if (i % 3 != 0) {
                    sendByte(session, bufferedImage);
                } else {
                    i = 0;
                }
            }
            try {
                mjpegInputStream.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            log.info("screen done.");
        }).start();

        session.getUserProperties().put("schedule", ScheduleTool.schedule(() -> {
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
    }

    private void exit(Session session) {
        synchronized (session) {
            // 同 issue #3：onOpen 鉴权/设备检测失败、或等 MJPEG 端口 60s 超时（screenPort==0
            // 直接 return，无清理）都会导致 onClose 调这里时 "schedule" 还没写入过。
            ScheduledFuture<?> future = (ScheduledFuture<?>) session.getUserProperties().get("schedule");
            if (future != null) {
                future.cancel(true);
            }
            WebSocketSessionMap.removeSession(session);
            removeUdIdMapAndSet(session);
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
