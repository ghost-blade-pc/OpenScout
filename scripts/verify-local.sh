#!/usr/bin/env bash
# verify-local.sh — OpenScout 本地验证脚本（mock-first，无密钥，无外网）
# 用途：一键验证 Java/Go/Compose/Evaluation 全部通过。
# 不依赖 MySQL/Redis 服务、外部网络、GitHub Token 或模型 Key。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PASS=0
FAIL=0
WARN=0

red()   { echo -e "\033[1;31m$*\033[0m"; }
green() { echo -e "\033[1;32m$*\033[0m"; }
yellow(){ echo -e "\033[1;33m$*\033[0m"; }
bold()  { echo -e "\033[1m$*\033[0m"; }

step_pass() { green "  PASS  $1"; PASS=$((PASS+1)); }
step_fail() { red   "  FAIL  $1"; FAIL=$((FAIL+1)); }
step_warn() { yellow "  WARN  $1 (skipped)"; WARN=$((WARN+1)); }

bold "=== OpenScout verify-local ==="
echo ""

# ---- Java ----
bold ">>> Java: mvn test (full suite)"
if command -v mvn &>/dev/null; then
  cd "$ROOT_DIR/openscout-agent-server"
  if mvn test -q 2>&1; then
    step_pass "Java mvn test"
  else
    step_fail "Java mvn test"
  fi
else
  step_warn "mvn not found — install Maven + JDK 17"
fi
echo ""

# ---- Java Evaluation ----
bold ">>> Java: Agent Evaluation (default mock)"
if command -v mvn &>/dev/null; then
  cd "$ROOT_DIR/openscout-agent-server"
  if mvn test -q -Dtest=AgentEvaluationCommandTest 2>&1; then
    step_pass "Agent Evaluation"
  else
    step_fail "Agent Evaluation"
  fi
else
  step_warn "mvn not found"
fi
echo ""

# ---- Go ----
bold ">>> Go: go test ./..."
# 优先使用项目本地 Go 工具链
if [ -f "$ROOT_DIR/.tools/go/bin/go" ]; then
  GO_BIN="$ROOT_DIR/.tools/go/bin/go"
elif command -v go &>/dev/null; then
  GO_BIN="go"
else
  GO_BIN=""
fi

if [ -n "$GO_BIN" ]; then
  cd "$ROOT_DIR/openscout-repo-collector"
  if "$GO_BIN" test ./... 2>&1; then
    step_pass "Go go test"
  else
    step_fail "Go go test"
  fi
else
  step_warn "go not found — install Go 1.22+ or run scripts/use-local-tools.sh"
fi
echo ""

# ---- Docker Compose ----
bold ">>> Docker Compose: config check"
if command -v docker &>/dev/null; then
  cd "$ROOT_DIR"
  if docker compose -f deploy/docker-compose.yml config --quiet 2>&1; then
    step_pass "Docker Compose config"
  else
    step_fail "Docker Compose config"
  fi
else
  step_warn "docker not found"
fi
echo ""

# ---- Summary ----
bold "=== Result: $PASS passed, $FAIL failed, $WARN skipped ==="
if [ "$FAIL" -gt 0 ]; then
  red "Verification FAILED — see messages above."
  exit 1
else
  green "Verification PASSED."
fi
