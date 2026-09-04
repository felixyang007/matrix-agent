#!/usr/bin/env bash
#
# 一键给 sonic-agent 打上 Android 14+ 投屏黑屏补丁。
# 做法：用本目录的补丁版 sonic-android-scrcpy.jar 替换目标 agent 的
#       plugins/sonic-android-scrcpy.jar（原文件自动备份为 *.orig-bak）。
#
# 用法：
#   ./patch-agent-scrcpy.sh [AGENT_DIR] [--restart]
#     AGENT_DIR   sonic-agent 部署目录；不填则自动探测常见路径
#     --restart   打完补丁后自动重启 agent（kill 旧 java 进程并 nohup 重启）
#
# 背景：scrcpy 1.23 用 SurfaceControl.createDisplay(String,boolean) 建投屏虚拟显示，
#      该私有 API 在 Android 14(API 34) 被移除 → Android 14/15/16/17 投屏黑屏。
#      补丁改用 DisplayManager.createVirtualDisplay 为主路径。
#      源码：fork matrix-android-scrcpy 分支 v1.23-android14-fix。
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PATCHED_JAR="$SCRIPT_DIR/sonic-android-scrcpy.jar"
EXPECT_MD5="6343af52e6e3ff8ad1986c2c7450b929"

md5of() { if command -v md5 >/dev/null 2>&1; then md5 -q "$1"; else md5sum "$1" | awk '{print $1}'; fi; }

# --- 参数 ---
AGENT_DIR=""
RESTART=0
for a in "$@"; do
  case "$a" in
    --restart) RESTART=1 ;;
    *) AGENT_DIR="$a" ;;
  esac
done

# --- 校验补丁 jar ---
[ -f "$PATCHED_JAR" ] || { echo "❌ 找不到补丁 jar：$PATCHED_JAR"; exit 1; }
got="$(md5of "$PATCHED_JAR")"
[ "$got" = "$EXPECT_MD5" ] || { echo "⚠️ 补丁 jar md5 不符（$got ≠ $EXPECT_MD5），文件可能损坏"; exit 1; }

# --- 定位 agent 目录 ---
if [ -z "$AGENT_DIR" ]; then
  for c in "." "$PWD" "$HOME/sonic-agent" "$HOME/code/Sonic/sonic-agent" "$HOME/Sonic/sonic-agent"; do
    if [ -f "$c/plugins/sonic-android-scrcpy.jar" ]; then AGENT_DIR="$c"; break; fi
  done
fi
if [ -z "$AGENT_DIR" ] || [ ! -f "$AGENT_DIR/plugins/sonic-android-scrcpy.jar" ]; then
  echo "❌ 未找到 agent（缺 plugins/sonic-android-scrcpy.jar）。"
  echo "   用法：$0 <sonic-agent 目录> [--restart]"
  exit 1
fi
AGENT_DIR="$(cd "$AGENT_DIR" && pwd)"
TARGET="$AGENT_DIR/plugins/sonic-android-scrcpy.jar"

# --- 幂等：已是补丁版则跳过 ---
if [ "$(md5of "$TARGET")" = "$EXPECT_MD5" ]; then
  echo "✅ 已是补丁版：$AGENT_DIR （无需操作）"
  exit 0
fi

# --- 备份 + 替换 ---
[ -f "$TARGET.orig-bak" ] || cp "$TARGET" "$TARGET.orig-bak"
cp "$PATCHED_JAR" "$TARGET"
echo "✅ 已打补丁：$TARGET"
echo "   原文件备份 → $TARGET.orig-bak"

# --- 可选重启 ---
if [ "$RESTART" = "1" ]; then
  echo "→ 重启 agent ..."
  pkill -f "sonic-agent-.*\.jar" 2>/dev/null || true
  sleep 2
  JAR="$(ls "$AGENT_DIR"/sonic-agent-*.jar 2>/dev/null | head -1)"
  if [ -n "$JAR" ]; then
    ( cd "$AGENT_DIR" && nohup java -Dfile.encoding=utf-8 -Dspring.profiles.active=sonic-agent -jar "$(basename "$JAR")" > agent.log 2>&1 & )
    echo "   已重启：$(basename "$JAR")（日志 $AGENT_DIR/agent.log）"
  else
    echo "   ⚠️ 未找到 sonic-agent-*.jar，请手动重启 agent。"
  fi
else
  echo "→ 请重启 agent 使补丁生效（kill 旧 java 进程后重新 java -jar 启动，或加 --restart 让本脚本代劳）。"
fi
