#!/usr/bin/env bash
# 用 GraalVM Native Image 编译独立二进制文件
# 输出：dist/ai-agent（无需 JVM，直接运行）
#
# 用法：
#   ./build-native.sh           # 直接编译
#   ./build-native.sh --capture # 先用 tracing agent 捕获反射配置，再编译
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/cli/target/scala-3.4.2/ai-agent.jar"
DIST_DIR="$SCRIPT_DIR/dist"
CONFIGS_DIR="$SCRIPT_DIR/native-image-configs"

# ── 定位 GraalVM ───────────────────────────────────────────────────────────
find_graalvm() {
  # 1. 项目内捆绑（.graalvm/）
  for d in "$SCRIPT_DIR"/.graalvm/*/Contents/Home "$SCRIPT_DIR"/.graalvm/*; do
    [ -f "$d/bin/native-image" ] && echo "$d" && return
  done
  # 2. GRAALVM_HOME 环境变量
  [ -f "${GRAALVM_HOME:-}/bin/native-image" ] && echo "$GRAALVM_HOME" && return
  # 3. JAVA_HOME
  [ -f "${JAVA_HOME:-}/bin/native-image" ] && echo "$JAVA_HOME" && return
  # 4. PATH
  if command -v native-image &>/dev/null; then
    local ni
    ni=$(command -v native-image)
    while [ -L "$ni" ]; do ni=$(readlink "$ni"); done
    echo "$(dirname "$(dirname "$ni")")" && return
  fi
  echo ""
}

GRAALVM_HOME_FOUND=$(find_graalvm)
if [ -z "$GRAALVM_HOME_FOUND" ]; then
  echo "错误：未找到 GraalVM（含 native-image）。"
  echo "  macOS：brew install --cask graalvm-jdk"
  echo "  或下载后解压到 .graalvm/ 目录"
  echo "  下载：https://github.com/graalvm/graalvm-ce-builds/releases/latest"
  exit 1
fi

export GRAALVM_HOME="$GRAALVM_HOME_FOUND"
export JAVA_HOME="$GRAALVM_HOME"
echo "使用 GraalVM：$GRAALVM_HOME"
"$GRAALVM_HOME/bin/java" -version 2>&1 | head -1

# ── 可选阶段：tracing agent 捕获反射配置 ────────────────────────────────────
if [ "$1" == "--capture" ]; then
  echo ""
  echo "=== tracing agent 配置捕获（约 8 秒）==="

  if [ ! -f "$JAR" ]; then
    echo "先编译 fat jar..."
    /opt/homebrew/bin/sbt "cli/assembly"
  fi

  if [ -f "$SCRIPT_DIR/.env" ]; then
    export $(grep -v '^#' "$SCRIPT_DIR/.env" | xargs)
  fi

  CAPTURED="$SCRIPT_DIR/native-image-configs-captured"
  rm -rf "$CAPTURED" && mkdir -p "$CAPTURED"

  timeout 8 "$GRAALVM_HOME/bin/java" \
    -agentlib:native-image-agent=config-merge-dir="$CAPTURED" \
    -jar "$JAR" "__capture__" 2>/dev/null || true

  # 合并到 native-image-configs/
  "$GRAALVM_HOME/bin/native-image-configure" merge \
    --input-dir="$CAPTURED" \
    --input-dir="$CONFIGS_DIR" \
    --output-dir="$CONFIGS_DIR" 2>/dev/null || cp -n "$CAPTURED"/*.json "$CONFIGS_DIR/" 2>/dev/null || true

  rm -rf "$CAPTURED"
  echo "配置已合并到 $CONFIGS_DIR"
fi

# ── 编译原生二进制 ─────────────────────────────────────────────────────────
echo ""
echo "=== native-image 编译（通常 40-60 秒）==="
mkdir -p "$DIST_DIR"

/opt/homebrew/bin/sbt "cli/nativeImage"

echo ""
SIZE=$(du -sh "$DIST_DIR/ai-agent" 2>/dev/null | cut -f1 || echo "?")
echo "=== 完成：dist/ai-agent（$SIZE）==="
echo "运行：./dist/ai-agent \"你的目标\""
