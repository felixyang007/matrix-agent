/*
 *   matrix-agent  Agent of Matrix Cloud Real Machine Platform (Sonic fork).
 *   Copyright (C) 2022 SonicCloudOrg
 *
 *   Xcode Simulator bridge: wraps `xcrun simctl`, mirroring SibTool so the
 *   downstream device-pool / WDA / driver chain stays unchanged.
 *
 *   Simulator devices report as platform=IOS(2) + isSimulator=1, and their
 *   WDA (WebDriverAgentRunner) is launched with `xcodebuild test` targeting
 *   `platform=iOS Simulator,id=<udid>`. No usbmuxd / iproxy involved: the
 *   simulator shares the Mac network stack, so WDA binds localhost directly.
 */
package org.cloud.sonic.agent.bridge.ios;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import org.cloud.sonic.agent.common.interfaces.DeviceStatus;
import org.cloud.sonic.agent.common.interfaces.PlatformType;
import org.cloud.sonic.agent.tools.BytesTool;
import org.cloud.sonic.agent.common.maps.DevicesBatteryMap;
import org.cloud.sonic.agent.common.maps.GlobalProcessMap;
import org.cloud.sonic.agent.common.maps.IOSDeviceManagerMap;
import org.cloud.sonic.agent.common.maps.IOSInfoMap;
import org.cloud.sonic.agent.common.maps.IOSProcessMap;
import org.cloud.sonic.agent.tools.PortTool;
import org.cloud.sonic.agent.tools.ProcessCommandTool;
import org.cloud.sonic.agent.tools.ScheduleTool;
import org.cloud.sonic.agent.transport.TransportWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import jakarta.websocket.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Simulator counterpart of {@link SibTool}.
 *
 * <p>P0 scope: device discovery (booted simulators) + reporting (deviceDetail)
 * + WDA launch ({@link #startWda(String)}). Once booted, a simulator shows up
 * in the device management page as a normal iOS device.</p>
 */
@DependsOn({"iOSThreadPoolInit"})
@Component
@Order(value = Ordered.HIGHEST_PRECEDENCE)
public class SimctlTool implements ApplicationListener<ContextRefreshedEvent> {
    private static final Logger logger = LoggerFactory.getLogger(SimctlTool.class);

    private static final String SIMCTL = "xcrun simctl";

    /** Default poll interval for the simulator discovery loop (seconds). */
    private static final long DEFAULT_POLL_SECONDS = 10;

    @Value("${modules.ios.simulator.enabled:false}")
    private boolean simulatorEnabled;

    @Value("${modules.ios.simulator.poll-interval-seconds:10}")
    private long pollIntervalSeconds;

    /** Reuse the existing WDA xcode project path config (same as SibTool). */
    @Value("${modules.ios.wda-xcode-project-path:default}")
    private String getWdaXcodeProjectPath;

    /** Base dir for per-instance DerivedData (avoid xcodebuild build lock). */
    @Value("${modules.ios.simulator.derived-data-base:/tmp/matrix-simctl-deriveddata}")
    private String getDerivedDataBase;

    /** Reset each simulator to factory state before every test task. */
    @Value("${modules.ios.simulator.fresh-instance-per-task:false}")
    private boolean getFreshInstancePerTask;

    /** Static copies for use inside static helper methods (mirrors SibTool). */
    private static String wdaXcodeProjectPath = "default";
    private static String derivedDataBase = "/tmp/matrix-simctl-deriveddata";
    private static boolean freshInstancePerTask = false;

    /** Booted simulator udids, maintained by the poller; cheap {@link #isSimulator} check. */
    private static final Set<String> simulatorUdids = ConcurrentHashMap.newKeySet();

    /** Snapshot of reported udids used to diff online/offline across polls. */
    private static final Set<String> reportedUdids = ConcurrentHashMap.newKeySet();

    /** Cache deviceTypeIdentifier -> "WxH" physical resolution. */
    private static final Map<String, String> sizeCache = new ConcurrentHashMap<>();

    /** Build-once cache for the WDA test runner (shared across simulators). */
    private static final Object wdaBuildLock = new Object();
    private static volatile boolean wdaBuilt = false;
    private static volatile String wdaXctestrunTemplate = null;

    @PostConstruct
    public void setEnv() {
        wdaXcodeProjectPath = getWdaXcodeProjectPath;
        derivedDataBase = getDerivedDataBase;
        freshInstancePerTask = getFreshInstancePerTask;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (!simulatorEnabled) {
            logger.info("iOS Simulator module disabled (modules.ios.simulator.enabled=false).");
            return;
        }
        init();
        logger.info("Enable iOS Simulator module");
    }

    /**
     * Start the discovery loop. simctl has no streaming "listen" command, so we
     * poll `simctl list devices --json` and diff booted-set across polls to
     * synthesize online/offline events (mirrors `sib devices listen -d`).
     */
    public void init() {
        long interval = pollIntervalSeconds > 0 ? pollIntervalSeconds : DEFAULT_POLL_SECONDS;
        ScheduleTool.scheduleAtFixedRate(this::refreshAndReport, 5, interval, TimeUnit.SECONDS);
        logger.info("iOS Simulator discovery loop started (poll {}s).", interval);
    }

    /**
     * One poll: list booted simulators, report new as ONLINE, missing as
     * DISCONNECTED.
     */
    private void refreshAndReport() {
        List<JSONObject> booted = getBootedSimulators();
        Set<String> current = ConcurrentHashMap.newKeySet();
        for (JSONObject sim : booted) {
            String udId = sim.getString("udid");
            if (udId == null || udId.isEmpty()) {
                continue;
            }
            current.add(udId);
            if (!reportedUdids.contains(udId)) {
                sendOnlineStatus(sim);
            }
        }
        for (String udId : reportedUdids) {
            if (!current.contains(udId)) {
                sendDisConnectStatus(udId);
            }
        }
        reportedUdids.retainAll(current);
        simulatorUdids.retainAll(current);
    }

    // ---------------------------------------------------------------------
    // Device discovery
    // ---------------------------------------------------------------------

    /** Parse `xcrun simctl list devices --json` into a JSONObject (may be empty). */
    private static JSONObject parseSimctlList() {
        String json = ProcessCommandTool.getProcessLocalCommandStr(SIMCTL + " list devices --json");
        if (json == null || json.trim().isEmpty()) {
            return new JSONObject();
        }
        try {
            return JSON.parseObject(json);
        } catch (JSONException e) {
            logger.warn("Failed to parse `simctl list devices --json`: {}", e.getMessage());
            return new JSONObject();
        }
    }

    /** List booted simulators; each entry carries the parent runtime key. */
    public static List<JSONObject> getBootedSimulators() {
        List<JSONObject> result = new ArrayList<>();
        JSONObject devices = parseSimctlList().getJSONObject("devices");
        if (devices == null) {
            return result;
        }
        for (Map.Entry<String, Object> entry : devices.entrySet()) {
            String runtime = entry.getKey();
            Object value = entry.getValue();
            if (!(value instanceof List)) {
                continue;
            }
            for (Object item : (List<?>) value) {
                if (!(item instanceof JSONObject)) {
                    continue;
                }
                JSONObject sim = (JSONObject) item;
                if ("Booted".equals(sim.getString("state"))) {
                    sim.put("runtime", runtime);
                    result.add(sim);
                }
            }
        }
        return result;
    }

    /** udids of currently booted simulators (used by the "appears in device page" path). */
    public static List<String> getDeviceList() {
        List<String> result = new ArrayList<>();
        for (JSONObject sim : getBootedSimulators()) {
            String udId = sim.getString("udid");
            if (udId != null && !udId.isEmpty()) {
                result.add(udId);
            }
        }
        return result;
    }

    /** Cheap check used to make simulator udids pass device-existence guards. */
    public static boolean isSimulator(String udId) {
        return udId != null && simulatorUdids.contains(udId);
    }

    /** True when the "fresh instance per task" pool policy is enabled. */
    public static boolean isFreshInstancePerTask() {
        return freshInstancePerTask;
    }

    /**
     * True if udId is a known iOS device — real device (via sib) or simulator
     * (via simctl). Use this instead of {@code SibTool.getDeviceList().contains()}
     * at device-existence guards so simulators pass.
     */
    public static boolean isIOSDevice(String udId) {
        return isSimulator(udId) || SibTool.getDeviceList().contains(udId);
    }

    /**
     * Start WDA on the right backend: simulator via {@link #startWda(String)},
     * real device via {@link SibTool#startWda(String)}. Same return shape
     * {@code int[]{wdaPort, mjpegPort}}.
     */
    public static int[] startWdaDispatch(String udId) throws IOException, InterruptedException {
        return isSimulator(udId) ? startWda(udId) : SibTool.startWda(udId);
    }

    /**
     * Build WDA once for the simulator (build-for-testing) and cache the
     * generated .xctestrun template. Later calls return the cached path; the
     * per-instance ports are injected into a COPY of it, so one build serves
     * every simulator.
     *
     * <p>USE_PORT / MJPEG_SERVER_PORT are read by FBConfiguration from the test
     * process environment, and the scheme only exposes them via $(USE_PORT)
     * build-setting substitution baked into the .xctestrun AT BUILD TIME. So
     * neither a shell env prefix nor a `test-time` build setting works — the
     * .xctestrun file is the injection point (verified 2026-09).</p>
     */
    private static String ensureWdaBuilt() {
        if (wdaBuilt) {
            return wdaXctestrunTemplate;
        }
        synchronized (wdaBuildLock) {
            if (wdaBuilt) {
                return wdaXctestrunTemplate;
            }
            String dd = derivedDataBase + "-wda";
            String cmd = String.format(
                    "xcodebuild build-for-testing -project %s -scheme WebDriverAgentRunner " +
                            "-destination 'generic/platform=iOS Simulator' -derivedDataPath %s " +
                            "CODE_SIGNING_ALLOWED=NO GCC_TREAT_WARNINGS_AS_ERRORS=NO",
                    wdaXcodeProjectPath, dd);
            logger.info("Building WebDriverAgent for simulator (first run, cached): {}", cmd);
            ProcessCommandTool.getProcessLocalCommand(cmd);
            File products = new File(dd, "Build/Products");
            File[] xctestrunFiles = products.listFiles((d, n) -> n.endsWith(".xctestrun"));
            if (xctestrunFiles == null || xctestrunFiles.length == 0) {
                logger.error("No .xctestrun produced after WDA build under {}", products.getAbsolutePath());
                return null;
            }
            wdaXctestrunTemplate = xctestrunFiles[0].getAbsolutePath();
            wdaBuilt = true;
            return wdaXctestrunTemplate;
        }
    }

    // ---------------------------------------------------------------------
    // Reporting (deviceDetail over TransportWorker)
    // ---------------------------------------------------------------------

    /** runtime key e.g. com.apple.CoreSimulator.SimRuntime.iOS-18-0 -> "18.0". */
    private static String runtimeToVersion(String runtime) {
        if (runtime == null) {
            return "";
        }
        int idx = runtime.lastIndexOf('-');
        return idx >= 0 ? runtime.substring(idx + 1).replace('-', '.') : runtime;
    }

    /** Build a detail object shaped like SibTool's `deviceDetail` (same keys). */
    private static JSONObject buildDeviceDetail(JSONObject sim) {
        JSONObject detail = new JSONObject();
        detail.put("deviceName", sim.getString("name"));
        detail.put("generationName", sim.getString("deviceTypeIdentifier"));
        detail.put("productVersion", runtimeToVersion(sim.getString("runtime")));
        detail.put("cpuArchitecture", "arm64");
        return detail;
    }

    /**
     * Resolve a simulator's physical screen resolution (e.g. "1206x2622") from
     * the CoreSimulator device-type bundle profile.plist. Result is cached per
     * device type; unknown types resolve to "".
     */
    public static String getSize(String deviceTypeIdentifier) {
        if (deviceTypeIdentifier == null || deviceTypeIdentifier.isEmpty()) {
            return "";
        }
        String cached = sizeCache.get(deviceTypeIdentifier);
        if (cached != null) {
            return cached;
        }
        String resolved = resolveSize(deviceTypeIdentifier);
        if (!resolved.isEmpty()) {
            sizeCache.put(deviceTypeIdentifier, resolved);
        }
        return resolved;
    }

    private static String resolveSize(String deviceTypeIdentifier) {
        try {
            String bundlePath = null;
            String json = ProcessCommandTool.getProcessLocalCommandStr(SIMCTL + " list devicetypes -j");
            if (json == null || json.isEmpty()) {
                return "";
            }
            JSONArray types = JSON.parseObject(json).getJSONArray("devicetypes");
            if (types == null) {
                return "";
            }
            for (int i = 0; i < types.size(); i++) {
                JSONObject t = types.getJSONObject(i);
                if (deviceTypeIdentifier.equals(t.getString("identifier"))) {
                    bundlePath = t.getString("bundlePath");
                    break;
                }
            }
            if (bundlePath == null || bundlePath.isEmpty()) {
                return "";
            }
            String profilePath = bundlePath + "/Contents/Resources/profile.plist";
            String profileJson = ProcessCommandTool.getProcessLocalCommandStr(
                    String.format("plutil -convert json -o - '%s'", profilePath));
            if (profileJson == null || profileJson.isEmpty()) {
                return "";
            }
            JSONObject profile = JSON.parseObject(profileJson);
            Integer w = profile.getInteger("mainScreenWidth");
            Integer h = profile.getInteger("mainScreenHeight");
            if (w == null || h == null) {
                return "";
            }
            return w + "x" + h;
        } catch (Exception e) {
            logger.warn("Failed to resolve simulator size for {}: {}", deviceTypeIdentifier, e.getMessage());
            return "";
        }
    }

    public static void sendOnlineStatus(JSONObject sim) {
        String udId = sim.getString("udid");
        if (udId == null || udId.isEmpty()) {
            return;
        }
        JSONObject detail = buildDeviceDetail(sim);
        JSONObject deviceStatus = new JSONObject();
        deviceStatus.put("msg", "deviceDetail");
        deviceStatus.put("udId", udId);
        deviceStatus.put("name", detail.getString("deviceName"));
        deviceStatus.put("model", detail.getString("generationName"));
        deviceStatus.put("status", DeviceStatus.ONLINE);
        deviceStatus.put("platform", PlatformType.IOS);
        deviceStatus.put("version", detail.getString("productVersion"));
        deviceStatus.put("size", getSize(sim.getString("deviceTypeIdentifier")));
        deviceStatus.put("cpu", detail.getString("cpuArchitecture"));
        deviceStatus.put("manufacturer", "APPLE");
        deviceStatus.put("isSimulator", 1);     // new field, ignored by server until `is_simulator` column lands
        TransportWorker.send(deviceStatus);
        IOSInfoMap.getDetailMap().put(udId, detail);
        IOSDeviceManagerMap.getMap().remove(udId);
        DevicesBatteryMap.getTempMap().remove(udId);
        reportedUdids.add(udId);
        simulatorUdids.add(udId);
        logger.info("iOS Simulator: {} ONLINE!", udId);
    }

    public static void sendDisConnectStatus(String udId) {
        JSONObject deviceStatus = new JSONObject();
        deviceStatus.put("msg", "deviceDetail");
        deviceStatus.put("udId", udId);
        deviceStatus.put("status", DeviceStatus.DISCONNECTED);
        deviceStatus.put("platform", PlatformType.IOS);
        TransportWorker.send(deviceStatus);
        IOSDeviceManagerMap.getMap().remove(udId);
        DevicesBatteryMap.getTempMap().remove(udId);
        reportedUdids.remove(udId);
        simulatorUdids.remove(udId);
        logger.info("iOS Simulator: {} OFFLINE!", udId);
    }

    // ---------------------------------------------------------------------
    // WDA launch (simulator: no usbmuxd / iproxy)
    // ---------------------------------------------------------------------

    /** Allocate a free port pair and start WDA. */
    public static int[] startWda(String udId) throws IOException, InterruptedException {
        Socket wda = PortTool.getBindSocket();
        Socket mjpeg = PortTool.getBindSocket();
        int wdaPort = PortTool.releaseAndGetPort(wda);
        int mjpegPort = PortTool.releaseAndGetPort(mjpeg);
        return startWda(udId, wdaPort, mjpegPort);
    }

    /**
     * Start WDA on the simulator.
     *
     * <p>Simulator WDA binds directly to the Mac localhost, so no iproxy is
     * needed — the returned ports are the actual WDA HTTP / MJPEG ports.</p>
     *
     * <p>Port injection relies on USE_PORT / MJPEG_SERVER_PORT being honoured
     * by the WDA runner environment. For a single instance the scheme defaults
     * (8100 / 9100) work as-is; multi-instance port injection is a P1 item to
     * verify against the matrix-ios-wda scheme.</p>
     */
    public static int[] startWda(String udId, int wdaPort, int mjpegPort) throws IOException, InterruptedException {
        List<Process> processList = IOSProcessMap.getMap().get(udId);
        if (processList != null) {
            for (Process p : processList) {
                if (p != null) {
                    p.children().forEach(ProcessHandle::destroy);
                    p.destroy();
                }
            }
        }

        // Build WDA once (shared derived-data), then inject the per-instance
        // ports into a copy of its .xctestrun and launch via test-without-building.
        String xctestrun = ensureWdaBuilt();
        if (xctestrun == null) {
            logger.error("WebDriverAgent build-for-testing failed; cannot start WDA.");
            return new int[]{0, 0};
        }
        File productsDir = new File(xctestrun).getParentFile();
        File modified = new File(productsDir, String.format("wda-%s-%d.xctestrun", udId, wdaPort));
        try {
            Files.copy(new File(xctestrun).toPath(), modified.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.error("Failed to copy xctestrun for {}", udId, e);
            return new int[]{0, 0};
        }
        ProcessCommandTool.getProcessLocalCommand(String.format(
                "plutil -replace WebDriverAgentRunner.EnvironmentVariables.USE_PORT -string %d '%s'",
                wdaPort, modified.getAbsolutePath()));
        ProcessCommandTool.getProcessLocalCommand(String.format(
                "plutil -replace WebDriverAgentRunner.EnvironmentVariables.MJPEG_SERVER_PORT -string %d '%s'",
                mjpegPort, modified.getAbsolutePath()));

        String commandLine = String.format(
                "xcodebuild test-without-building -xctestrun '%s' -destination 'platform=iOS Simulator,id=%s'",
                modified.getAbsolutePath(), udId);

        logger.info("Starting simulator WDA for {}: {}", udId, commandLine);
        String system = System.getProperty("os.name").toLowerCase();
        Process wdaProcess;
        if (system.contains("win")) {
            wdaProcess = Runtime.getRuntime().exec(new String[]{"cmd", "/c", commandLine});
        } else {
            wdaProcess = Runtime.getRuntime().exec(new String[]{"sh", "-c", commandLine});
        }

        BufferedReader stdInput = new BufferedReader(new InputStreamReader(wdaProcess.getInputStream()));
        Semaphore isFinish = new Semaphore(0);
        Thread wdaThread = new Thread(() -> {
            String s;
            while (true) {
                try {
                    if ((s = stdInput.readLine()) == null) {
                        break;
                    }
                } catch (IOException e) {
                    logger.info(e.getMessage());
                    break;
                }
                logger.info(s);
                if (s.contains("ServerURLHere->")) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    isFinish.release();
                }
            }
            try {
                stdInput.close();
            } catch (IOException e) {
                logger.info(e.getMessage());
            }
            logger.info("Simulator WebDriverAgent print thread shutdown.");
        });
        wdaThread.start();

        int wait = 0;
        while (!isFinish.tryAcquire()) {
            Thread.sleep(500);
            wait++;
            if (wait >= 120) {
                logger.info("{} simulator WebDriverAgent start timeout!", udId);
                return new int[]{0, 0};
            }
        }

        List<Process> newProcessList = new ArrayList<>();
        newProcessList.add(wdaProcess);
        IOSProcessMap.getMap().put(udId, newProcessList);
        return new int[]{wdaPort, mjpegPort};
    }

    // ---------------------------------------------------------------------
    // Basic simctl ops (App install/launch used by a minimal smoke run)
    // ---------------------------------------------------------------------

    public static void install(String udId, String path) {
        ProcessCommandTool.getProcessLocalCommand(String.format("%s install %s %s", SIMCTL, udId, path));
    }

    public static void launch(String udId, String bundleId) {
        ProcessCommandTool.getProcessLocalCommand(String.format("%s launch %s %s", SIMCTL, udId, bundleId));
    }

    public static void terminate(String udId, String bundleId) {
        ProcessCommandTool.getProcessLocalCommand(String.format("%s terminate %s %s", SIMCTL, udId, bundleId));
    }

    public static void uninstall(String udId, String bundleId) {
        ProcessCommandTool.getProcessLocalCommand(String.format("%s uninstall %s %s", SIMCTL, udId, bundleId));
    }

    // ---------------------------------------------------------------------
    // Log streaming (terminal tab): simctl spawn log stream
    // ---------------------------------------------------------------------

    public static void stopSysLog(String udId) {
        String processName = String.format("process-%s-simctl-syslog", udId);
        if (GlobalProcessMap.getMap().get(processName) != null) {
            Process ps = GlobalProcessMap.getMap().get(processName);
            ps.children().forEach(ProcessHandle::destroy);
            ps.destroy();
        }
    }

    /**
     * Stream the simulator unified log via `simctl spawn <udid> log stream`.
     * Mirrors `sib syslog -f`: the filter is a simple substring match on each
     * line. Each matching line is sent as a `logDetail` websocket message.
     */
    public static void getSysLog(String udId, String filter, Session session) {
        new Thread(() -> {
            stopSysLog(udId);
            String system = System.getProperty("os.name").toLowerCase();
            Process ps = null;
            String commandLine = String.format("%s spawn %s log stream --style compact", SIMCTL, udId);
            try {
                if (system.contains("win")) {
                    ps = Runtime.getRuntime().exec(new String[]{"cmd", "/c", commandLine});
                } else {
                    ps = Runtime.getRuntime().exec(new String[]{"sh", "-c", commandLine});
                }
            } catch (Exception e) {
                logger.info(e.getMessage());
                return;
            }
            String processName = String.format("process-%s-simctl-syslog", udId);
            GlobalProcessMap.getMap().put(processName, ps);
            BufferedReader stdInput = new BufferedReader(new InputStreamReader(ps.getInputStream()));
            String s;
            while (true) {
                try {
                    if ((s = stdInput.readLine()) == null) {
                        break;
                    }
                } catch (IOException e) {
                    logger.info(e.getMessage());
                    break;
                }
                if (s.startsWith("Timestamp")) {
                    continue; // skip the --style compact header line
                }
                if (filter == null || filter.length() == 0 || s.contains(filter)) {
                    JSONObject logDetail = new JSONObject();
                    logDetail.put("msg", "logDetail");
                    logDetail.put("detail", s);
                    BytesTool.sendText(session, logDetail.toJSONString());
                }
            }
            try {
                stdInput.close();
            } catch (IOException e) {
                logger.info(e.getMessage());
            }
            logger.info("simulator syslog done.");
        }).start();
    }

    // ---------------------------------------------------------------------
    // Location (remote "mock location"): simctl location set/clear
    // ---------------------------------------------------------------------

    public static void locationSet(String udId, String longitude, String latitude) {
        // simctl order is <lat>,<lon>
        ProcessCommandTool.getProcessLocalCommand(
                String.format("%s location %s set %s,%s", SIMCTL, udId, latitude, longitude));
    }

    public static void locationUnset(String udId) {
        ProcessCommandTool.getProcessLocalCommand(
                String.format("%s location %s clear", SIMCTL, udId));
    }

    /** Bundle ids of user apps installed on the simulator (mirrors `sib app list`). */
    public static List<String> getAppList(String udId) {
        return getAppList(udId, null).stream()
                .filter(e -> e.getString("bundleId") != null)
                .map(e -> e.getString("bundleId"))
                .collect(Collectors.toList());
    }

    /**
     * List user apps via `simctl listapps` (piped through plutil to JSON).
     * Each entry is shaped like `sib app list` (bundleId / name / version);
     * icon and shortVersion are omitted for the simulator (not exposed by
     * `listapps`). Streams appListDetail when a session is supplied.
     */
    public static List<JSONObject> getAppList(String udId, Session session) {
        List<JSONObject> result = new ArrayList<>();
        String json = ProcessCommandTool.getProcessLocalCommandStr(
                String.format("%s listapps %s | plutil -convert json -o - -", SIMCTL, udId));
        if (json == null || json.isEmpty()) {
            return result;
        }
        JSONObject apps;
        try {
            apps = JSON.parseObject(json);
        } catch (JSONException e) {
            logger.warn("Failed to parse simctl listapps for {}: {}", udId, e.getMessage());
            return result;
        }
        for (Map.Entry<String, Object> entry : apps.entrySet()) {
            Object v = entry.getValue();
            if (!(v instanceof JSONObject)) {
                continue;
            }
            JSONObject app = (JSONObject) v;
            if (!"User".equals(app.getString("ApplicationType"))) {
                continue;
            }
            JSONObject appInfo = new JSONObject();
            appInfo.put("bundleId", app.getString("CFBundleIdentifier"));
            String name = app.getString("CFBundleDisplayName");
            if (name == null || name.isEmpty()) {
                name = app.getString("CFBundleName");
            }
            appInfo.put("name", name);
            appInfo.put("version", app.getString("CFBundleVersion"));
            if (session != null) {
                JSONObject appList = new JSONObject();
                appList.put("msg", "appListDetail");
                appList.put("detail", appInfo);
                BytesTool.sendText(session, appList.toJSONString());
            } else {
                result.add(appInfo);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Lifecycle (P1: per-task fresh instance — used later by the pool policy)
    // ---------------------------------------------------------------------

    public static String create(String name, String deviceType, String runtime) {
        String output = ProcessCommandTool.getProcessLocalCommandStr(
                String.format("%s create %s %s %s", SIMCTL, name, deviceType, runtime));
        // `simctl create` prints the new udid on stdout.
        return output != null ? output.trim() : "";
    }

    public static void boot(String udId) {
        ProcessCommandTool.getProcessLocalCommand(String.format("%s boot %s", SIMCTL, udId));
    }

    public static void shutdown(String udId) {
        ProcessCommandTool.getProcessLocalCommand(String.format("%s shutdown %s", SIMCTL, udId));
    }

    public static void reboot(String udId) {
        shutdown(udId);
        boot(udId);
    }

    public static void erase(String udId) {
        ProcessCommandTool.getProcessLocalCommand(String.format("%s erase %s", SIMCTL, udId));
    }

    /**
     * Reset a simulator to factory state: shutdown -> erase -> boot.
     * `simctl erase` fails on a Booted device (SimError 405), so shutdown first.
     */
    public static void eraseAndBoot(String udId) {
        shutdown(udId);
        erase(udId);
        boot(udId);
    }

    public static void delete(String udId) {
        ProcessCommandTool.getProcessLocalCommand(String.format("%s delete %s", SIMCTL, udId));
    }
}