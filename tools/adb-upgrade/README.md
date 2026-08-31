# adb 版本统一升级（platform-tools）

给 **任意 sonic-agent 机器**一键把内置 `plugins/adb` 升级/统一到指定版本（默认 Google 官方 **r37.0.1**），
并检查整机所有 adb 的版本一致性。替代逐台手搬二进制。

## 为什么需要

- 仓库 `release.yml` 原来从**已归档**的 `SonicCloudOrg/sonic-adb-binary` 硬编码拉 r34.0.3：版本老、无法跟进上游。
- **不同版本的 adb 客户端会互杀同一个 adb server（端口 5037）** → agent 反复丢设备（见 `docs/ARCHITECTURE.md` §5 已知坑）。
  所以不只是"新"，还要**这台机器上所有 adb（SDK / homebrew / 其它自动化框架）版本一致**。
- r34.0.3 对新安卓设备兼容性差。

## 用法

```bash
# 升级（自动探测常见 agent 路径；也可显式传目录）
./patch-agent-adb.sh /path/to/sonic-agent

# 升级并自动重启 agent
./patch-agent-adb.sh /path/to/sonic-agent --restart
```

脚本会：
1. 按平台（macOS→darwin / Linux→linux / Windows(git-bash)→win）从 **dl.google.com 官方直链**下载
   `platform-tools_r37.0.1-<平台>.zip`（注：windows 包名后缀是 `-win` 不是 `-windows`）；
2. 校验下载包内 `adb version` 确为 37.0.1（损坏即中止，不会留下坏状态）；
3. 已是 37.0.1 则跳过（**幂等**，不破坏手工软链）；
4. 备份原 `plugins/adb` 为 `adb.orig-bak`，然后把 platform-tools 全量内容覆盖进 `plugins/`
   （与 `release.yml` 的 `mv platform-tools/* plugins/` 行为一致，fastboot/lib64 等成套升级）；
5. `which -a adb` 扫描整机所有 adb，版本 ≠ 37.0.1 的逐个 ⚠️ 提示（不强制改动，由你决定统一方式）；
6. 提示重启或 `--restart` 自动重启（重启后看 agent.log 的 `ADB version:` 行确认）。

需要联网（约 9–16 MB）；依赖 curl / unzip（macOS / Linux / git-bash 均自带）。

## 改版本号

脚本顶部 `TARGET_VERSION="37.0.1"`，改成目标版本即可（先用
`curl -sI https://dl.google.com/android/repository/platform-tools_r<版本>-darwin.zip` 确认该版本存在）。

## 新机接入 SOP / 发布包

- `.github/workflows/release.yml` 已改为**直接从 dl.google.com 拉官方包**（不再依赖归档 fork），新 release 的 agent 包自带 37.0.1。
- ⚠️ 该 workflow 改动需要 gh token 的 `workflow` 权限才能 push（PLAN 已知限制）；在权限到位前发布的包仍是 r34.0.3，解压后跑一次本脚本即可。

## 回退

```bash
cp plugins/adb.orig-bak plugins/adb   # 还原后重启 agent
```

## 已知环境提示

- 本机（local-mac）`plugins/adb` 是指向 homebrew adb 的**软链**（已是 37.0.1，脚本会幂等跳过）；
  原版 34.0.3 备份在 `plugins/adb.orig-34.0.3`。
- 本机 Android SDK 里还有一个 **37.0.0**（`~/Library/Android/sdk/platform-tools/adb`），与本目标 37.0.1 差一个小版本，
  也会互杀 server；建议用 Android Studio SDK Manager 升到同版本，或临时不用它。
