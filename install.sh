#!/usr/bin/env bash
# ai-agent 安装脚本
# 用法：bash install.sh [选项]
#   --native    强制使用 native-image 模式（需要 GraalVM）
#   --jar       强制使用 fat JAR 模式（需要 JDK 17+）
#   --no-path   不修改 PATH（不写 .zshrc/.bashrc）
#   --prefix=   安装目录，默认 ~/.local/bin
set -euo pipefail

# ── 颜色 ───────────────────────────────────────────────────────────────────
if [ -t 1 ]; then
  RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
  BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; RESET='\033[0m'
else
  RED=''; GREEN=''; YELLOW=''; BLUE=''; CYAN=''; BOLD=''; RESET=''
fi

info()    { echo -e "${CYAN}[•]${RESET} $*"; }
success() { echo -e "${GREEN}[✓]${RESET} $*"; }
warn()    { echo -e "${YELLOW}[!]${RESET} $*"; }
error()   { echo -e "${RED}[✗]${RESET} $*" >&2; }
die()     { error "$*"; exit 1; }
ask()     { echo -en "${BOLD}$*${RESET} "; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── 参数 ───────────────────────────────────────────────────────────────────
MODE=""
NO_PATH=false
INSTALL_PREFIX="${HOME}/.local/bin"

for arg in "$@"; do
  case "$arg" in
    --native)   MODE=native ;;
    --jar)      MODE=jar ;;
    --no-path)  NO_PATH=true ;;
    --prefix=*) INSTALL_PREFIX="${arg#*=}" ;;
    -h|--help)
      echo "用法：bash install.sh [--native|--jar] [--no-path] [--prefix=DIR]"
      exit 0 ;;
    *) warn "未知选项：${arg}（已忽略）" ;;
  esac
done

# ── Banner ─────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}${BLUE}  ╔══════════════════════════════════╗${RESET}"
echo -e "${BOLD}${BLUE}  ║     ai-agent  installer          ║${RESET}"
echo -e "${BOLD}${BLUE}  ╚══════════════════════════════════╝${RESET}"
echo ""

OS=$(uname -s)
ARCH=$(uname -m)
info "平台：$OS / $ARCH"

# ══════════════════════════════════════════════════════════════════════════
# 检测函数
# ══════════════════════════════════════════════════════════════════════════

find_graalvm() {
  # 1. 项目内捆绑
  for d in \
    "$SCRIPT_DIR"/.graalvm/*/Contents/Home \
    "$SCRIPT_DIR"/.graalvm/*; do
    for expanded in $d; do
      [ -f "$expanded/bin/native-image" ] && echo "$expanded" && return
    done
  done
  # 2. macOS Homebrew cask 安装位置
  for d in \
    /Library/Java/JavaVirtualMachines/graalvm-*/Contents/Home \
    /Library/Java/JavaVirtualMachines/graalvm-community-*/Contents/Home \
    /opt/homebrew/opt/graalvm*/libexec/Contents/Home; do
    for expanded in $d; do
      [ -f "$expanded/bin/native-image" ] && echo "$expanded" && return
    done
  done
  # 3. 环境变量
  [ -f "${GRAALVM_HOME:-}/bin/native-image" ] && echo "$GRAALVM_HOME" && return
  [ -f "${JAVA_HOME:-}/bin/native-image"    ] && echo "$JAVA_HOME"    && return
  # 4. PATH
  if command -v native-image &>/dev/null; then
    local ni; ni=$(command -v native-image)
    while [ -L "$ni" ]; do ni=$(readlink "$ni"); done
    echo "$(dirname "$(dirname "$ni")")" && return
  fi
  echo ""
}

find_jdk() {
  local candidates=(
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
    /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
    /usr/local/opt/openjdk/libexec/openjdk.jdk/Contents/Home
    /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
    /Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home
    /usr/lib/jvm/temurin-21
    /usr/lib/jvm/temurin-17
    /usr/lib/jvm/java-21-openjdk-amd64
    /usr/lib/jvm/java-21-openjdk-arm64
    /usr/lib/jvm/java-17-openjdk-amd64
  )
  for d in "${candidates[@]}"; do
    [ -f "$d/bin/jlink" ] && echo "$d" && return
  done
  [ -f "${JAVA_HOME:-}/bin/jlink" ] && echo "$JAVA_HOME" && return
  if command -v java &>/dev/null; then
    local jbin; jbin=$(command -v java)
    while [ -L "$jbin" ]; do jbin=$(readlink "$jbin"); done
    local jhome; jhome=$(dirname "$(dirname "$jbin")")
    [ -f "$jhome/bin/jlink" ] && echo "$jhome" && return
  fi
  echo ""
}

find_sbt() {
  command -v sbt            &>/dev/null && echo "sbt"            && return
  [ -f /opt/homebrew/bin/sbt ] && echo "/opt/homebrew/bin/sbt"  && return
  [ -f /usr/local/bin/sbt    ] && echo "/usr/local/bin/sbt"     && return
  echo ""
}

needs_build() {
  [ "$MODE" = "native" ] && [ ! -f "$NATIVE_BIN" ] && return 0
  [ "$MODE" = "jar"    ] && [ ! -f "$FAT_JAR"    ] && return 0
  return 1
}

# ══════════════════════════════════════════════════════════════════════════
# 安装函数
# ══════════════════════════════════════════════════════════════════════════

install_graalvm() {
  local VER="25.0.2"
  echo ""
  info "需要安装 GraalVM Community JDK ${VER}（含 native-image）"
  echo ""

  # ── macOS ──────────────────────────────────────────────────────────────
  if [ "$OS" = "Darwin" ]; then
    if command -v brew &>/dev/null; then
      echo "  检测到 Homebrew，可一键安装。"
      ask "  通过 brew install --cask graalvm-jdk 安装？[Y/n]"
      read -r yn
      if [[ "${yn:-Y}" =~ ^[Yy] ]]; then
        brew install --cask graalvm-jdk
        # brew cask 安装后需要执行 xattr 移除隔离属性（macOS Gatekeeper）
        for d in /Library/Java/JavaVirtualMachines/graalvm-jdk-*/Contents/Home \
                 /Library/Java/JavaVirtualMachines/graalvm-community-jdk-*/Contents/Home; do
          for expanded in $d; do
            [ -d "$expanded" ] && sudo xattr -r -d com.apple.quarantine "$expanded" 2>/dev/null || true
          done
        done
        GRAALVM_HOME_FOUND=$(find_graalvm)
        if [ -n "$GRAALVM_HOME_FOUND" ]; then
          success "GraalVM 安装成功：${GRAALVM_HOME_FOUND}"
          return 0
        fi
        warn "brew 安装完成，但未能自动定位 native-image，尝试下载独立包..."
      fi
    else
      warn "未检测到 Homebrew。"
      echo "  可先安装 Homebrew：/bin/bash -c \"\$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)\""
    fi
    local ARCH_TAG; [ "$ARCH" = "arm64" ] && ARCH_TAG="macos-aarch64" || ARCH_TAG="macos-x64"

  # ── Linux ──────────────────────────────────────────────────────────────
  elif [ "$OS" = "Linux" ]; then
    local ARCH_TAG; [ "$ARCH" = "aarch64" ] && ARCH_TAG="linux-aarch64" || ARCH_TAG="linux-x64"
    if command -v apt-get &>/dev/null; then
      echo "  GraalVM 不在 apt 官方源，将直接下载 tar.gz 包（推荐）。"
    elif command -v sdk &>/dev/null; then
      echo "  检测到 SDKMAN，可通过 sdk install java ${VER}-graalce 安装。"
      ask "  通过 SDKMAN 安装？[Y/n]"
      read -r yn
      if [[ "${yn:-Y}" =~ ^[Yy] ]]; then
        sdk install java "${VER}-graalce"
        GRAALVM_HOME_FOUND=$(find_graalvm)
        if [ -n "$GRAALVM_HOME_FOUND" ]; then
          success "GraalVM 安装成功：${GRAALVM_HOME_FOUND}"
          return 0
        fi
      fi
    fi
  else
    die "不支持的操作系统：${OS}，请手动安装 GraalVM。"
  fi

  # ── 通用：下载 tar.gz 到 .graalvm/ ─────────────────────────────────────
  local TARBALL="graalvm-community-jdk-${VER}_${ARCH_TAG}_bin.tar.gz"
  local URL="https://github.com/graalvm/graalvm-ce-builds/releases/download/jdk-${VER}/${TARBALL}"
  echo ""
  info "将下载 GraalVM 到项目目录 .graalvm/"
  info "下载地址：${URL}"
  info "文件大小约 300-400 MB，请确保网络畅通。"
  echo ""
  ask "  确认下载？[Y/n]"
  read -r yn
  [[ "${yn:-Y}" =~ ^[Yy] ]] || die "已取消。请手动安装 GraalVM 后重试。"

  mkdir -p "$SCRIPT_DIR/.graalvm"
  local TARBALL_PATH="$SCRIPT_DIR/.graalvm/${TARBALL}"

  if command -v curl &>/dev/null; then
    curl -L --progress-bar -o "$TARBALL_PATH" "$URL"
  elif command -v wget &>/dev/null; then
    wget --show-progress -O "$TARBALL_PATH" "$URL"
  else
    die "需要 curl 或 wget 来下载，请先安装其中之一。"
  fi

  info "解压中..."
  tar xzf "$TARBALL_PATH" -C "$SCRIPT_DIR/.graalvm"
  rm "$TARBALL_PATH"

  GRAALVM_HOME_FOUND=$(find_graalvm)
  [ -n "$GRAALVM_HOME_FOUND" ] \
    && success "GraalVM 已就绪：${GRAALVM_HOME_FOUND}" \
    || die "解压后未能识别 GraalVM，请检查 $SCRIPT_DIR/.graalvm 目录。"
}

install_jdk() {
  echo ""
  info "需要安装 JDK 21+"
  echo ""

  # ── macOS ──────────────────────────────────────────────────────────────
  if [ "$OS" = "Darwin" ]; then
    if command -v brew &>/dev/null; then
      echo "  检测到 Homebrew，可一键安装。"
      ask "  通过 brew install openjdk@21 安装？[Y/n]"
      read -r yn
      if [[ "${yn:-Y}" =~ ^[Yy] ]]; then
        brew install openjdk@21
        # brew 安装后需手动 link（Homebrew 提示）
        local opt_path="/opt/homebrew/opt/openjdk@21"
        [ -d "$opt_path" ] || opt_path="/usr/local/opt/openjdk@21"
        if [ -f "${opt_path}/libexec/openjdk.jdk/Contents/Home/bin/jlink" ]; then
          JDK_HOME_FOUND="${opt_path}/libexec/openjdk.jdk/Contents/Home"
          success "JDK 安装成功：${JDK_HOME_FOUND}"
          return 0
        fi
        JDK_HOME_FOUND=$(find_jdk)
        [ -n "$JDK_HOME_FOUND" ] && success "JDK 安装成功：${JDK_HOME_FOUND}" && return 0
        warn "brew 安装完成，但未能自动定位 JDK。"
      fi
    fi
    echo ""
    die "请手动安装 JDK：brew install openjdk@21，然后重新运行此脚本。"

  # ── Linux ──────────────────────────────────────────────────────────────
  elif [ "$OS" = "Linux" ]; then
    if command -v apt-get &>/dev/null; then
      ask "  通过 apt 安装 openjdk-21-jdk？（需要 sudo）[Y/n]"
      read -r yn
      if [[ "${yn:-Y}" =~ ^[Yy] ]]; then
        sudo apt-get update -qq && sudo apt-get install -y openjdk-21-jdk
        JDK_HOME_FOUND=$(find_jdk)
        [ -n "$JDK_HOME_FOUND" ] && success "JDK 安装成功：${JDK_HOME_FOUND}" && return 0
      fi
    elif command -v dnf &>/dev/null; then
      ask "  通过 dnf 安装 java-21-openjdk-devel？（需要 sudo）[Y/n]"
      read -r yn
      if [[ "${yn:-Y}" =~ ^[Yy] ]]; then
        sudo dnf install -y java-21-openjdk-devel
        JDK_HOME_FOUND=$(find_jdk)
        [ -n "$JDK_HOME_FOUND" ] && success "JDK 安装成功：${JDK_HOME_FOUND}" && return 0
      fi
    elif command -v yum &>/dev/null; then
      ask "  通过 yum 安装 java-21-openjdk-devel？（需要 sudo）[Y/n]"
      read -r yn
      if [[ "${yn:-Y}" =~ ^[Yy] ]]; then
        sudo yum install -y java-21-openjdk-devel
        JDK_HOME_FOUND=$(find_jdk)
        [ -n "$JDK_HOME_FOUND" ] && success "JDK 安装成功：${JDK_HOME_FOUND}" && return 0
      fi
    else
      warn "未检测到 apt/dnf/yum，请手动安装 JDK 21+。"
    fi
    die "JDK 安装失败，请手动安装后重试。"

  else
    die "不支持的操作系统：${OS}，请手动安装 JDK 21+。"
  fi
}

install_sbt() {
  echo ""
  info "需要安装 sbt（Scala 构建工具）"
  echo ""

  if [ "$OS" = "Darwin" ]; then
    if command -v brew &>/dev/null; then
      ask "  通过 brew install sbt 安装？[Y/n]"
      read -r yn
      if [[ "${yn:-Y}" =~ ^[Yy] ]]; then
        brew install sbt
        SBT_CMD=$(find_sbt)
        [ -n "$SBT_CMD" ] && success "sbt 安装成功" && return 0
      fi
    fi
  elif [ "$OS" = "Linux" ]; then
    if command -v apt-get &>/dev/null; then
      ask "  通过 apt 安装 sbt？（需要 sudo）[Y/n]"
      read -r yn
      if [[ "${yn:-Y}" =~ ^[Yy] ]]; then
        # 添加 sbt 官方源
        local GPG_KEY_URL="https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x99E82A75642AC823"
        curl -fsSL "$GPG_KEY_URL" \
          | sudo gpg --dearmor -o /usr/share/keyrings/scalasbt-archive-keyring.gpg
        echo "deb [signed-by=/usr/share/keyrings/scalasbt-archive-keyring.gpg] https://repo.scala-sbt.org/scalasbt/debian all main" \
          | sudo tee /etc/apt/sources.list.d/sbt.list > /dev/null
        sudo apt-get update -qq && sudo apt-get install -y sbt
        SBT_CMD=$(find_sbt)
        [ -n "$SBT_CMD" ] && success "sbt 安装成功" && return 0
      fi
    elif command -v dnf &>/dev/null || command -v yum &>/dev/null; then
      ask "  通过 rpm 安装 sbt？（需要 sudo）[Y/n]"
      read -r yn
      if [[ "${yn:-Y}" =~ ^[Yy] ]]; then
        curl -fsSL "https://repo.scala-sbt.org/scalasbt/rpm/sbt.repo" \
          | sudo tee /etc/yum.repos.d/sbt.repo > /dev/null
        local pm; command -v dnf &>/dev/null && pm=dnf || pm=yum
        sudo $pm install -y sbt
        SBT_CMD=$(find_sbt)
        [ -n "$SBT_CMD" ] && success "sbt 安装成功" && return 0
      fi
    fi
  fi

  echo ""
  warn "无法自动安装 sbt，请手动安装："
  echo "  macOS：brew install sbt"
  echo "  Linux：https://www.scala-sbt.org/download"
  die "sbt 未安装，请手动安装后重试。"
}

# ══════════════════════════════════════════════════════════════════════════
# 阶段 1：确定构建模式 + 安装缺失依赖
# ══════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${BOLD}▶ 阶段 1/3：选择部署模式${RESET}"
echo ""

NATIVE_BIN="$SCRIPT_DIR/dist/ai-agent"
FAT_JAR="$SCRIPT_DIR/cli/target/scala-3.4.2/ai-agent.jar"
BUNDLED_JAVA="$SCRIPT_DIR/runtime/bin/java"

GRAALVM_HOME_FOUND=$(find_graalvm)
JDK_HOME_FOUND=$(find_jdk)
SBT_CMD=$(find_sbt)

# ── 自动模式：优先使用已有产物，再检查工具链 ───────────────────────────────
if [ -z "$MODE" ]; then
  if [ -f "$NATIVE_BIN" ]; then
    MODE=native
    info "发现已有 native binary，直接使用"
  elif [ -f "$FAT_JAR" ]; then
    MODE=jar
    info "发现已有 fat JAR"
  elif [ -n "$GRAALVM_HOME_FOUND" ]; then
    MODE=native
    info "发现 GraalVM，将编译 native binary（无需 JVM，推荐）"
  elif [ -n "$JDK_HOME_FOUND" ]; then
    MODE=jar
    info "发现 JDK，将打包 fat JAR + 精简 JRE"
  else
    # ── 两种条件都不具备：让用户选择并安装 ───────────────────────────────
    echo ""
    warn "未找到 GraalVM 或 JDK，需要先安装运行时环境。"
    echo ""
    echo "  请选择部署模式："
    echo "  1) native binary（推荐）"
    echo "     编译后单文件、无需 JVM，启动快"
    echo "     需要安装：GraalVM Community JDK（含 native-image）"
    echo ""
    echo "  2) fat JAR + 精简 JRE"
    echo "     兼容性好，无需 GraalVM"
    echo "     需要安装：JDK 17+"
    echo ""
    ask "选择 [1/2，默认 1]："
    read -r choice
    case "${choice:-1}" in
      2) MODE=jar;    install_jdk    ;;
      *) MODE=native; install_graalvm ;;
    esac
  fi
fi

# ── 强制 --native：GraalVM 缺失时安装 ─────────────────────────────────────
if [ "$MODE" = "native" ] && [ -z "$GRAALVM_HOME_FOUND" ] && [ ! -f "$NATIVE_BIN" ]; then
  warn "指定了 --native，但未找到 GraalVM。"
  install_graalvm
fi

# ── 强制 --jar：JDK 缺失时安装 ────────────────────────────────────────────
if [ "$MODE" = "jar" ] && [ -z "$JDK_HOME_FOUND" ] && [ ! -f "$FAT_JAR" ]; then
  warn "指定了 --jar，但未找到 JDK。"
  install_jdk
fi

# ── 需要编译但 sbt 缺失时安装 ─────────────────────────────────────────────
if needs_build && [ -z "$SBT_CMD" ]; then
  warn "需要 sbt 来编译项目，但未找到。"
  install_sbt
  SBT_CMD=$(find_sbt)
  [ -n "$SBT_CMD" ] || die "sbt 安装失败，请手动安装后重试。"
fi

echo ""

# ══════════════════════════════════════════════════════════════════════════
# 阶段 2：构建
# ══════════════════════════════════════════════════════════════════════════
echo -e "${BOLD}▶ 阶段 2/3：构建${RESET}"
echo ""

if [ "$MODE" = "native" ]; then
  if [ -f "$NATIVE_BIN" ]; then
    success "native binary 已存在，跳过编译"
  else
    export GRAALVM_HOME="$GRAALVM_HOME_FOUND"
    export JAVA_HOME="$GRAALVM_HOME"
    info "使用 GraalVM：$GRAALVM_HOME"
    info "开始编译 fat JAR…"
    cd "$SCRIPT_DIR"
    $SBT_CMD "cli/assembly"
    info "开始 native-image 编译（约 40-60 秒）…"
    $SBT_CMD "cli/nativeImage"
    success "native binary 已生成：$NATIVE_BIN"
  fi
else
  if [ -f "$FAT_JAR" ]; then
    success "fat JAR 已存在，跳过编译"
  else
    info "使用 JDK：$JDK_HOME_FOUND"
    info "开始编译 fat JAR…"
    cd "$SCRIPT_DIR"
    $SBT_CMD "cli/assembly"
    success "fat JAR 已生成：$FAT_JAR"
  fi

  if [ -f "$BUNDLED_JAVA" ]; then
    success "精简 JRE 已存在，跳过生成"
  else
    info "生成精简 JRE（jlink）…"
    export JAVA_HOME="$JDK_HOME_FOUND"
    bash "$SCRIPT_DIR/build-runtime.sh"
    success "精简 JRE 已生成：$SCRIPT_DIR/runtime"
  fi
fi

echo ""

# ══════════════════════════════════════════════════════════════════════════
# 阶段 3：配置 .env
# ══════════════════════════════════════════════════════════════════════════
echo -e "${BOLD}▶ 阶段 3/3：配置 API Key${RESET}"
echo ""

ENV_FILE="$SCRIPT_DIR/.env"
MEMORY_DIR="$HOME/.ai-agent"
mkdir -p "$MEMORY_DIR"

if [ -f "$ENV_FILE" ]; then
  success ".env 已存在，跳过配置（如需修改请编辑 ${ENV_FILE}）"
else
  info "创建 .env 配置文件…"
  echo ""

  ask "LLM API Base URL [默认: https://api.deepseek.com/v1]："
  read -r input_url
  LLM_BASE_URL="${input_url:-https://api.deepseek.com/v1}"

  if echo "$LLM_BASE_URL" | grep -q "deepseek"; then
    default_model="deepseek-reasoner"
  elif echo "$LLM_BASE_URL" | grep -q "openai"; then
    default_model="gpt-4o"
  elif echo "$LLM_BASE_URL" | grep -q "anthropic"; then
    default_model="claude-opus-4-6"
  else
    default_model="gpt-4o"
  fi
  ask "LLM 模型 [默认: $default_model]："
  read -r input_model
  LLM_MODEL="${input_model:-$default_model}"

  while true; do
    ask "API Key（必填）："
    read -rs LLM_API_KEY
    echo ""
    [ -n "$LLM_API_KEY" ] && break
    warn "API Key 不能为空，请重新输入。"
  done

  cat > "$ENV_FILE" <<EOF
# ── LLM 后端（OpenAI 兼容接口）─────────────────────────────────
LLM_BASE_URL=${LLM_BASE_URL}
LLM_API_KEY=${LLM_API_KEY}
LLM_MODEL=${LLM_MODEL}
# LLM_MAX_TOKENS=4096

# ── 记忆数据库 ─────────────────────────────────────────────────
AGENT_MEMORY_DB=${MEMORY_DIR}/memory.db

# ── Web 搜索 ───────────────────────────────────────────────────
WEB_SEARCH_ENGINE=duckduckgo
WEB_SEARCH_API_KEY=
WEB_SEARCH_BASE_URL=
EOF

  success ".env 已写入：${ENV_FILE}"
fi

echo ""

# ══════════════════════════════════════════════════════════════════════════
# 安装到 PATH
# ══════════════════════════════════════════════════════════════════════════
mkdir -p "$INSTALL_PREFIX"
TARGET_LINK="$INSTALL_PREFIX/ai-agent"

if [ "$MODE" = "native" ]; then
  ln -sf "$NATIVE_BIN" "$TARGET_LINK"
  success "已创建软链：$TARGET_LINK → $NATIVE_BIN"
else
  cat > "$TARGET_LINK" <<WRAPPER
#!/usr/bin/env bash
exec "${SCRIPT_DIR}/run.sh" "\$@"
WRAPPER
  chmod +x "$TARGET_LINK"
  success "已创建包装脚本：$TARGET_LINK → ${SCRIPT_DIR}/run.sh"
fi

# ── PATH 写入 ───────────────────────────────────────────────────────────────
if ! echo "$PATH" | tr ':' '\n' | grep -qF "$INSTALL_PREFIX"; then
  if [ "$NO_PATH" = true ]; then
    warn "跳过 PATH 配置（--no-path）。请手动添加：export PATH=\"${INSTALL_PREFIX}:\$PATH\""
  else
    SHELL_RC=""
    if [ -n "${ZSH_VERSION:-}" ] || echo "$SHELL" | grep -q zsh; then
      SHELL_RC="$HOME/.zshrc"
    elif [ -n "${BASH_VERSION:-}" ] || echo "$SHELL" | grep -q bash; then
      [ "$OS" = "Darwin" ] && SHELL_RC="$HOME/.bash_profile" || SHELL_RC="$HOME/.bashrc"
    fi

    if [ -n "$SHELL_RC" ]; then
      if ! grep -qF "$INSTALL_PREFIX" "$SHELL_RC" 2>/dev/null; then
        { echo ""; echo "# ai-agent"; echo "export PATH=\"${INSTALL_PREFIX}:\$PATH\""; } \
          >> "$SHELL_RC"
        success "已写入 PATH：$SHELL_RC"
        warn "请运行 'source $SHELL_RC' 或重开终端使 PATH 生效"
      else
        success "$SHELL_RC 中已有 PATH 配置"
      fi
    else
      warn "无法识别 shell，请手动添加：export PATH=\"${INSTALL_PREFIX}:\$PATH\""
    fi
  fi
else
  success "$INSTALL_PREFIX 已在 PATH 中"
fi

# ══════════════════════════════════════════════════════════════════════════
# 完成
# ══════════════════════════════════════════════════════════════════════════
echo ""
echo -e "${BOLD}${GREEN}══════════════════════════════════════${RESET}"
echo -e "${BOLD}${GREEN}  安装完成！${RESET}"
echo -e "${BOLD}${GREEN}══════════════════════════════════════${RESET}"
echo ""
echo "  模式：$([ "$MODE" = "native" ] && echo "native binary（无需 JVM）" || echo "fat JAR + 精简 JRE")"
echo "  配置：${ENV_FILE}"
echo "  命令：${TARGET_LINK}"
echo ""
echo "  使用方式："
echo -e "    ${CYAN}ai-agent \"帮我整理当前目录的文件\"${RESET}"
echo -e "    ${CYAN}ai-agent --no-tui \"写一个 Hello World\"${RESET}"
echo -e "    ${CYAN}ai-agent  # 进入 TUI 交互模式${RESET}"
echo ""
