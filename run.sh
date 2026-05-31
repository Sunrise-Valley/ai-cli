#!/usr/bin/env bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 加载 .env（如果存在）
if [ -f "$SCRIPT_DIR/.env" ]; then
  export $(grep -v '^#' "$SCRIPT_DIR/.env" | xargs)
fi

JAR="$SCRIPT_DIR/cli/target/scala-3.4.2/ai-agent.jar"

# Java 查找优先级：
# 1. 捆绑的 runtime/（jlink 精简 JRE，跨机器免配置）
# 2. 系统 JAVA_HOME
# 3. PATH 中的 java（版本检查，需要 11+）
BUNDLED_JAVA="$SCRIPT_DIR/runtime/bin/java"

find_java() {
  if [ -f "$BUNDLED_JAVA" ]; then
    echo "$BUNDLED_JAVA"
    return
  fi

  if [ -n "$JAVA_HOME" ] && [ -f "$JAVA_HOME/bin/java" ]; then
    local ver
    ver=$("$JAVA_HOME/bin/java" -version 2>&1 | awk -F'"' '/version/{print $2}' | cut -d. -f1)
    [ "${ver:-0}" -ge 11 ] 2>/dev/null && echo "$JAVA_HOME/bin/java" && return
  fi

  if command -v java &>/dev/null; then
    local ver
    ver=$(java -version 2>&1 | awk -F'"' '/version/{print $2}' | cut -d. -f1)
    [ "${ver:-0}" -ge 11 ] 2>/dev/null && echo "java" && return
  fi

  echo ""
}

JAVA_BIN=$(find_java)

if [ -z "$JAVA_BIN" ]; then
  echo "错误：未找到 Java 11+ 运行时。"
  echo "请运行 ./build-runtime.sh 生成捆绑 JRE，或安装 Java 11+。"
  exit 1
fi

if [ -f "$JAR" ]; then
  "$JAVA_BIN" -jar "$JAR" "$@"
else
  echo "错误：未找到 $JAR"
  echo "请先运行：sbt 'cli/assembly'"
  exit 1
fi
