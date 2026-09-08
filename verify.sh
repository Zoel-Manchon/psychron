#!/usr/bin/env bash
# Everything this project can assert about itself, in one command.
#
# The three suites answer different questions and none of them substitutes for
# another: the domain tests say the arithmetic is right, the payload tests say
# the serialiser cannot walk off the end of its buffer, and the mTLS checks say
# the transport really refuses what it claims to refuse — which is the only one
# of the three that can be wrong while every unit test still passes.
set -uo pipefail
cd "$(dirname "$0")"

pass=0
fail=0
skip=0

rule() { printf '\n\033[1m%s\033[0m\n' "$1"; }
ok()   { printf '  \033[32mPASS\033[0m  %s\n' "$1"; pass=$((pass + 1)); }
no()   { printf '  \033[31mFAIL\033[0m  %s\n' "$1"; fail=$((fail + 1)); }
meh()  { printf '  \033[33mSKIP\033[0m  %s\n' "$1"; skip=$((skip + 1)); }

# ── domain and adapters ─────────────────────────────────────────────────────
rule "Python suite"
PY=backend/.venv/Scripts/python.exe
[ -x "$PY" ] || PY=backend/.venv/bin/python
if [ -x "$PY" ]; then
  # Captured rather than piped to a formatter: a pipeline reports the exit code
  # of its last command, so `pytest | tail` passes even when pytest fails.
  out=$("$PY" -m pytest backend -q 2>&1)
  code=$?
  printf '%s\n' "$out" | tail -n 2 | sed 's/^/        /'
  [ $code -eq 0 ] && ok "domain, psychrometrics, identity, telemetry" \
                  || no "python tests"
else
  meh "no virtualenv at backend/.venv — see the README"
fi

# ── the serialiser, on the host, under sanitisers ───────────────────────────
rule "Firmware payload builder"
if command -v g++ >/dev/null 2>&1; then
  bin=$(mktemp -d)/test_payload
  if g++ -std=c++17 -Wall -Wextra -fsanitize=address,undefined \
         -I firmware/psychron_node firmware/test/test_payload.cpp \
         firmware/psychron_node/payload.cpp -o "$bin" 2>/dev/null; then
    if "$bin" > /dev/null 2>&1; then
      ok "bounded writes, buffer sweep 1..122, ASan + UBSan clean"
    else
      no "payload tests"
    fi
  else
    no "payload tests did not compile"
  fi
else
  meh "no g++ on PATH — the host tests need a C++17 compiler"
fi

# ── the claim that cannot be checked by a unit test ─────────────────────────
rule "Transport"
if docker compose -f infra/docker-compose.yml ps --status running 2>/dev/null | grep -q broker; then
  if (cd infra && MSYS_NO_PATHCONV=1 ./check-mtls.sh > /tmp/psychron-mtls.$$ 2>&1); then
    grep -E '^  (OK|FAIL)' /tmp/psychron-mtls.$$ | sed 's/^/      /'
    ok "7 assertions: ACLs, forged CN, unknown CA, 1883 closed"
  else
    sed 's/^/      /' /tmp/psychron-mtls.$$
    no "mTLS assertions"
  fi
  rm -f /tmp/psychron-mtls.$$
else
  meh "broker not running — start it with: cd infra && docker compose up -d"
fi

printf '\n\033[1m%d passed, %d failed, %d skipped\033[0m\n' "$pass" "$fail" "$skip"
exit $((fail > 0))
