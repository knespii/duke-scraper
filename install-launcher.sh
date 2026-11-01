#!/usr/bin/env bash
# Přidá "Duke 390 hlídač" do menu aplikací. Odebrání: ./install-launcher.sh --remove
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
APP="$HOME/.local/share/applications/duke-390-hlidac.desktop"

if [[ "${1:-}" == "--remove" ]]; then
  rm -f "$APP"
  echo "Zástupce odebrán."
  exit 0
fi

chmod +x "$DIR/launcher.sh"
mkdir -p "$(dirname "$APP")"
cat > "$APP" <<EOF
[Desktop Entry]
Type=Application
Name=Duke 390 hlídač
GenericName=Hlídač inzerátů
Comment=KTM Duke 390 (2023+) z bazarů v ČR a Německu
Exec="$DIR/launcher.sh"
Icon=$DIR/icon.svg
Terminal=false
Categories=Network;
Keywords=ktm;duke;motorka;bazar;
StartupNotify=false
Actions=refresh;stop;

[Desktop Action refresh]
Name=Otevřít a aktualizovat inzeráty
Exec="$DIR/launcher.sh" --refresh

[Desktop Action stop]
Name=Zastavit server
Exec="$DIR/launcher.sh" --stop
EOF
chmod +x "$APP"
command -v update-desktop-database >/dev/null && update-desktop-database "$(dirname "$APP")" 2>/dev/null || true
echo "Hotovo – v menu aplikací najdeš „Duke 390 hlídač“."
