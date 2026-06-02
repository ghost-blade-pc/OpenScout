#!/usr/bin/env bash
# demo-mock.sh — OpenScout Mock 模式演示（无需数据库、密钥或外网）
# 启动 Java Agent Server（mock 模式）并演示核心 API。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

red()   { echo -e "\033[1;31m$*\033[0m"; }
green() { echo -e "\033[1;32m$*\033[0m"; }
yellow(){ echo -e "\033[1;33m$*\033[0m"; }
bold()  { echo -e "\033[1m$*\033[0m"; }

bold "=== OpenScout Mock Demo ==="
echo ""

# Kill background server on exit
cleanup() {
  if [ -n "${SERVER_PID:-}" ]; then
    kill "$SERVER_PID" 2>/dev/null || true
    wait "$SERVER_PID" 2>/dev/null || true
  fi
}
trap cleanup EXIT

# ---- Start Server ----
bold ">>> Starting Agent Server (mock mode, port 8080)"
export OPSCOUT_MOCK_AGENT=true
export OPSCOUT_COLLECTOR_MODE=mock
export OPSCOUT_LLM_ENABLED=false
export OPSCOUT_PERSISTENCE_ENABLED=false

cd "$ROOT_DIR/openscout-agent-server"
mvn spring-boot:run -q &
SERVER_PID=$!

# Wait for server to be ready
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

# ---- Demo: Agent Ask ----
echo ""
bold ">>> POST /api/agent/ask"
curl -s -X POST http://localhost:8080/api/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"我想学习 React 前端项目，帮我推荐几个适合初学者上手的开源项目"}' | python3 -m json.tool 2>/dev/null || true

echo ""
bold "=== Demo finished ==="
green "提示：demo-mock.sh 仅为快速演示；完整功能请参考 README。"
