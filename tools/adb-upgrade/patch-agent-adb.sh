#!/usr/bin/env bash
#
# 一键把 sonic-agent 内置 adb 升级/统一到指定版本（默认 Google 官方 r37.0.1）。
# 做法：从 dl.google.com 下载对应平台官方 platform-tools zip，覆盖目标 agent 的
#       plugins/（原 plugins/adb 自动备份为 adb.orig-bak），并检查整机 adb 版本一致性。
#
# 用法：
#   ./patch-agent-adb.sh [AGENT_DIR] [--restart]
#     AGENT_DIR   sonic-agent 部署目录；不填则自动探测常见路径
#     --restart   升级后自动重启 agent（kill 旧 java 进程并 nohup 重启）
#
# 背景：
#   - 仓库 release.yml 原来从已归档的 SonicCloudOrg/sonic-adb-binary 拉 r34.0.3，
#     版本过老且无法跟进上游；不同版本 adb 客户端会互杀同一个 adb server(5037)，
#     导致 agent 反复丢设备 → 所有机器必须统一到同一版本。
#   - 本脚本对存量 agent 立即生效；新发布包改由 release.yml 直接拉官方包（见
#     tools/adb-upgrade/README.md）。
#
# 下载源（官方直链，按平台自动选择）：
#   https://dl.google.com/android/repository/platform-tools_r<ver>-darwin.zip
#   https://dl.google.com/android/repository/platform-tools_r<ver>-linux.zip
#   https://dl.google.com/android/repository/platform-tools_r<ver>-win.zip
#   （注：windows 包名后缀是 -win 不是 -windows）
#
set -euo pipefail

TARGET_VERSION="37.0.1"
DOWNLOAD_BASE="https://dl.google.com/android/repository"

# --- 参数 ---
AGENT_DIR=""
RESTART=0
for a in "$@"; do
  case "$a" in
    --restart) RESTART=1 ;;
    *) AGENT_DIR="$a" ;;
  esac
done

# --- 平台探测（决定下载哪个官方包）---
case "$(uname -s)" in
  Darwin)  PLATFORM="darwin" ;;
  Linux)   PLATFORM="linux" ;;
  MINGW*|MSYS*|CYGWIN*) PLATFORM="win" ;;
  *) echo "❌ 不支持的平台：$(uname -s)"; exit 1 ;;
esac
ZIP_URL="$DOWNLOAD_BASE/platform-tools_r${TARGET_VERSION}-${PLATFORM}.zip"

command -v curl >/dev/null 2>&1 || { echo "❌ 缺少 curl，请先安装"; exit 1; }
command -v unzip >/dev/null 2>&1 || { echo "❌ 缺少 unzip，请先安装"; exit 1; }

# --- 定位 agent 目录（须存在 plugins/）---
if [ -z "$AGENT_DIR" ]; then
  for c in "." "$PWD" "$HOME/sonic-agent" "$HOME/code/Sonic/sonic-agent" "$HOME/Sonic/sonic-agent"; do
    if [ -f "$c/plugins/adb" ] || [ -f "$c/plugins/adb.exe" ]; then AGENT_DIR="$c"; break; fi
  done
fi
if [ -z "$AGENT_DIR" ]; then
  echo "❌ 未找到 agent（缺 plugins/adb）。用法：$0 <sonic-agent 目录> [--restart]"
  exit 1
fi
AGENT_DIR="$(cd "$AGENT_DIR" && pwd)"
PLUGINS="$AGENT_DIR/plugins"
if [ ! -d "$PLUGINS" ]; then
  echo "❌ 目录异常：$PLUGINS 不存在"
  exit 1
fi

adb_ver_of() { # 输出形如 37.0.1 的版本号；不可用则输出空
  local out
  out="$("$1" version 2>/dev/null | grep -m1 '^Version' | awk '{print $2}')" || true
  echo "${out%%-*}"
}

# --- 幂等：plugins/adb 已是目标版本则跳过替换 ---
ADB_BIN="$PLUGINS/adb"; [ "$PLATFORM" = "win" ] && ADB_BIN="$PLUGINS/adb.exe"
if [ -e "$ADB_BIN" ]; then
  CUR="$(adb_ver_of "$ADB_BIN")"
  if [ "$CUR" = "$TARGET_VERSION" ]; then
    echo "✅ 已是 ${TARGET_VERSION}：${ADB_BIN}（无需替换）"
    ADB_UP_TO_DATE=1
  else
    echo "→ 当前 plugins/adb 版本：${CUR:-未知}，将升级到 $TARGET_VERSION"
    ADB_UP_TO_DATE=0
  fi
else
  echo "→ $ADB_BIN 不存在（首次安装）"
  ADB_UP_TO_DATE=0
fi

# --- 下载 + 校验 + 覆盖 ---
if [ "$ADB_UP_TO_DATE" = "0" ]; then
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  echo "→ 下载 $ZIP_URL ..."
  curl -fsSL --retry 3 -o "$TMP/platform-tools.zip" "$ZIP_URL"
  unzip -q -o "$TMP/platform-tools.zip" -d "$TMP"
  NEW_ADB="$TMP/platform-tools/adb"; [ "$PLATFORM" = "win" ] && NEW_ADB="$TMP/platform-tools/adb.exe"
  [ -f "$NEW_ADB" ] || { echo "❌ 下载包异常（解压后无 adb）"; exit 1; }
  chmod +x "$NEW_ADB"
  GOT="$(adb_ver_of "$NEW_ADB")"
  [ "$GOT" = "$TARGET_VERSION" ] || { echo "❌ 下载包版本校验失败：$GOT ≠ ${TARGET_VERSION}（zip 可能损坏，请重试）"; exit 1; }

  # 备份现有 adb（保留符号链接本体 -P）
  if [ -e "$ADB_BIN" ] || [ -L "$ADB_BIN" ]; then
    [ -e "$ADB_BIN.orig-bak" ] || cp -P "$ADB_BIN" "$ADB_BIN.orig-bak"
    echo "   原文件备份 → $ADB_BIN.orig-bak"
    rm -f "$ADB_BIN"
  fi
  # 与 release.yml 行为一致：platform-tools 全量内容覆盖进 plugins/（adb/fastboot/依赖库成套升级）
  cp -R "$TMP/platform-tools/." "$PLUGINS/"
  chmod +x "$ADB_BIN"
  echo "✅ 已升级：$ADB_BIN → $(adb_ver_of "$ADB_BIN")"
fi

# --- 整机 adb 版本一致性检查（不同版本客户端会互杀 adb server 导致丢设备）---
echo "→ 检查整机 adb 版本一致性 ..."
MISMATCH=0
if [ "$PLATFORM" = "win" ]; then
  ADB_PATHS=$(where adb 2>/dev/null || true)
else
  ADB_PATHS=$(which -a adb 2>/dev/null || true)
fi
if [ -n "${ADB_PATHS:-}" ]; then
  while IFS= read -r p; do
    [ -x "$p" ] || continue
    v="$(adb_ver_of "$p")"
    if [ "$v" = "$TARGET_VERSION" ]; then
      echo "   ✅ $p ($v)"
    else
      echo "   ⚠️ $p (${v:-不可用}) ≠ $TARGET_VERSION —— 版本不一致会互杀 adb server(5037) 导致 agent 丢设备！"
      MISMATCH=1
    fi
  done <<< "$ADB_PATHS"
fi
if [ "$MISMATCH" = "1" ]; then
  echo "   ↑ 请将上述 adb 统一升级/替换到 ${TARGET_VERSION}（例如 ANDROID_HOME/platform-tools、homebrew、其它自动化框架的 adb）。"
fi

# --- 可选重启 ---
if [ "$ADB_UP_TO_DATE" = "0" ] || [ "$RESTART" = "1" ]; then
  if [ "$RESTART" = "1" ]; then
    echo "→ 重启 agent ..."
    pkill -f "sonic-agent-.*\.jar" 2>/dev/null || true
    sleep 2
    JAR="$(ls "$AGENT_DIR"/sonic-agent-*.jar 2>/dev/null | head -1)"
    if [ -n "$JAR" ]; then
      ( cd "$AGENT_DIR" && nohup java -Dfile.encoding=utf-8 -Dspring.profiles.active=sonic-agent -jar "$(basename "$JAR")" > agent.log 2>&1 & )
      echo "   已重启：$(basename "$JAR")（日志 $AGENT_DIR/agent.log，启动后看 'ADB version:' 行确认）"
    else
      echo "   ⚠️ 未找到 sonic-agent-*.jar，请手动重启 agent。"
    fi
  else
    echo "→ 请重启 agent 使新 adb 生效（kill 旧 java 进程后重新 java -jar 启动，或加 --restart 让本脚本代劳）。"
  fi
fi
