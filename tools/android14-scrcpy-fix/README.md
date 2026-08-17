# Android 14+ 投屏黑屏补丁（scrcpy）

给 **任意 sonic-agent 机器**一键打上 Android 14/15/16/17 投屏黑屏修复，替代逐台手搬 jar。

## 为什么需要

scrcpy 1.23 用私有 API `SurfaceControl.createDisplay(String, boolean)` 建投屏虚拟显示，
该方法在 **Android 14 (API 34) 被移除** → 新安卓上编码器启动即崩、投屏黑屏。
补丁改用 `DisplayManager.createVirtualDisplay(...)` 为主路径（旧系统仍回退 SurfaceControl）。

- 源码：fork **`matrix-android-scrcpy`** 分支 **`v1.23-android14-fix`**
- 产物：本目录 `sonic-android-scrcpy.jar`（md5 `6343af52e6e3ff8ad1986c2c7450b929`，基于 scrcpy 1.23，wire 协议/版本号不变，agent 无需改动）

## 用法

```bash
# 打补丁（自动探测常见 agent 路径；也可显式传目录）
./patch-agent-scrcpy.sh /path/to/sonic-agent

# 打补丁并自动重启 agent
./patch-agent-scrcpy.sh /path/to/sonic-agent --restart
```

脚本会：校验补丁 jar md5 → 定位 `plugins/sonic-android-scrcpy.jar` → 已是补丁版则跳过（幂等）→
备份原文件为 `*.orig-bak` → 替换 → 提示或自动重启。跨 macOS / Linux。

## 新机接入 SOP

- **走本仓库发布的 agent 包**：仓库 `plugins/sonic-android-scrcpy.jar` 已是补丁版，`.github/workflows/release.yml` 会把它拷进各平台 zip，所以**新 release 的 agent 包已自带补丁，新机零操作**。
- **用官方/旧包**：解压后、启动前，对目录跑一次本脚本 `./patch-agent-scrcpy.sh <agent目录>`。

> ⚠️ **维护提醒**：别把 `plugins/sonic-android-scrcpy.jar` 从上游同步回原版，否则新安卓投屏黑屏回归。
> （本想在 release.yml 那步加防回退注释，但改 `.github/workflows/*` 需要 gh token 的 `workflow` 权限，当前 token 没有，故说明放这里。）

## 回退

```bash
cp plugins/sonic-android-scrcpy.jar.orig-bak plugins/sonic-android-scrcpy.jar   # 还原后重启 agent
```

## 重新构建补丁 jar（可选）

```bash
git clone -b v1.23-android14-fix https://github.com/felixyang007/matrix-android-scrcpy.git
cd matrix-android-scrcpy/server && mkdir -p build_manual && \
ANDROID_HOME=~/Library/Android/sdk ANDROID_PLATFORM=36 ANDROID_BUILD_TOOLS=36.0.0 \
BUILD_DIR="$(pwd)/build_manual" ./build_without_gradle.sh
# 产物 build_manual/scrcpy-server → 即 sonic-android-scrcpy.jar
```
