#!/usr/bin/env bash
# Fails when the tracked files look like they contain credentials, signing material or server addresses.
# Runs in CI and as a local pre-commit hook. It is deliberately generic: personal strings (your own domain, e-mail,
# paths) must NOT be listed here. Put them, one per line, into a file OUTSIDE the repository and point
# GATE_PRIVATE_PATTERNS at it (default: ~/.gate-private-patterns); they are checked as fixed strings when the file exists.
set -uo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || echo "$(dirname "$0")/..")"

fail=0
report() { echo "FOUND: $1"; fail=1; }

if git rev-parse --git-dir >/dev/null 2>&1; then
  mapfile -t files < <(git ls-files -co --exclude-standard)
else
  mapfile -t files < <(find . -type f -not -path './.git/*' | sed 's#^\./##')
fi

# 1. files that must never be tracked
for f in "${files[@]}"; do
  case "$f" in
    *.jks|*.keystore|*.p12|*.pfx|*.pem|*.key|*.apk|*.aab|*.env|.env|.env.*|local.properties|keystore.properties|signing.properties)
      report "forbidden file type tracked: $f" ;;
  esac
done

# 2. secret-looking content (text files only, this script excluded)
scan() { # $1 = label, $2 = ERE
  local hits
  hits=$(grep -InE -e "$2" -- "${files[@]}" 2>/dev/null | grep -v '^scripts/check-secrets.sh:' || true)
  [ -n "$hits" ] && { report "$1"; echo "$hits" | sed -E 's/^([^:]+:[0-9]+):.*/  \1: <line hidden>/'; }
}
scan "private key block"             '-----BEGIN [A-Z ]*PRIVATE KEY-----'
scan "GitHub token"                  'gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{20,}'
scan "AWS access key id"             'AKIA[0-9A-Z]{16}'
scan "Slack token"                   'xox[baprs]-[A-Za-z0-9-]{10,}'
scan "JSON web token"                'eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.'
scan "hard-coded credential"         '(password|passwd|secret|api[_-]?key|client[_-]?secret|access[_-]?token|refresh[_-]?token)[[:space:]]*[:=][[:space:]]*["'"'"'][^"'"'"'$]{6,}["'"'"']'
scan "connection string"             '[a-z][a-z0-9+.-]*://[^/[:space:]:@]+:[^/[:space:]@]+@'

# 3. IPv4 addresses other than loopback/any
ips=$(grep -InoE -e '\b([0-9]{1,3}\.){3}[0-9]{1,3}\b' -- "${files[@]}" 2>/dev/null | grep -v '^scripts/check-secrets.sh:' | grep -vE ':(127\.0\.0\.1|0\.0\.0\.0)$' || true)
[ -n "$ips" ] && { report "IPv4 address"; echo "$ips" | sed -E 's/^([^:]+:[0-9]+):.*/  \1/'; }

# 4. personal strings kept outside the repository
pp="${GATE_PRIVATE_PATTERNS:-$HOME/.gate-private-patterns}"
if [ -f "$pp" ]; then
  hits=$(grep -InF -i -f "$pp" -- "${files[@]}" 2>/dev/null | grep -v '^scripts/check-secrets.sh:' || true)
  [ -n "$hits" ] && { report "private pattern from $pp"; echo "$hits" | sed -E 's/^([^:]+:[0-9]+):.*/  \1: <line hidden>/'; }
fi

if [ "$fail" -eq 0 ]; then echo "check-secrets: OK (${#files[@]} files)"; else echo "check-secrets: FAILED"; fi
exit "$fail"
