# Duke 390 hlídač

Scraper, který hlídá bazary v ČR a v Německu (do ~5 h jízdy z Prahy) a hledá **KTM Duke 390 od ročníku 2023**.
Výsledek je přehledná stránka `report.html` (lokálně i na `http://localhost:8390`).

## Co prohledává

| Země | Bazar | Poznámka |
|---|---|---|
| 🇨🇿 | Bazoš, Sbazar, Motorkáři.cz, TipMoto | |
| 🇩🇪 | Kleinanzeigen, AutoScout24 | filtr podle odhadované doby jízdy z Prahy (PSČ → souřadnice) |
| 🇩🇪 | mobile.de | blokuje roboty → v reportu je jen odkaz na ruční hledání |

## Report

Sekce v tomhle pořadí:

1. 🔥 **Zajímavá cena** – aspoň o 8 % pod mediánem stejného ročníku
2. 🔊 **Laděný výfuk** – Akrapovič, Arrow, SC-Project, Remus, MIVV…
3. ⚡ **Quickshifter** – všechny motorky s QS (můžou být i v jiné sekci)
4. 🆕 **Model 2025 / 2026**
5. **Ostatní 2023+**
6. ❓ **Rok se nepodařilo zjistit**

Dál:
- inzeráty od **prodejců / salonů** jsou v každé sekci schované v rozklikávacím bloku,
- titulky a popisy se **překládají do angličtiny** (přepínač 🌐 EN / originál),
- štítky NOVÉ, zlevněno, podezřele levné, možná poškozená,
- filtr 🇨🇿 / 🇩🇪 a filtr generace: Gen 2 (373 ccm, do MY2023) / Gen 3 (399 ccm, od MY2024) – podle textu inzerátu (Gen 3, 399 ccm…), jinak podle roku; generace je i na každé kartě.

## Spuštění

Potřeba jen Python 3 s `requests` a `beautifulsoup4` (`pip install requests beautifulsoup4`).

```bash
python3 duke_scraper.py --open     # stáhne inzeráty a otevře report
python3 server.py                  # report na http://localhost:8390 s tlačítkem Aktualizovat
./install-launcher.sh              # přidá „Duke 390 hlídač“ do menu aplikací (spustí server + otevře prohlížeč)
./install.sh                       # automatické spouštění každé 2 h (systemd --user) + notifikace
./install.sh --remove              # vypnutí
```

Nastavení (max. doba jízdy, práh zajímavé ceny, jazyk překladu…) je nahoře v `duke_scraper.py`.

`state.json` (cache a historie cen) a `report.html` se do gitu neukládají – obsahují texty inzerátů.

## Android aplikace

Složka `android/` – nativní aplikace v Kotlinu (Jetpack Compose), která dělá totéž přímo v telefonu:
stejné bazary, stejná pravidla (ověřeno na 418 skutečných inzerátech proti Python verzi), sekce jako záložky,
prodejci schovaní, překlad EN/originál, kontrola každé 3 h na pozadí a notifikace o nových inzerátech.

```bash
cd android && ./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```

Podpisový klíč (`duke-release.jks`, `keystore.properties`) se do gitu neukládá – bez stejného klíče nejde
aplikace aktualizovat přes již nainstalovanou verzi.

## Data

`data/de_plz.tsv` – německá PSČ se souřadnicemi z [GeoNames](https://www.geonames.org/) (CC BY 4.0).

Určeno pro osobní použití; scraper posílá požadavky pomalu (1 s pauza) a výsledky cachuje.
