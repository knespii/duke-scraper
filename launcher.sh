#!/usr/bin/env bash
# Spouštěč Duke 390 hlídače: nastartuje lokální server (pokud neběží) a otevře report v prohlížeči.
#   ./launcher.sh            -> otevřít report
#   ./launcher.sh --refresh  -> otevřít a rovnou spustit aktualizaci inzerátů
#   ./launcher.sh --stop     -> zastavit server
DIR="$(cd "$(dirname "$(readlink -f "$0")")" && pwd)"
PORT=8390
URL="http://localhost:$PORT"
LOG="$DIR/server.log"

running() { curl -s --max-time 1 "$URL/status" >/dev/null 2>&1; }

if [[ "${1:-}" == "--stop" ]]; then
  pkill -f "$DIR/server.py" && notify-send -a "Duke 390 hlídač" "Server zastaven" 2>/dev/null
  exit 0
fi

if ! running; then
  setsid nohup python3 "$DIR/server.py" "$PORT" >"$LOG" 2>&1 < /dev/null &
  for _ in $(seq 1 50); do running && break; sleep 0.1; done
  if ! running; then
    notify-send -a "Duke 390 hlídač" "Server se nepodařilo spustit" "Detaily v $LOG" 2>/dev/null
    exit 1
  fi
fi

# aktualizovat, když se o to řekne nebo je report starší než 3 hodiny
REPORT="$DIR/report.html"
if [[ "${1:-}" == "--refresh" || ! -f "$REPORT" || -n "$(find "$REPORT" -mmin +180 2>/dev/null)" ]]; then
  curl -s -X POST "$URL/refresh" >/dev/null
fi
xdg-open "$URL" >/dev/null 2>&1 &
