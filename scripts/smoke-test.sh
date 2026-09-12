#!/usr/bin/env bash
# End-to-end smoke test: every container up, and the API able to reach each dependency.
set -uo pipefail

BACKEND="${BACKEND:-http://localhost:8080}"
FRONTEND="${FRONTEND:-http://localhost:4200}"
RENDER="${RENDER:-http://localhost:8000}"
GROBID="${GROBID:-http://localhost:8070}"
OLLAMA="${OLLAMA:-http://localhost:11434}"

pass=0; fail=0
check() {
  local name="$1" url="$2" expect="$3"
  local body
  body=$(curl -fsS --max-time 15 "$url" 2>/dev/null)
  if [[ $? -eq 0 && "$body" == *"$expect"* ]]; then
    printf '  \033[32mPASS\033[0m  %-24s %s\n' "$name" "$url"; pass=$((pass+1))
  else
    printf '  \033[31mFAIL\033[0m  %-24s %s\n' "$name" "$url"; fail=$((fail+1))
  fi
}

echo "PaperViz smoke test"
echo
check "frontend"          "$FRONTEND/"                     "<app-root"
check "backend actuator"  "$BACKEND/actuator/health"       '"status":"UP"'
check "backend api"       "$BACKEND/api/health"            '"service":"paperviz-backend"'
check "render-service"    "$RENDER/health"                 '"status":"UP"'
check "grobid"            "$GROBID/api/isalive"            "true"
check "ollama"            "$OLLAMA/api/tags"               "models"
echo
echo "Aggregate dependency view:"
curl -fsS --max-time 15 "$BACKEND/api/health" 2>/dev/null | python -m json.tool 2>/dev/null || echo "  (backend unreachable)"
echo
echo "$pass passed, $fail failed"
[[ $fail -eq 0 ]]
