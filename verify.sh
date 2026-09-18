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

# ── firmware units, on the host, under sanitisers ───────────────────────────
rule "Firmware host tests"
PAYLOAD_LABEL="payload: bounded writes, buffer sweep, ASan + UBSan clean"
INFLIGHT_LABEL="unconfirmed publishes: horizon, order, overwrite, millis() wrap"
if command -v g++ >/dev/null 2>&1; then
  dir=$(mktemp -d)
  flags="-std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined -fno-sanitize-recover=all -I firmware/psychron_node"
  # shellcheck disable=SC2086
  if g++ $flags firmware/test/test_payload.cpp firmware/psychron_node/payload.cpp -o "$dir/payload" 2>/dev/null \
     && timeout 60 "$dir/payload" > /dev/null 2>&1; then ok "$PAYLOAD_LABEL"; else no "payload tests"; fi
  # shellcheck disable=SC2086
  if g++ $flags firmware/test/test_inflight.cpp -o "$dir/inflight" 2>/dev/null \
     && timeout 60 "$dir/inflight" > /dev/null 2>&1; then ok "$INFLIGHT_LABEL"; else no "inflight tests"; fi
  rm -rf "$dir"
elif docker info >/dev/null 2>&1; then
  # No compiler on the host is no reason to skip the suites that check memory
  # safety. The same flags, in a throwaway Linux container: the sanitizers need
  # glibc, which rules out the smaller Alpine images. One container for both, and
  # each result reported on its own line so a failure names its suite.
  #
  # trixie, not bookworm: GCC 12's AddressSanitizer cannot map its shadow memory
  # under the 32 bits of mmap randomisation that recent kernels (WSL2's 6.18
  # among them) use, and instead of failing it prints DEADLYSIGNAL forever — gigabytes
  # of it, measured. GCC 14 copes. The timeouts are there so no future toolchain
  # regression can turn a test run into a disk-filling loop again.
  results=$(MSYS_NO_PATHCONV=1 docker run --rm --cap-add=SYS_PTRACE \
       -v "$(pwd -W 2>/dev/null || pwd)/firmware:/fw:ro" debian:trixie-slim sh -c '
       apt-get update -qq >/dev/null && apt-get install -y -qq --no-install-recommends g++ >/dev/null 2>&1 || exit 0
       F="-std=c++17 -Wall -Wextra -Werror -fsanitize=address,undefined -fno-sanitize-recover=all -I/fw/psychron_node"
       g++ $F /fw/test/test_payload.cpp /fw/psychron_node/payload.cpp -o /tmp/p >/dev/null 2>&1 && timeout 60 /tmp/p >/dev/null 2>&1 && echo payload=ok
       g++ $F /fw/test/test_inflight.cpp -o /tmp/i >/dev/null 2>&1 && timeout 60 /tmp/i >/dev/null 2>&1 && echo inflight=ok' 2>/dev/null | head -c 1000)
  case "$results" in *payload=ok*)  ok "$PAYLOAD_LABEL (in Docker)" ;;  *) no "payload tests (in Docker)" ;; esac
  case "$results" in *inflight=ok*) ok "$INFLIGHT_LABEL (in Docker)" ;; *) no "inflight tests (in Docker)" ;; esac
else
  meh "no g++ and no Docker — the host tests need a C++17 compiler"
fi

# ── the panel ───────────────────────────────────────────────────────────────
rule "Panel"
if command -v node >/dev/null 2>&1 && [ -d frontend/node_modules ]; then
  # Node's own test runner on the TypeScript as written: no test framework in the
  # dependency tree, and nothing compiled that could differ from what is shipped.
  out=$(cd frontend && npm test --silent 2>&1)
  code=$?
  printf '%s\n' "$out" | grep -E '^ℹ (tests|fail)' | sed 's/^/        /'
  [ $code -eq 0 ] && ok "coverage, track, tendency and gap arithmetic" || no "panel tests"
  if (cd frontend && npx tsc -b >/dev/null 2>&1); then ok "types check across the panel and its tests"; else no "panel types"; fi
else
  meh "no node or no frontend/node_modules — run npm install in frontend/"
fi

# ── the phone node ──────────────────────────────────────────────────────────
rule "Phone node"
if [ -n "${JAVA_HOME:-}" ] && { [ -n "${ANDROID_HOME:-}" ] || [ -f android/local.properties ]; }; then
  gradlew=./gradlew
  [ -f android/gradlew.bat ] && [ -n "${WINDIR:-}" ] && gradlew=./gradlew.bat
  out=$(cd android && $gradlew testDebugUnitTest --console=plain -q 2>&1)
  if [ $? -eq 0 ]; then
    n=$(grep -ho 'tests="[0-9]*"' android/app/build/test-results/testDebugUnitTest/*.xml | tr -dc '0-9\n' | awk '{ s += $1 } END { print s }')
    ok "${n:+$n tests: }contract, readouts, vibration trigger, A-weighting, SNTP, signing request"
  # A toolchain that will not start is not a test that failed, and printing it
  # as one teaches whoever runs this to ignore a red line. The Android plugin
  # rejects a JDK newer than it knows by printing the version and nothing else.
  elif printf "%s" "$out" | grep -qiE "unsupported class file|no longer supports|^[0-9]+\.[0-9.]+$"; then
    meh "the JDK in JAVA_HOME is newer than the Android plugin accepts — set org.gradle.java.home to a JDK 17-21"
  else
    no "phone node unit tests"
  fi
else
  meh "no JAVA_HOME and Android SDK — the app's tests need both"
fi

# ── the claim that cannot be checked by a unit test ─────────────────────────
rule "Transport"
if docker compose -f infra/docker-compose.yml ps --status running 2>/dev/null | grep -q broker; then
  if (cd infra && MSYS_NO_PATHCONV=1 ./check-mtls.sh > /tmp/psychron-mtls.$$ 2>&1); then
    grep -E '^  (OK|FAIL)' /tmp/psychron-mtls.$$ | sed 's/^/      /'
    # Counted rather than stated: the revocation check only runs once there is a
    # revoked certificate to present.
    n=$(grep -c '^  OK' /tmp/psychron-mtls.$$)
    revoked=$(grep -q 'revoked' /tmp/psychron-mtls.$$ && echo ', revocation' || true)
    ok "$n assertions: ACLs, forged CN, unknown CA${revoked}, phone fencing, alert direction, 1883 closed"
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
