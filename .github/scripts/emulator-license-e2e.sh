#!/usr/bin/env bash
# Emulator end-to-end licensing scenario (tests A–F) against the real Worker running on the CI host.
# Requires: emulator running, e2e app + test APKs installed, license-server/.e2e/env present.
set -euo pipefail

APP=com.choice.autotap
RUNNER=$APP.test/androidx.test.runner.AndroidJUnitRunner
CLS=com.choice.autotap.LicenseE2ETest
REPORT=e2e-report.txt
: > "$REPORT"
set -a; . license-server/.e2e/env; set +a

admin() { curl -fsS -X "$1" "$LICENSE_URL$2" -H "authorization: Bearer $ADMIN_TOKEN" -H "content-type: application/json" -H "cf-connecting-ip: 192.0.2.10" ${3:+-d "$3"}; }
new_code() { admin POST /api/admin/licenses '{"count":1,"note":"emulator e2e"}' | python3 -c 'import json,sys; l=json.load(sys.stdin)["licenses"][0]; print(l["id"], l["activationCode"])'; }
other_installation() { python3 -c 'import hashlib,os; print(hashlib.sha256(b"choice-auto-tap/installation/v1:" + os.urandom(16).hex().encode()).hexdigest())'; }
api_activate() { # code installation -> "HTTP_STATUS BODY"
  local body; body=$(mktemp)
  local status; status=$(curl -s -o "$body" -w '%{http_code}' -X POST "$LICENSE_URL/api/license/activate" \
    -H 'content-type: application/json' -H "cf-connecting-ip: 192.0.2.$((RANDOM % 200 + 20))" \
    -d "{\"activationCode\":\"$1\",\"installationId\":\"$2\"}")
  echo "$status $(cat "$body")"
}
pass() { echo "PASS  $1" | tee -a "$REPORT"; }
fail() { echo "FAIL  $1" | tee -a "$REPORT"; exit 1; }

step() { # description testMethod [instrument args...]
  local name=$1 method=$2; shift 2
  echo "::group::$name"
  local out; out=$(adb shell am instrument -w -e class "$CLS#$method" "$@" "$RUNNER" 2>&1 | tr -d '\r') || true
  echo "$out" | tail -40
  echo "::endgroup::"
  if grep -q "OK (1 test)" <<<"$out"; then pass "$name"; else fail "$name"; fi
}

read -r ID_A CODE_A < <(new_code)
read -r _ CODE_B < <(new_code)
read -r _ CODE_X < <(new_code)
echo "Generated test licenses: A=${CODE_A:0:8}…  B=${CODE_B:0:8}…  X=${CODE_X:0:8}…"

step "Fresh install shows the activation screen" showsActivationScreenWhenNotActivated

step "A: activate license A on the emulator -> Home" activateSucceeds -e code "$CODE_A"

adb shell am force-stop "$APP"
step "C: restart opens Home without asking, forced re-validation passes" activatedAppOpensWithoutCodeAndRevalidates

read -r status body <<<"$(api_activate "$CODE_A" "$(other_installation)")"
if [ "$status" = "409" ] && grep -q ALREADY_USED <<<"$body"; then
  pass "B: license A on another installation is rejected (HTTP 409 ALREADY_USED)"
else
  fail "B: expected 409 ALREADY_USED, got $status $body"
fi

# F: make the license server unreachable (connection refused at the host firewall).
RULE=(INPUT -p tcp --dport 8787 -j REJECT --reject-with tcp-reset)
sudo iptables -I "${RULE[@]}"
if curl -s -m 5 "$LICENSE_URL/api/health" >/dev/null; then sudo iptables -D "${RULE[@]}"; fail "F: could not block the server"; fi
adb shell am force-stop "$APP"
step "F: server unreachable after activation -> app stays usable (offline grace)" staysUsableWhileOffline
sudo iptables -D "${RULE[@]}"
curl -fsS "$LICENSE_URL/api/health" >/dev/null

admin POST /api/license/revoke "{\"id\":\"$ID_A\"}" >/dev/null
adb shell am force-stop "$APP"
step "D: revoke license A -> heartbeat -> app shows 'License revoked'" revokedAfterHeartbeat

# Person B: a brand-new installation on the same phone (all app data wiped).
adb shell pm clear "$APP"
read -r status _ <<<"$(api_activate "$CODE_X" "$(other_installation)")"
[ "$status" = "200" ] || fail "setup: activating X elsewhere returned $status"
step "B (UI): code already activated by someone else shows 'Activation code already used.'" activationRejected -e code "$CODE_X" -e expected ALREADY_USED
adb shell am force-stop "$APP"
step "Revoked code A is rejected on a new installation" activationRejected -e code "$CODE_A" -e expected INVALID_CODE

adb shell am force-stop "$APP"
step "E: activate license B on the new installation -> Home" activateSucceeds -e code "$CODE_B"

echo
echo "All emulator licensing steps passed:"
cat "$REPORT"
