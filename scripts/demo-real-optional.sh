#!/usr/bin/env bash
# demo-real-optional.sh — OpenScout 真实验证（需要 GitHub Token + LLM Key + Docker 服务）
# 警告：本脚本会发起真实 GitHub API 调用和模型请求，可能产生费用或触发限流。
# 使用前请确保已配置必要的环境变量，并已启动 MySQL/Redis。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

red()   { echo -e "\033[1;31m$*\033[0m"; }
green() { echo -e "\033[1;32m$*\033[0m"; }
yellow(){ echo -e "\033[1;33m$*\033[0m"; }
bold()  { echo -e "\033[1m$*\033[0m"; }

bold "=== OpenScout Real Demo (optional) ==="
echo ""

MISSING=""

check_env() {
  local var="$1"
  local hint="$2"
  if [ -z "${!var:-}" ]; then
    red "  MISSING: $var — $hint"
    MISSING="$MISSING $var"
    return 1
  else
    green "  OK: $var"
    return 0
  fi
}

bold ">>> Checking prerequisites"
check_env GITHUB_TOKEN     "GitHub Personal Access Token (repo scope)"
check_env DEEPSEEK_API_KEY  "DeepSeek API Key"

if [ -n "$MISSING" ]; then
  echo ""
  yellow "缺少必要环境变量，请配置后重试："
  for v in $MISSING; do
    echo "  export $v=<your-value>"
  done
  echo ""
  yellow "提示：本脚本为 optional 真实验证，默认 CI 和 verify-local.sh 不依赖这些变量。"
  exit 0
fi

# ---- Check Docker services ----
bold ">>> Checking Docker services"
if docker compose -f "$ROOT_DIR/deploy/docker-compose.yml" ps --services --status running 2>/dev/null | grep -q .; then
  green "  MySQL/Redis services running."
else
  yellow "  Docker services not running — starting..."
  docker compose -f "$ROOT_DIR/deploy/docker-compose.yml" up -d
fi

# ---- Go Collector (real mode) ----
bold ">>> Starting Go Collector (real mode)"
export OPSCOUT_COLLECTOR_MODE=real
cd "$ROOT_DIR/openscout-repo-collector"
go run ./cmd/server &
COLLECTOR_PID=$!
echo "  Collector PID: $COLLECTOR_PID"

cleanup() {
  if [ -n "${COLLECTOR_PID:-}" ]; then
    kill "$COLLECTOR_PID" 2>/dev/null || true
    wait "$COLLECTOR_PID" 2>/dev/null || true
  fi
  if [ -n "${SERVER_PID:-}" ]; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

sleep 2

# ---- Java Agent Server (real mode) ----
bold ">>> Starting Agent Server (LLM enabled, persistence enabled)"
export OPSCOUT_MOCK_AGENT=false
export OPSCOUT_LLM_ENABLED=true
export OPSCOUT_PERSISTENCE_ENABLED=true
export OPSCOUT_COLLECTOR_MODE=real

cd "$ROOT_DIR/openscout-agent-server"
mvn spring-boot:run -q &
SERVER_PID=$!

echo -n "Waiting for server..."
for i in $(seq 1 30); do
  if curl -s http://localhost:8080/actuator/health &>/dev/null; then
    echo ""
    green "Server ready."
    break
  fi
  if ! kill -0 "$SERVER_PID" 2>/dev/null; then
    red "Server failed to start."
    exit 1
  fi
  echo -n "."
  sleep 2
done

# ---- Demo: Real Ask ----
echo ""
bold ">>> POST /api/agent/ask (real mode)"
curl -s -X POST http://localhost:8080/api/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"我想学习 Go 微服务项目，推荐几个适合进阶的开源项目"}' | python3 -m json.tool 2>/dev/null || true

echo ""
bold "=== Demo finished ==="
green "提示：本脚本仅用于本地真实验证；不要在公网环境暴露以上端口。"
