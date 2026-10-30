#!/usr/bin/env bash
# Nainstaluje automatické spouštění hlídače (systemd --user timer) a zástupce v menu aplikací.
# Odinstalace:  ./install.sh --remove
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
UNIT_DIR="$HOME/.config/systemd/user"
APP_DIR="$HOME/.local/share/applications"

if [[ "${1:-}" == "--remove" ]]; then
  systemctl --user disable --now duke-scraper.timer 2>/dev/null || true
  rm -f "$UNIT_DIR/duke-scraper.service" "$UNIT_DIR/duke-scraper.timer" "$APP_DIR/duke-scraper.desktop"
  systemctl --user daemon-reload
  echo "Odinstalováno."
  exit 0
fi

mkdir -p "$UNIT_DIR" "$APP_DIR"

cat > "$UNIT_DIR/duke-scraper.service" <<EOF
[Unit]
Description=Hlídač inzerátů KTM Duke 390
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
WorkingDirectory=$DIR
ExecStart=/usr/bin/python3 $DIR/duke_scraper.py
EOF

cat > "$UNIT_DIR/duke-scraper.timer" <<EOF
[Unit]
Description=Spouští hlídač Duke 390 každé 2 hodiny přes den

[Timer]
OnCalendar=*-*-* 08,10,12,14,16,18,20,22:00
OnBootSec=5min
RandomizedDelaySec=5min
Persistent=true

[Install]
WantedBy=timers.target
EOF

cat > "$APP_DIR/duke-scraper.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=Duke 390 hlídač
Comment=Přehled inzerátů KTM Duke 390 (2023+)
Exec=xdg-open $DIR/report.html
Icon=applications-internet
Terminal=false
Categories=Network;
Actions=refresh;

[Desktop Action refresh]
Name=Aktualizovat teď
Exec=sh -c "systemctl --user start duke-scraper.service && xdg-open $DIR/report.html"
EOF

systemctl --user daemon-reload
systemctl --user enable --now duke-scraper.timer
echo "Hotovo. Další spuštění:"
systemctl --user list-timers duke-scraper.timer --no-pager
