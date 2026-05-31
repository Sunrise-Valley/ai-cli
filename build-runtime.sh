#!/usr/bin/env bash
# 用 jlink 为当前平台生成捆绑 JRE，存入 runtime/
# 需要：JDK 11+（包含 jlink 和 jdeps）
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/cli/target/scala-3.4.2/ai-agent.jar"

# ── 定位 JDK ──────────────────────────────────────────────────────────────
find_jdk_home() {
  # 常见位置：Homebrew macOS
  for d in \
    /opt/homebrew/Cellar/openjdk/*/libexec/openjdk.jdk/Contents/Home \
    /usr/local/Cellar/openjdk/*/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home \
    /usr/lib/jvm/java-17-openjdk-* \
    /usr/lib/jvm/java-21-openjdk-* \
    /usr/lib/jvm/temurin-17 \
    /usr/lib/jvm/temurin-21; do
    # 展开 glob
    for expanded in $d; do
      [ -f "$expanded/bin/jlink" ] && echo "$expanded" && return
    done
  done

  # 回退：JAVA_HOME
  [ -f "${JAVA_HOME:-}/bin/jlink" ] && echo "$JAVA_HOME" && return

  # 回退：PATH 中的 java → 推断 JAVA_HOME
  if command -v java &>/dev/null; then
    local jbin
    jbin=$(command -v java)
    # 解链接
    while [ -L "$jbin" ]; do jbin=$(readlink "$jbin"); done
    local jhome
    jhome=$(dirname "$(dirname "$jbin")")
    [ -f "$jhome/bin/jlink" ] && echo "$jhome" && return
  fi

  echo ""
}

JDK_HOME=$(find_jdk_home)
if [ -z "$JDK_HOME" ]; then
  echo "错误：未找到包含 jlink 的 JDK（需要 JDK 11+）。"
  echo "  macOS：brew install openjdk"
  echo "  Ubuntu：sudo apt install openjdk-17-jdk"
  exit 1
fi

echo "使用 JDK：$JDK_HOME"
JLINK="$JDK_HOME/bin/jlink"
JDEPS="$JDK_HOME/bin/jdeps"

# ── 确保 jar 存在 ─────────────────────────────────────────────────────────
if [ ! -f "$JAR" ]; then
  echo "未找到 $JAR，先编译打包..."
  cd "$SCRIPT_DIR"
  sbt "cli/assembly"
fi

# ── 用 jdeps 分析所需模块 ──────────────────────────────────────────────────
echo "分析模块依赖..."
DETECTED=$("$JDEPS" \
  --multi-release 17 \
  --ignore-missing-deps \
  -q \
  --print-module-deps \
  "$JAR" 2>/dev/null || echo "")

# 合并额外必要模块（TLS/HTTPS、日志）
EXTRA="java.logging,jdk.crypto.ec,jdk.crypto.cryptoki"
MODULES="${DETECTED:+$DETECTED,}$EXTRA"
# 去重
MODULES=$(echo "$MODULES" | tr ',' '\n' | sort -u | tr '\n' ',' | sed 's/,$//')

echo "模块列表：$MODULES"

# ── 生成精简 JRE ───────────────────────────────────────────────────────────
RUNTIME_DIR="$SCRIPT_DIR/runtime"
rm -rf "$RUNTIME_DIR"

"$JLINK" \
  --no-header-files \
  --no-man-pages \
  --strip-debug \
  --compress=zip-6 \
  --add-modules "$MODULES" \
  --output "$RUNTIME_DIR"

SIZE=$(du -sh "$RUNTIME_DIR" | cut -f1)
echo ""
echo "捆绑 JRE 已生成：$RUNTIME_DIR（$SIZE）"
echo "现在可以直接运行：./run.sh \"你的目标\""
