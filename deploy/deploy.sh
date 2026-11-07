#!/usr/bin/env bash
# ==========================================================================
#  spring-labs → 远端 Docker 部署脚本（幂等）
#
#  流程：上传 jar → 远端 docker build → docker compose up -d
#        → 轮询 /actuator/health 至 UP → 打印 docker ps / 状态摘要
#
#  凭据策略：本脚本【不内置】任何主机、用户、私钥路径或口令，
#            连接参数一律由命令行参数（或同名环境变量）传入，
#            因此脚本本身不含任何凭据内容，可以安全进入版本库。
#
#  远端目录布局（--remote-dir，默认为 /home/ubuntu/app）：
#    <remote-dir>/build/app.jar        构建上下文（只有 app.jar + Dockerfile）
#    <remote-dir>/build/Dockerfile
#    <remote-dir>/docker-compose.yml
#    <remote-dir>/.env
#    <remote-dir>/logs/
#
#  示例：
#    ./deploy.sh \
#      --host <服务器IP> --port 22 --user <用户> --key <私钥路径> \
#      --local-jar ../spring-labs/target/spring-labs.jar \
#      --remote-dir /home/ubuntu/app
# ==========================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# --------------------------------------------------------------------------
# 默认值：连接参数不设默认（必须显式传入），其余为与服务器无关的通用值
# --------------------------------------------------------------------------
HOST="${HOST:-}"
SSH_PORT="${SSH_PORT:-22}"
SSH_USER="${SSH_USER:-}"
SSH_KEY="${SSH_KEY:-}"

LOCAL_JAR="${LOCAL_JAR:-$REPO_ROOT/spring-labs/target/spring-labs.jar}"
REMOTE_DIR="${REMOTE_DIR:-/home/ubuntu/app}"
DOCKERFILE="${DOCKERFILE:-$SCRIPT_DIR/Dockerfile}"
COMPOSE_FILE="${COMPOSE_FILE:-$SCRIPT_DIR/docker-compose.yml}"
ENV_EXAMPLE="${ENV_EXAMPLE:-$SCRIPT_DIR/.env.example}"

IMAGE_NAME="${IMAGE_NAME:-spring-labs}"
IMAGE_TAG="${IMAGE_TAG:-jdwp}"
CONTAINER_NAME="${CONTAINER_NAME:-spring-labs}"
APP_PORT="${APP_PORT:-8080}"
JDWP_PORT="${JDWP_PORT:-5005}"
JDWP_BIND="${JDWP_BIND:-0.0.0.0}"

HEALTH_TIMEOUT="${HEALTH_TIMEOUT:-240}"   # 健康检查最长等待秒数
ROLLBACK_TAG=""                            # 非空时执行回滚而非常规部署
SKIP_BUILD=0
DRY_RUN=0

TMP_DIR=""

# --------------------------------------------------------------------------
# 输出helper
# --------------------------------------------------------------------------
log()  { printf '\033[0;36m[deploy]\033[0m %s\n' "$*"; }
warn() { printf '\033[0;33m[deploy]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[0;31m[deploy] 错误：\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'USAGE'
用法：deploy.sh [选项]

连接参数（必须显式提供，脚本不内置任何凭据）：
  --host <IP|DNS>        目标服务器地址
  --port <端口>          SSH 端口（默认 22）
  --user <用户名>        SSH 登录用户名
  --key <私钥路径>       SSH 私钥文件路径

部署参数：
  --local-jar <路径>     本地 jar（默认 <repo>/spring-labs/target/spring-labs.jar）
  --remote-dir <绝对路径> 远端部署目录（默认 /home/ubuntu/app）
  --image-name <名称>    镜像名（默认 spring-labs）
  --image-tag <标签>     镜像标签（默认 jdwp）
  --container-name <名>  容器名（默认 spring-labs）
  --app-port <端口>      宿主机业务端口（默认 8080）
  --jdwp-port <端口>     宿主机 JDWP 端口（默认 5005）
  --jdwp-bind <地址>     JDWP 在宿主机上的绑定地址：0.0.0.0 或 127.0.0.1（默认 0.0.0.0）
  --health-timeout <秒>  健康检查最长等待秒数（默认 240）
  --dockerfile <路径>    运行时 Dockerfile（默认 deploy/Dockerfile）

行为开关：
  --rollback <标签>      回滚：把 <标签> 重新打成当前标签并重建容器
  --skip-build           跳过构建与上传，仅执行 compose up
  --dry-run              只打印将要执行的动作，不真正连接远端
  -h, --help             显示本帮助

也可用同名环境变量提供默认值（HOST / SSH_PORT / SSH_USER / SSH_KEY / ...）。
USAGE
}

cleanup() { [ -n "$TMP_DIR" ] && [ -d "$TMP_DIR" ] && rm -rf "$TMP_DIR"; }
trap cleanup EXIT

# --------------------------------------------------------------------------
# 参数解析
# --------------------------------------------------------------------------
need_val() { [ "$1" -ge 2 ] || die "参数 $2 缺少取值"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --host)           need_val $# "$1"; HOST="$2"; shift 2 ;;
    --port)           need_val $# "$1"; SSH_PORT="$2"; shift 2 ;;
    --user)           need_val $# "$1"; SSH_USER="$2"; shift 2 ;;
    --key)            need_val $# "$1"; SSH_KEY="$2"; shift 2 ;;
    --local-jar)      need_val $# "$1"; LOCAL_JAR="$2"; shift 2 ;;
    --remote-dir)     need_val $# "$1"; REMOTE_DIR="$2"; shift 2 ;;
    --image-name)     need_val $# "$1"; IMAGE_NAME="$2"; shift 2 ;;
    --image-tag)      need_val $# "$1"; IMAGE_TAG="$2"; shift 2 ;;
    --container-name) need_val $# "$1"; CONTAINER_NAME="$2"; shift 2 ;;
    --app-port)       need_val $# "$1"; APP_PORT="$2"; shift 2 ;;
    --jdwp-port)      need_val $# "$1"; JDWP_PORT="$2"; shift 2 ;;
    --jdwp-bind)      need_val $# "$1"; JDWP_BIND="$2"; shift 2 ;;
    --health-timeout) need_val $# "$1"; HEALTH_TIMEOUT="$2"; shift 2 ;;
    --dockerfile)     need_val $# "$1"; DOCKERFILE="$2"; shift 2 ;;
    --rollback)       need_val $# "$1"; ROLLBACK_TAG="$2"; shift 2 ;;
    --skip-build)     SKIP_BUILD=1; shift ;;
    --dry-run)        DRY_RUN=1; shift ;;
    -h|--help)        usage; exit 0 ;;
    *)                die "未知参数：$1（用 --help 查看用法）" ;;
  esac
done

# --------------------------------------------------------------------------
# 校验
# --------------------------------------------------------------------------
[ -n "$HOST" ]     || { usage; die "缺少 --host"; }
[ -n "$SSH_USER" ] || { usage; die "缺少 --user"; }
[ -n "$SSH_KEY" ]  || { usage; die "缺少 --key"; }

command -v ssh >/dev/null 2>&1 || die "本机没有 ssh"
command -v scp >/dev/null 2>&1 || die "本机没有 scp"

[ -f "$SSH_KEY" ]      || die "私钥文件不存在：$SSH_KEY"
[ -f "$LOCAL_JAR" ]    || die "本地 jar 不存在：$LOCAL_JAR（请先在本地构建产物，不要在远端编译）"
[ -f "$DOCKERFILE" ]   || die "Dockerfile 不存在：$DOCKERFILE"
[ -f "$COMPOSE_FILE" ] || die "docker-compose.yml 不存在：$COMPOSE_FILE"
[ -f "$ENV_EXAMPLE" ]  || die ".env.example 不存在：$ENV_EXAMPLE"

case "$REMOTE_DIR" in
  /*) ;;
  *) die "--remote-dir 必须是绝对路径，当前为：$REMOTE_DIR" ;;
esac
if ! printf '%s' "$REMOTE_DIR" | grep -Eq '^/[A-Za-z0-9._/-]*$'; then
  die "--remote-dir 含非法字符，只允许字母数字与 . _ / - ：$REMOTE_DIR"
fi
if printf '%s' "$REMOTE_DIR" | grep -q '\.\.'; then
  die "--remote-dir 不允许包含 .. ：$REMOTE_DIR"
fi

for p in "$APP_PORT" "$JDWP_PORT"; do
  printf '%s' "$p" | grep -Eq '^[0-9]{1,5}$' || die "端口必须是数字：$p"
  [ "$p" -ge 1 ] && [ "$p" -le 65535 ] || die "端口越界：$p"
done
case "$JDWP_BIND" in
  0.0.0.0|127.0.0.1|::) ;;
  *) die "--jdwp-bind 只允许 0.0.0.0 / 127.0.0.1 / :: ，当前为：$JDWP_BIND" ;;
esac
printf '%s' "$HEALTH_TIMEOUT" | grep -Eq '^[0-9]+$' || die "--health-timeout 必须是数字"

# --------------------------------------------------------------------------
# SSH / SCP 封装
# --------------------------------------------------------------------------
SSH_OPTS=(
  -i "$SSH_KEY"
  -p "$SSH_PORT"
  -o StrictHostKeyChecking=accept-new
  -o ConnectTimeout=15
  -o BatchMode=yes
  -o ServerAliveInterval=30
)

ssh_run() {
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '[dry-run] ssh %s\n' "$*"
    return 0
  fi
  ssh "${SSH_OPTS[@]}" "${SSH_USER}@${HOST}" "$@"
}

scp_up() {
  local src="$1" dst="$2"
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '[dry-run] scp %s -> %s\n' "$src" "$dst"
    return 0
  fi
  scp -i "$SSH_KEY" -P "$SSH_PORT" \
    -o StrictHostKeyChecking=accept-new \
    -o ConnectTimeout=15 \
    -o BatchMode=yes \
    "$src" "${SSH_USER}@${HOST}:${dst}"
}

# 把本地脚本通过 stdin 送到远端执行（远端只有 bash + coreutils）
ssh_stdin() {
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '[dry-run] ssh bash -s < 本地脚本\n'
    return 0
  fi
  ssh "${SSH_OPTS[@]}" "${SSH_USER}@${HOST}" 'bash -s'
}

COMPOSE_PROJECT="spring-labs"
STAMP="$(date +%Y%m%d-%H%M%S)"

# ==========================================================================
# 0. 预检
# ==========================================================================
if [ "$ROLLBACK_TAG" = "" ] && [ "$SKIP_BUILD" -eq 0 ]; then
  log "本地产物：$LOCAL_JAR"
  log "远端目录：${REMOTE_DIR}（构建上下文 ${REMOTE_DIR}/build）"
  log "镜像标签：${IMAGE_NAME}:${IMAGE_TAG}    容器名：${CONTAINER_NAME}"
  log "端口映射：业务 ${APP_PORT}:8080    JDWP ${JDWP_BIND}:${JDWP_PORT}:5005（容器内 5005）"
fi

# ==========================================================================
# 1. 远端目录准备
# ==========================================================================
if [ "$SKIP_BUILD" -eq 0 ] && [ -z "$ROLLBACK_TAG" ]; then
  log "步骤 1/5：准备远端目录 ${REMOTE_DIR}"
  ssh_run "set -e
    mkdir -p '${REMOTE_DIR}/build' '${REMOTE_DIR}/logs'
    # 容器以 uid 10001(spring) 运行，日志/堆转储目录需可写，否则 OOM 转储会失败
    sudo chown 10001:10001 '${REMOTE_DIR}/logs' 2>/dev/null || echo '[warn] logs 目录属主调整失败，堆转储可能不可写'
    echo '远端目录就绪：'
    ls -ld '${REMOTE_DIR}' '${REMOTE_DIR}/build' '${REMOTE_DIR}/logs'"

  # ---- 渲染远端 .env（以 .env.example 为唯一来源，命令行参数覆盖同名键）----
  TMP_DIR="$(mktemp -d)"
  sed -e "s|^APP_PORT=.*|APP_PORT=${APP_PORT}|" \
      -e "s|^JDWP_PORT=.*|JDWP_PORT=${JDWP_PORT}|" \
      -e "s|^JDWP_BIND=.*|JDWP_BIND=${JDWP_BIND}|" \
      -e "s|^CONTAINER_NAME=.*|CONTAINER_NAME=${CONTAINER_NAME}|" \
      -e "s|^IMAGE_NAME=.*|IMAGE_NAME=${IMAGE_NAME}|" \
      -e "s|^IMAGE_TAG=.*|IMAGE_TAG=${IMAGE_TAG}|" \
      "$ENV_EXAMPLE" > "$TMP_DIR/.env"

  log "步骤 2/5：上传 jar 与编排文件"
  scp_up "$LOCAL_JAR"   "${REMOTE_DIR}/build/app.jar"
  scp_up "$DOCKERFILE"  "${REMOTE_DIR}/build/Dockerfile"
  scp_up "$COMPOSE_FILE" "${REMOTE_DIR}/docker-compose.yml"
  scp_up "$TMP_DIR/.env" "${REMOTE_DIR}/.env"

  # ---- 构建上下文自检：只允许 app.jar + Dockerfile ----
  ssh_run "set -e
    ctx='${REMOTE_DIR}/build'
    extra=\$(ls -A \"\$ctx\" | grep -v -x -e 'app.jar' -e 'Dockerfile' || true)
    if [ -n \"\$extra\" ]; then
      echo '构建上下文存在多余文件，已中止：'; echo \"\$extra\"; exit 1
    fi
    echo '构建上下文内容：'; ls -l \"\$ctx\""

  # ==========================================================================
  # 2. 远端构建镜像
  # ==========================================================================
  log "步骤 3/5：远端 docker build -t ${IMAGE_NAME}:${IMAGE_TAG}"
  ssh_run "set -e
    cd '${REMOTE_DIR}/build'
    docker build -t '${IMAGE_NAME}:${IMAGE_TAG}' .
    # 打一个带时间戳的标签作为回滚点
    docker tag '${IMAGE_NAME}:${IMAGE_TAG}' '${IMAGE_NAME}:${IMAGE_TAG}-${STAMP}'
    echo '构建完成，镜像列表：'
    docker images '${IMAGE_NAME}' --format '  {{.Repository}}:{{.Tag}}  {{.Size}}'"

  # ==========================================================================
  # 3. 启动容器
  # ==========================================================================
  log "步骤 4/5：docker compose up -d"
  ssh_run "set -e
    cd '${REMOTE_DIR}'
    docker compose -p '${COMPOSE_PROJECT}' \
      -f '${REMOTE_DIR}/docker-compose.yml' \
      --env-file '${REMOTE_DIR}/.env' \
      up -d --remove-orphans
    echo 'compose 已执行'"
else
  log "步骤 1-3/5：已跳过（--skip-build / --rollback）"
fi

if [ -n "$ROLLBACK_TAG" ]; then
  log "回滚：把 ${IMAGE_NAME}:${ROLLBACK_TAG} 重新打成 ${IMAGE_NAME}:${IMAGE_TAG}"
  ssh_run "set -e
    docker image inspect '${IMAGE_NAME}:${ROLLBACK_TAG}' >/dev/null 2>&1 || {
      echo '回滚标签不存在：${IMAGE_NAME}:${ROLLBACK_TAG}'; exit 1; }
    docker tag '${IMAGE_NAME}:${ROLLBACK_TAG}' '${IMAGE_NAME}:${IMAGE_TAG}'
    cd '${REMOTE_DIR}'
    docker compose -p '${COMPOSE_PROJECT}' \
      -f '${REMOTE_DIR}/docker-compose.yml' \
      --env-file '${REMOTE_DIR}/.env' \
      up -d --force-recreate --remove-orphans"
fi

# ==========================================================================
# 4. 轮询健康检查（远端执行，避免每次轮询都走一次 SSH 往返）
# ==========================================================================
if [ "$DRY_RUN" -eq 1 ]; then
  log "[dry-run] 跳过健康检查与状态打印"
  exit 0
fi

TMP_DIR="${TMP_DIR:-$(mktemp -d)}"
POLL_SCRIPT="$TMP_DIR/poll.sh"
cat > "$POLL_SCRIPT" <<POLL_EOF
APP_PORT='${APP_PORT}'
CONTAINER='${CONTAINER_NAME}'
TIMEOUT='${HEALTH_TIMEOUT}'
URL="http://127.0.0.1:\${APP_PORT}/actuator/health"
echo "[poll] 等待健康检查：\${URL}（最长 \${TIMEOUT}s）"
start=\$(date +%s)
while :; do
  now=\$(date +%s)
  elapsed=\$((now - start))
  if [ "\$elapsed" -ge "\$TIMEOUT" ]; then
    echo "[poll] 健康检查超时（\${TIMEOUT}s）"
    echo "----- 容器状态 -----"
    docker inspect -f '{{.State.Status}} / health={{if .State.Health}}{{.State.Health.Status}}{{else}}n/a{{end}} / restarts={{.RestartCount}}' "\$CONTAINER" 2>&1 || true
    echo "----- 最后 80 行日志 -----"
    docker logs --tail 80 "\$CONTAINER" 2>&1 || true
    exit 1
  fi
  body=\$(curl -fsS --max-time 5 "\$URL" 2>/dev/null || true)
  case "\$body" in
    *'"status":"UP"'*)
      echo "[poll] 健康检查通过：\$body"
      exit 0
      ;;
  esac
  st=\$(docker inspect -f '{{.State.Status}}' "\$CONTAINER" 2>/dev/null || echo unknown)
  echo "[poll] \${elapsed}s 尚未就绪（容器状态：\${st}）"
  sleep 3
done
POLL_EOF

log "步骤 5/5：轮询 /actuator/health"
if ! ssh_stdin < "$POLL_SCRIPT"; then
  warn "健康检查未通过，部署视为失败。"
  warn "排障：ssh 到服务器执行 'docker logs --tail 100 ${CONTAINER_NAME}'"
  exit 1
fi

# ==========================================================================
# 5. 状态摘要
# ==========================================================================
echo
log "===== 部署结果 ====="
ssh_run "echo '--- docker ps ---'
docker ps --filter 'name=${CONTAINER_NAME}' --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
echo
echo '--- 容器详情 ---'
docker inspect -f '容器: {{.Name}}
镜像: {{.Config.Image}}
状态: {{.State.Status}}
健康: {{if .State.Health}}{{.State.Health.Status}}{{else}}n/a{{end}}
重启次数: {{.RestartCount}}
启动时间: {{.State.StartedAt}}' '${CONTAINER_NAME}'
echo '--- 生效的关键环境变量 ---'
docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' '${CONTAINER_NAME}' | grep -E '^(JAVA_TOOL_OPTIONS|SERVER_PORT|JAVA_OPTS)=' || true
echo
echo '--- 宿主机端口监听 ---'
ss -tln | grep -E ':(8080|5005)' || echo '（未发现 8080 / 5005 监听）'
echo
echo '--- 镜像列表（含回滚点）---'
docker images '${IMAGE_NAME}' --format '  {{.Repository}}:{{.Tag}}  {{.CreatedSince}}  {{.Size}}'"

# 清理过期的回滚标签，保留最近 3 个
ssh_run "docker images --format '{{.Repository}}:{{.Tag}}' --filter 'reference=${IMAGE_NAME}:${IMAGE_TAG}-*' 2>/dev/null \
  | sort -r | tail -n +4 | xargs -r -n1 docker rmi -f >/dev/null 2>&1 || true
  echo '已清理过期回滚标签（保留最近 3 个）'" || true

echo
log "部署完成 ✅"
log "本地健康检查地址：http://127.0.0.1:${APP_PORT}/actuator/health（在服务器上执行 curl）"
case "$JDWP_BIND" in
  0.0.0.0)
    log "JDWP 公网直连：IDEA 远程调试 host=<服务器IP> port=${JDWP_PORT}"
    warn "JDWP 未加密且等价远程代码执行入口：请在腾讯云安全组把 ${JDWP_PORT} 入方向来源限制为你的固定 IP。"
    ;;
  *)
    log "JDWP 仅本机可达：IDEA 远程调试 host=localhost port=${JDWP_PORT}（需先建立 SSH 隧道）"
    ;;
esac
