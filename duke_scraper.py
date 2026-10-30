#!/usr/bin/env python3
"""Hlídač inzerátů KTM Duke 390 (2023+) na bazarech v ČR a v Německu.

Spuštění:  python3 duke_scraper.py            -> stáhne inzeráty, vytvoří report.html, pošle notifikaci
           python3 duke_scraper.py --open     -> totéž a report otevře v prohlížeči
           python3 duke_scraper.py --no-notify
"""
import hashlib
import json
import math
import re
import statistics
import subprocess
import sys
import time
import traceback
import unicodedata
import webbrowser
from datetime import datetime, timedelta
from html import escape
from pathlib import Path
from urllib.parse import quote, urljoin

import requests
from bs4 import BeautifulSoup

# ---------------------------------------------------------------- nastavení
MIN_YEAR = 2023
MAX_DRIVE_HOURS_DE = 5.0          # max. odhadovaná doba jízdy z Prahy do místa v Německu
DEAL_THRESHOLD = 0.08             # "zajímavá cena" = aspoň o 8 % levnější než medián stejné generace
NEW_BADGE_HOURS = 48              # jak dlouho svítí štítek NOVÉ
DETAIL_CACHE_DAYS = 5             # jak často znovu stahovat detail inzerátu
UNKNOWN_YEAR_MIN_CZK = 80000     # inzerát bez roku a levnější než tohle = skoro jistě starší ročník -> skrýt
SUSPICIOUS = 0.33                 # víc než o 33 % pod mediánem = možná podvod / chyba v ceně
TRANSLATE_TO = "en"               # do jakého jazyka překládat inzeráty ("" = nepřekládat)
DETAIL_VERSION = 2               # zvýšit, když se mění, co se z detailu čte -> cache se obnoví
REQUEST_DELAY = 1.0               # pauza mezi požadavky (s), ať bazary nezatěžujeme

PRAGUE = (50.0755, 14.4378)
BASE = Path(__file__).resolve().parent
STATE_FILE = BASE / "state.json"
REPORT_FILE = BASE / "report.html"
PLZ_FILE = BASE / "data" / "de_plz.tsv"
NOW = datetime.now()
THIS_YEAR = NOW.year

UA = ("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/130.0 Safari/537.36")
S = requests.Session()
S.headers.update({"User-Agent": UA, "Accept-Language": "cs,de;q=0.8,en;q=0.6"})

# Laděné výfuky – značky a obecná slova (porovnává se bez diakritiky, malými písmeny)
EXHAUST_BRANDS = {
    "akrapovic": "Akrapovič", "arrow": "Arrow", "sc-project": "SC-Project",
    "sc project": "SC-Project", "scproject": "SC-Project", "leovince": "LeoVince",
    "leo vince": "LeoVince", "remus": "Remus", "yoshimura": "Yoshimura",
    "termignoni": "Termignoni", "mivv": "MIVV", "gpr": "GPR", "hp corse": "HP Corse",
    "austin racing": "Austin Racing", "ixil": "IXIL", "bodis": "Bodis", "zard": "Zard",
    "spark exhaust": "Spark", "two brothers": "Two Brothers", "toce": "Toce",
    "bos exhaust": "BOS", "laser exhaust": "Laser", "powerbox": "Powerbox",
}
EXHAUST_GENERIC = [
    r"laden\w* vyfuk", r"sportovni vyfuk", r"vyfukov\w* koncovk", r"koncovk\w* vyfuk",
    r"aftermarket", r"slip[- ]?on", r"full ?system", r"kompletni vyfuk", r"sportauspuff",
    r"auspuffanlage", r"endschalldampfer", r"\besd\b", r"\bexhaust\b", r"\bdecat\b",
    r"vyfuk \w+ s homologac", r"underseat", r"shark[- ]?(auspuff|exhaust|vyfuk)",
]

NOT_DUKE_390 = re.compile(
    r"super ?duke|duke ?(125|200|250|690|790|890|990|1290|1390)|adventure|\bsmc\b|enduro|"
    r"svartpilen|vitpilen|ankauf|\bsuche\b|kaufe|koupim|hledam|gesuch|"
    r"\bdil[yu]\b|nahradni|ersatzteil|\bteile\b|rozprod|ausschlacht|schlachtfest|"
    r"padaci|sturzbugel|kryt |plast|blinkr|zrcatk|polep|samolep")


def norm(t):
    t = unicodedata.normalize("NFKD", t or "")
    return "".join(c for c in t if not unicodedata.combining(c)).lower()


def get(url, **kw):
    time.sleep(REQUEST_DELAY)
    r = S.get(url, timeout=30, **kw)
    r.raise_for_status()
    return r


def soup(html):
    return BeautifulSoup(html, "html.parser")


def num(text):
    d = re.sub(r"[^\d]", "", text or "")
    return int(d) if d else None


# ---------------------------------------------------------------- rok výroby
YEAR_KW = (r"(?:rok(?:u)?(?: vyroby)?|r\.? ?v\.?|rocnik|vyroba|vyrobeno|model(?:ovy rok|ljahr|jahr)?|"
           r"\bmy|\bmj\.?|\bez|erstzulassung|zulassung|baujahr|\bbj\.?|registrac\w*|1\. ?reg\w*|provoz\w*)")
YEAR_RE = re.compile(YEAR_KW + r"[\s:.\-]{0,6}(?:(?:0?[1-9]|1[0-2])[/.] ?)?(20[1-2]\d)\b")
MY_SHORT = re.compile(r"\bmy ?'?(2[3-9])\b")
ANY_YEAR = re.compile(r"\b(20[1-2]\d)\b")
BAD_CTX = re.compile(r"(stk|tuv|\bhu\b|garanc|gewahrleist|platn|servis|service|inspektion|reifen|pneu|bis|do)\W{0,4}(?:\d{1,2}[/.])?\s*$")


def parse_year(*texts):
    """Vrátí (rok, zdroj) z textu inzerátu; None, když rok nejde spolehlivě určit."""
    for t in texts:
        t = norm(t)
        for m in YEAR_RE.finditer(t):
            y = int(m.group(1))
            if 2010 <= y <= THIS_YEAR + 1:
                return y
        m = MY_SHORT.search(t)
        if m:
            return 2000 + int(m.group(1))
    # nouzově: jediný rok v textu, který není u STK/servisu/záruky
    cands = set()
    for t in texts:
        t = norm(t)
        for m in ANY_YEAR.finditer(t):
            y = int(m.group(1))
            if 2010 <= y <= THIS_YEAR + 1 and not BAD_CTX.search(t[max(0, m.start() - 25):m.start()]):
                cands.add(y)
    return cands.pop() if len(cands) == 1 else None


def strong_year(title, desc=""):
    """Modelový rok uvedený výslovně: rok v titulku nebo za slovem rok/Modelljahr/MY/Mj./Baujahr."""
    t = norm(title)
    ys = {int(y) for y in ANY_YEAR.findall(t) if 2010 <= int(y) <= THIS_YEAR + 1}
    if len(ys) == 1:
        return ys.pop()
    m = re.search(r"(?:modell?(?:ovy)? ?(?:rok|jahr)?|\bmy|\bmj\.?|baujahr|\bbj\.?|rok vyroby|rocnik)\W{0,4}(20[1-2]\d)\b", norm(title + "\n" + desc))
    if m and 2010 <= int(m.group(1)) <= THIS_YEAR + 1:
        return int(m.group(1))
    m = MY_SHORT.search(t)
    return 2000 + int(m.group(1)) if m else None


def parse_km(text):
    t = norm(text)
    m = re.search(r"(?:najeto|najezd|nalet\w*|kilometerstand|laufleistung|tachostand|km ?stand)\D{0,15}([\d .]{1,9})\s*(tis|tkm|km)?", t)
    if not m:
        m = re.search(r"\b([\d][\d .]{0,7})\s*(km)\b", t)
    if not m:
        return None
    v = num(m.group(1))
    if v is None:
        return None
    if m.group(2) in ("tis", "tkm") and v < 1000:
        v *= 1000
    return v if v < 200000 else None


def detect_exhaust(text):
    t = norm(text)
    for k, label in EXHAUST_BRANDS.items():
        if re.search(r"\b" + re.escape(k) + r"\b", t):
            return label
    for p in EXHAUST_GENERIC:
        if re.search(p, t):
            return "laděný výfuk"
    return None


QS_RE = re.compile(r"q[u]?[i]?ck ?-?shift\w*|schaltautomat|schaltassist\w*|\bq\+|\bqs\+|blipper|"
                   r"rychlo ?razen\w*|rychlo ?radic\w*|easy ?shift")
QS_NEG = re.compile(r"ohne|nicht|kein|nachrust|optional|\bbez\b|chybi|neni|lze dokoupit|mozno dokoupit|not ")


def detect_qs(text):
    """True, když inzerát zmiňuje quickshifter a nepíše, že chybí / jde dokoupit."""
    t = norm(text)
    for m in QS_RE.finditer(t):
        ctx = t[max(0, m.start() - 30):m.end() + 25]
        if not QS_NEG.search(ctx):
            return True
    return False


DEALER_CUES = re.compile(
    r"\bs\. ?r\. ?o\b|\ba\. ?s\.(?!\w)|\bgmbh\b|autorizovany (dealer|prodejce)|odpoc\w* dph|cena (bez|vc\.?|vcetne) dph|"
    r"\bskladem\b|nov[ea] moto\b|predvadec\w*|financovan\w*|\bsplat\w*|\bleasing\w*|protiucet|vykup|"
    r"moznost (splatek|financovani|odpoctu)|novy motocykl|ihned k odberu|zaruka \d+ (rok|let)|\d ?(roky|let) zaruk|"
    r"mwst|finanzierung|inzahlungnahme|vorfuhr\w*|tageszulassung|neufahrzeug")


def looks_like_dealer(text):
    return bool(DEALER_CUES.search(norm(text)))


def is_duke390(title):
    t = norm(title)
    return bool(re.search(r"duke|390", t)) and "390" in t and "duke" in t and not NOT_DUKE_390.search(t)


# ---------------------------------------------------------------- vzdálenost
_PLZ = None


def plz_coords(plz):
    global _PLZ
    if _PLZ is None:
        _PLZ = {}
        if PLZ_FILE.exists():
            for line in PLZ_FILE.read_text().splitlines():
                p, la, lo = line.split("\t")
                _PLZ[p] = (float(la), float(lo))
    return _PLZ.get((plz or "").strip().zfill(5))


def drive_hours(coords):
    la1, lo1 = map(math.radians, PRAGUE)
    la2, lo2 = map(math.radians, coords)
    a = math.sin((la2 - la1) / 2) ** 2 + math.cos(la1) * math.cos(la2) * math.sin((lo2 - lo1) / 2) ** 2
    km = 6371 * 2 * math.asin(math.sqrt(a))
    # vzdušná čára * 1.27 = silnice; ~100 km/h průměr po dálnici + 15 min na výjezdy
    return round(km * 1.27 / 100 + 0.25, 1)


# ---------------------------------------------------------------- zdroje
def L(**kw):
    base = dict(source="", id="", url="", title="", price=None, currency="CZK", year=None,
                km=None, location="", country="CZ", plz="", image="", desc="", dealer=False,
                needs_detail=True)
    base.update(kw)
    return base


def src_bazos():
    out, seen = [], set()
    url = "https://motorky.bazos.cz/"
    for off in range(0, 400, 20):
        params = {"hledat": "duke 390", "rubriky": "motorky", "hlokalita": "", "humkreis": "25",
                  "cenaod": "50000", "cenado": "", "Submit": "Hledat", "kitx": "ne", "crp": off or ""}
        b = soup(get(url, params=params).text)
        items = b.select("div.inzeraty")
        if not items:
            break
        for d in items:
            a = d.select_one("h2.nadpis a")
            if not a:
                continue
            m = re.search(r"/inzerat/(\d+)/", a["href"])
            if not m or m.group(1) in seen:
                continue
            seen.add(m.group(1))
            lok = d.select_one(".inzeratylok")
            lok_parts = list(lok.stripped_strings) if lok else []
            img = d.select_one("img.obrazek")
            out.append(L(source="Bazoš", id=m.group(1), url=urljoin(url, a["href"]),
                         title=a.get_text(strip=True),
                         price=num(d.select_one(".inzeratycena").get_text()) if d.select_one(".inzeratycena") else None,
                         location=lok_parts[0] if lok_parts else "",
                         image=img["src"] if img else "",
                         desc=d.select_one(".popis").get_text(" ", strip=True) if d.select_one(".popis") else ""))
        if len(items) < 20:
            break
    return out


def det_bazos(x):
    b = soup(get(x["url"]).text)
    d = b.select_one(".popisdetail")
    return {"desc": d.get_text("\n", strip=True) if d else x["desc"]}


def src_sbazar():
    out = []
    for phrase in ("duke 390", "390 duke"):
        r = get("https://www.sbazar.cz/api/v1/items/search", params={"phrase": phrase, "limit": 100}).json()
        for it in r.get("results", []):
            if any(o["id"] == str(it["id"]) for o in out):
                continue
            img = (it.get("images") or [{}])[0].get("url", "")
            loc = it.get("locality") or {}
            out.append(L(source="Sbazar", id=str(it["id"]), url=f"https://www.sbazar.cz/inzerat/{it['seo_name']}",
                         title=it["name"], price=it.get("price"),
                         location=loc.get("municipality") or loc.get("district", ""),
                         image=("https:" + img + "?fl=exf|res,606,606,3|webp,75") if img.startswith("//") else img,
                         dealer=bool(it.get("premise")) or
                         (it.get("user") or {}).get("user_service", {}).get("shop_url", "").startswith("www.")))
    return out


def det_sbazar(x):
    r = get(f"https://www.sbazar.cz/api/v1/items/{x['id']}").json().get("result", {})
    return {"desc": r.get("description", "")}


def src_motorkari():
    out = []
    url = "https://www.motorkari.cz/motobazar/motorky/ktm/ktm-duke-390/"
    b = soup(get(url).text)
    for li in b.select("div.main ul.list > li"):
        a = li.select_one("h3 a")
        if not a:
            continue
        href = urljoin(url, a["href"])
        m = re.search(r"-(\d+)\.html", href)
        params = {th.get_text(strip=True): td.get_text(" ", strip=True)
                  for th, td in ((tr.find("th"), tr.find("td")) for tr in li.select("table tr")) if th and td}
        ps = li.select(".description p")
        yr = re.search(r"Ročník:\s*(\d{4})", li.get_text(" ", strip=True))
        img = li.select_one(".thumb img")
        out.append(L(source="Motorkáři.cz", id=m.group(1) if m else href, url=href, title=a.get_text(strip=True),
                     price=num(params.get("Cena")), year=int(yr.group(1)) if yr else None,
                     km=num(params.get("Najeto")), location=params.get("Kraj", "") + " kraj",
                     image=urljoin(url, img["src"]) if img else "",
                     desc=ps[1].get_text(" ", strip=True) if len(ps) > 1 else "",
                     dealer=bool(li.find(string=re.compile(r"-DPH")))))
    return out


def det_motorkari(x):
    b = soup(get(x["url"]).text)
    d = b.select_one(".box-highlighted .inner")
    return {"desc": d.get_text("\n", strip=True) if d else x["desc"]}


def src_tipmoto():
    out = []
    for od in range(0, 200, 10):
        url = f"https://www.tipmoto.com/modely/ktm/6919-390-duke.html?od={od}"
        b = soup(get(url).text)
        rows = [tr for tr in b.select("table.vypis tr") if tr.find("td")]
        if not rows:
            break
        for tr in rows:
            tds = tr.find_all("td")
            a = tr.select_one("a[href*='/motorky/ktm/']")
            if not a or len(tds) < 5:
                continue
            href = urljoin(url, a["href"])
            if any(o["url"] == href for o in out):
                continue
            m = re.search(r"/(\d+)-", href)
            img = tr.select_one("img")
            yr = re.search(r"20\d\d", tds[2].get_text())
            title = tds[1].get_text(" ", strip=True).replace("Vložit do schránky", "").strip()
            out.append(L(source="TipMoto", id=m.group(1) if m else href, url=href, title=title,
                         price=num(tds[4].get_text().split("Kč")[0]),
                         year=int(yr.group(0)) if yr else None, location=tds[3].get_text(" ", strip=True),
                         image=urljoin(url, img["src"]) if img else "", dealer=True))
        if len(rows) < 10:
            break
    return out


def det_tipmoto(x):
    b = soup(get(x["url"]).text)
    d = b.select_one("#detail-popis")
    return {"desc": d.get_text("\n", strip=True) if d else ""}


def src_kleinanzeigen():
    out = []
    for page in range(1, 11):
        p = f"seite:{page}/" if page > 1 else ""
        url = f"https://www.kleinanzeigen.de/s-motorraeder-roller/{p}duke-390/k0c305"
        b = soup(get(url).text)
        arts = b.select("article[data-adid]")
        if not arts:
            break
        new = 0
        for a in arts:
            aid = a["data-adid"]
            if any(o["id"] == aid for o in out):
                continue
            new += 1
            ld = {}
            sc = a.find("script", type="application/ld+json")
            if sc:
                try:
                    ld = json.loads(sc.string)
                except Exception:
                    pass
            for s_ in a.select("script, svg"):
                s_.decompose()
            parts = [t.strip() for t in a.stripped_strings]
            text = " | ".join(parts)
            plz = re.search(r"\b(\d{5})\s+([^|]+)", text)
            pm = re.search(r"([\d.]+)\s*€", text)
            ez = re.search(r"EZ (\d{2})/(\d{4})", text)
            out.append(L(source="Kleinanzeigen", id=aid, url=urljoin(url, a.get("data-href", "")),
                         title=ld.get("title") or (a.select_one("h2") or a).get_text(strip=True),
                         price=num(pm.group(1)) if pm else None, currency="EUR", country="DE",
                         year=int(ez.group(2)) if ez else None,
                         plz=plz.group(1) if plz else "", location=plz.group(2).strip() if plz else "",
                         image=(ld.get("contentUrl") or "").replace("$_59", "$_2"),
                         desc=ld.get("description", ""), dealer="PRO" in parts,
                         wanted="Gesuch" in parts))
        if not new or len(arts) < 25:
            break
    return [o for o in out if not o.pop("wanted", False)]


def det_kleinanzeigen(x):
    b = soup(get(x["url"]).text)
    d = b.select_one("#viewad-description-text")
    attrs = {}
    for li in b.select(".addetailslist--detail"):
        v = li.select_one(".addetailslist--detail--value")
        if v:
            attrs[li.get_text(" ", strip=True).replace(v.get_text(" ", strip=True), "").strip()] = v.get_text(" ", strip=True)
    res = {"desc": d.get_text("\n", strip=True) if d else x["desc"]}
    page = b.get_text(" ", strip=True)
    if "Gewerblicher Nutzer" in page:
        res["dealer"] = True
    elif "Privater Nutzer" in page:
        res["dealer"] = False
    ez = re.search(r"(20\d\d)", attrs.get("Erstzulassung", ""))
    if ez:
        res["year"] = int(ez.group(1))
    if attrs.get("Kilometerstand"):
        res["km"] = num(attrs["Kilometerstand"])
    return res


def src_autoscout():
    out = []
    for page in range(1, 6):
        url = f"https://www.autoscout24.de/lst-moto/ktm/390-duke?fregfrom={MIN_YEAR}&cy=D&page={page}"
        html = get(url).text
        m = re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', html, re.S)
        if not m:
            break
        pp = json.loads(m.group(1))["props"]["pageProps"]
        for l in pp.get("listings", []):
            fr = l.get("tracking", {}).get("firstRegistration", "")
            y = re.search(r"(20\d\d)", fr or "")
            v = l.get("vehicle", {})
            loc = l.get("location", {})
            out.append(L(source="AutoScout24", id=l["id"], url=urljoin("https://www.autoscout24.de", l["url"]),
                         title=f"{v.get('make', '')} {v.get('model', '')} {v.get('modelVersionInput') or ''}".strip(),
                         price=l.get("price", {}).get("priceRaw"), currency="EUR", country=loc.get("countryCode", "DE"),
                         year=int(y.group(1)) if y else (THIS_YEAR if fr == "new" else None),
                         is_new_bike=(v.get("offerType") == "N"),
                         km=num(v.get("mileageInKm")), plz=loc.get("zip", ""), location=loc.get("city", ""),
                         image=(l.get("images") or [""])[0], dealer=l.get("seller", {}).get("type") == "Dealer"))
        if page >= pp.get("numberOfPages", 1):
            break
    return out


def det_autoscout(x):
    html = get(x["url"]).text
    m = re.search(r'<script id="__NEXT_DATA__"[^>]*>(.*?)</script>', html, re.S)
    if not m:
        return {}
    ld = json.loads(m.group(1))["props"]["pageProps"].get("listingDetails", {})
    v = ld.get("vehicle", {})
    desc = soup(ld.get("description") or "").get_text("\n", strip=True)
    extra = " ".join(str(v.get(k) or "") for k in ("modelVersionInput", "variant", "subtitle"))
    return {"desc": (extra + "\n" + desc).strip()}


SOURCES = [
    ("Bazoš", src_bazos, det_bazos),
    ("Sbazar", src_sbazar, det_sbazar),
    ("Motorkáři.cz", src_motorkari, det_motorkari),
    ("TipMoto", src_tipmoto, det_tipmoto),
    ("Kleinanzeigen", src_kleinanzeigen, det_kleinanzeigen),
    ("AutoScout24", src_autoscout, det_autoscout),
]
DETAILS = {name: det for name, _, det in SOURCES}

MANUAL_LINKS = [
    ("mobile.de (blokuje roboty – otevřít ručně)",
     f"https://suchen.mobile.de/fahrzeuge/search.html?isSearchRequest=true&vc=Motorbike&s=Motorbike"
     f"&ms=14600%3B%3B%3B&fr={MIN_YEAR}%3A&ref=quickSearch&sb=p&od=up"),
    ("Kleinanzeigen – Duke 390", "https://www.kleinanzeigen.de/s-motorraeder-roller/duke-390/k0c305"),
    ("Bazoš – Duke 390", "https://motorky.bazos.cz/inzeraty/duke%20390/"),
]


# ---------------------------------------------------------------- překlad
def translate(text, state):
    """Přeloží text do TRANSLATE_TO (Google Translate, bez klíče). Výsledek se cachuje ve state.json."""
    text = (text or "").strip()
    if not text or not TRANSLATE_TO:
        return text
    cache = state.setdefault("tr", {})
    key = hashlib.sha1(f"{TRANSLATE_TO}:{text}".encode()).hexdigest()[:16]
    if key in cache:
        cache[key]["at"] = NOW.isoformat()
        return cache[key]["t"]
    try:
        time.sleep(0.3)
        r = S.get("https://translate.googleapis.com/translate_a/single", timeout=20,
                  params={"client": "gtx", "sl": "auto", "tl": TRANSLATE_TO, "dt": "t", "q": text})
        r.raise_for_status()
        d = r.json()
        out = "".join(seg[0] for seg in d[0] if seg and seg[0])
        if d[2] == TRANSLATE_TO:          # už je v cílovém jazyce
            out = text
    except Exception as e:
        print(f"  překlad selhal: {e}", file=sys.stderr)
        return None
    cache[key] = {"t": out, "at": NOW.isoformat()}
    return out


# ---------------------------------------------------------------- stav a kurz
def load_state():
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text())
        except Exception:
            pass
    return {"listings": {}, "details": {}, "eur": None}


def eur_rate(state):
    try:
        t = get("https://www.cnb.cz/cs/financni-trhy/devizovy-trh/kurzy-devizoveho-trhu/"
                "kurzy-devizoveho-trhu/denni_kurz.txt").text
        m = re.search(r"\|EUR\|([\d,]+)", t)
        if m:
            state["eur"] = float(m.group(1).replace(",", "."))
    except Exception:
        pass
    return state.get("eur") or 24.5


# ---------------------------------------------------------------- hlavní běh
def run(notify=True, open_report=False):
    state = load_state()
    rate = eur_rate(state)
    status, all_items = {}, []

    for name, fn, _ in SOURCES:
        try:
            items = fn()
            status[name] = {"ok": True, "found": len(items)}
            all_items += items
            print(f"[{name}] {len(items)} inzerátů")
        except Exception as e:
            status[name] = {"ok": False, "error": f"{type(e).__name__}: {e}"[:200]}
            print(f"[{name}] CHYBA: {e}", file=sys.stderr)
            traceback.print_exc()

    # 1) hrubý filtr: musí to být Duke 390, rozumná cena, ne starší rok
    cand = []
    for x in all_items:
        if not is_duke390(x["title"]):
            continue
        if not x["price"] or (x["currency"] == "EUR" and x["price"] < 1500) or (x["currency"] == "CZK" and x["price"] < 40000):
            continue
        if x["year"] is None:
            x["year"] = parse_year(x["title"], x["desc"])
        if x["year"] is not None and x["year"] < MIN_YEAR:
            continue
        if x["country"] == "DE":
            c = plz_coords(x["plz"])
            x["hours"] = drive_hours(c) if c else None
            if x["hours"] is not None and x["hours"] > MAX_DRIVE_HOURS_DE:
                continue
        elif x["country"] not in ("CZ",):
            continue
        cand.append(x)

    # 2) detaily (celý popis) – kvůli výfuku, roku a km; s cache
    for x in cand:
        key = f"{x['source']}:{x['id']}"
        cached = state["details"].get(key)
        fresh = cached and datetime.fromisoformat(cached["at"]) > NOW - timedelta(days=DETAIL_CACHE_DAYS) \
            and cached.get("price") == x["price"] and cached.get("v") == DETAIL_VERSION
        if fresh:
            det = cached["data"]
        else:
            try:
                det = DETAILS[x["source"]](x)
                state["details"][key] = {"at": NOW.isoformat(), "price": x["price"], "data": det, "v": DETAIL_VERSION}
            except Exception as e:
                print(f"  detail {key} selhal: {e}", file=sys.stderr)
                det = cached["data"] if cached else {}
        if det.get("desc"):
            x["desc"] = det["desc"]
        if det.get("km") and not x["km"]:
            x["km"] = det["km"]
        if "dealer" in det:
            x["dealer"] = det["dealer"]
        if det.get("year") and x["source"] == "Kleinanzeigen":
            x["year"] = det["year"]
        if x["year"] is None or x["source"] in ("Bazoš", "Sbazar"):
            x["year"] = parse_year(x["title"], x["desc"]) or x["year"]
        sy = strong_year(x["title"], x["desc"])
        if sy:
            x["year"] = sy

    # 3) finální výběr a obohacení
    final = []
    for x in cand:
        if x["year"] is not None and x["year"] < MIN_YEAR:
            continue
        price_czk = round(x["price"] * rate) if x["currency"] == "EUR" else x["price"]
        if x["year"] is None and price_czk < UNKNOWN_YEAR_MIN_CZK:
            continue
        full = f"{x['title']}\n{x['desc']}"
        x["exhaust"] = detect_exhaust(full)
        x["qs"] = detect_qs(full)
        if not x["km"]:
            x["km"] = parse_km(full)
        x["price_czk"] = round(x["price"] * rate) if x["currency"] == "EUR" else x["price"]
        n = norm(full)
        my = re.search(r"(?:model\w*(?: rok)?|\bmy)\W{0,3}(?:20)?(25|26|27)\b", n)
        x["my2526"] = (x["year"] or 0) >= 2025 or bool(my) or bool(x.get("is_new_bike"))
        if x["source"] in ("Bazoš", "Sbazar", "Motorkáři.cz") and not x["dealer"]:
            x["dealer"] = looks_like_dealer(full)
        x["damaged"] = bool(re.search(r"bourac|bourana|po nehode|unfall(?!frei)|defekt|motorschaden|poskozen", n))
        x["gen"] = None if x["year"] is None else ("2023 (stará gen.)" if x["year"] == 2023 else "2024+ (nová gen.)")
        final.append(x)

    # duplicity (stejný inzerát na TipMoto/Motoinzerce a Sbazaru apod.) – stejná cena + podobný název
    final.sort(key=lambda x: x["price_czk"])
    dedup = []
    for x in final:
        if any(abs(o["price_czk"] - x["price_czk"]) < 500 and (o["year"] == x["year"] or not o["year"] or not x["year"])
               and o["country"] == x["country"] and (o["km"] == x["km"] or not o["km"] or not x["km"])
               and o["source"] != x["source"] for o in dedup):
            continue
        dedup.append(x)
    final = dedup

    # 4) "zajímavá cena" = výrazně pod mediánem své generace
    medians = {}
    for g in {x["gen"] for x in final if x["gen"]}:
        prices = [x["price_czk"] for x in final if x["gen"] == g and not x["damaged"]]
        if len(prices) >= 3:
            medians[g] = statistics.median(prices)
    for y in {x["year"] for x in final if x["year"]}:
        prices = [x["price_czk"] for x in final if x["year"] == y and not x["damaged"]]
        if len(prices) >= 3:
            medians[str(y)] = statistics.median(prices)
    for x in final:
        med = medians.get(str(x["year"])) or medians.get(x["gen"])
        x["median"] = med
        x["deal_pct"] = (med - x["price_czk"]) / med if med else None
        x["deal"] = bool(x["deal_pct"] is not None and x["deal_pct"] >= DEAL_THRESHOLD and not x["damaged"])

    # 5) historie: nové inzeráty a zlevnění
    new_items = []
    seen_keys = set()
    for x in final:
        key = f"{x['source']}:{x['id']}"
        seen_keys.add(key)
        h = state["listings"].get(key)
        if not h:
            h = {"first_seen": NOW.isoformat(), "prices": []}
            state["listings"][key] = h
            new_items.append(x)
        h["last_seen"] = NOW.isoformat()
        if not h["prices"] or h["prices"][-1][1] != x["price_czk"]:
            h["prices"].append([NOW.date().isoformat(), x["price_czk"]])
        x["first_seen"] = h["first_seen"]
        x["is_new"] = datetime.fromisoformat(h["first_seen"]) > NOW - timedelta(hours=NEW_BADGE_HOURS)
        first_price = h["prices"][0][1]
        drop = first_price - x["price_czk"]
        x["price_drop"] = drop if 0 < drop < x["price_czk"] * 0.5 else 0
    # překlad titulku a popisu
    for x in final:
        x["title_tr"] = translate(x["title"], state)
        x["desc_tr"] = translate(re.sub(r"[ \t]+", " ", x["desc"] or "")[:1500], state)

    # uklidit staré záznamy (30 dní neviděné)
    cutoff = (NOW - timedelta(days=30)).isoformat()
    state["listings"] = {k: v for k, v in state["listings"].items() if v.get("last_seen", "") > cutoff}
    state["details"] = {k: v for k, v in state["details"].items() if v.get("at", "") > cutoff}
    state["tr"] = {k: v for k, v in state.get("tr", {}).items() if v.get("at", "") > cutoff}
    first_run = not state.get("last_run")
    state["last_run"] = NOW.isoformat()
    state["status"] = status
    STATE_FILE.write_text(json.dumps(state, ensure_ascii=False, indent=1))

    # 6) sekce v pořadí, které chceš
    sections = [
        ("deal", "🔥 Zajímavá cena", "Aspoň o {:.0f} % levnější než medián stejné generace.".format(DEAL_THRESHOLD * 100), []),
        ("exhaust", "🔊 Laděný výfuk", "Akrapovič, Arrow, SC-Project, Remus… (ze značky nebo popisu).", []),
        ("qs", "⚡ Quickshifter", "Všechny motorky s quickshifterem – můžou být zároveň i v jiné sekci.", []),
        ("my", "🆕 Model 2025 / 2026", "Novější ročníky, které nespadly do sekcí výš.", []),
        ("rest", "Ostatní 2023+", "Všechno ostatní, co splňuje rok 2023 a výš.", []),
        ("unknown", "❓ Rok se nepodařilo zjistit", "Inzerát rok neuvádí – zkontroluj ručně.", []),
    ]
    sec = {k: lst for k, _, _, lst in sections}
    for x in final:
        if x["year"] is None:
            sec["unknown"].append(x)
        elif x["deal"]:
            sec["deal"].append(x)
        elif x["exhaust"]:
            sec["exhaust"].append(x)
        elif x["my2526"]:
            sec["my"].append(x)
        else:
            sec["rest"].append(x)
    sec["qs"] += [x for x in final if x["qs"] and x["year"] is not None]
    sec["deal"].sort(key=lambda x: -x["deal_pct"])
    for k in ("exhaust", "qs", "my", "rest", "unknown"):
        sec[k].sort(key=lambda x: x["price_czk"])

    REPORT_FILE.write_text(render(sections, status, rate, medians, len(final)), encoding="utf-8")
    print(f"Hotovo: {len(final)} inzerátů → {REPORT_FILE}")

    if notify and new_items and not first_run:
        deals = [x for x in new_items if x["deal"]]
        top = (deals or new_items)[0]
        body = "\n".join(f"{x['title'][:40]} – {fmt_czk(x['price_czk'])} ({x['source']})" for x in new_items[:5])
        summary = (f"🔥 {len(deals)} zajímavých + " if deals else "") + f"{len(new_items)} nových Duke 390"
        try:
            subprocess.run(["notify-send", "-a", "Duke 390 hlídač", "-i", "applications-internet",
                            summary, body], timeout=10)
        except Exception:
            pass
    if open_report:
        webbrowser.open(REPORT_FILE.as_uri())


# ---------------------------------------------------------------- HTML
def fmt_czk(v):
    return f"{v:,.0f} Kč".replace(",", " ") if v is not None else "?"


def card(x):
    badges = []
    if x["is_new"]:
        badges.append('<span class="b new">NOVÉ</span>')
    if x["deal"]:
        badges.append(f'<span class="b deal">−{x["deal_pct"] * 100:.0f} % pod mediánem ročníku</span>')
    if x["deal_pct"] and x["deal_pct"] > SUSPICIOUS:
        badges.append('<span class="b dmg">⚠ podezřele levné – ověř</span>')
    if x["price_drop"]:
        badges.append(f'<span class="b drop">↓ zlevněno o {fmt_czk(x["price_drop"])}</span>')
    if x["exhaust"]:
        badges.append(f'<span class="b exh">🔊 {escape(x["exhaust"])}</span>')
    if x["qs"]:
        badges.append('<span class="b qs">⚡ quickshifter</span>')
    if x["my2526"]:
        badges.append('<span class="b my">MY 25/26</span>')
    if x["damaged"]:
        badges.append('<span class="b dmg">⚠ možná poškozená</span>')
    if x["dealer"]:
        badges.append('<span class="b dl">prodejce</span>')
    orig = f'<span class="orig">{x["price"]:,} €</span>'.replace(",", " ") if x["currency"] == "EUR" else ""
    flag = "🇩🇪" if x["country"] == "DE" else "🇨🇿"
    dist = f' · 🚗 ~{x["hours"]} h z Prahy' if x.get("hours") else ""
    meta = " · ".join(filter(None, [
        f'{x["km"]:,} km'.replace(",", " ") if x["km"] else None,
    ]))
    def short(t):
        return escape(re.sub(r"\s+", " ", t or "")[:400])

    def both(orig, tr, cls):
        if tr and tr.strip() != (orig or "").strip():
            return (f'<div class="{cls} tr">{short(tr) if cls == "desc" else escape(tr)}</div>'
                    f'<div class="{cls} og">{short(orig) if cls == "desc" else escape(orig)}</div>')
        return f'<div class="{cls}">{short(orig) if cls == "desc" else escape(orig)}</div>'
    img = f'<img src="{escape(x["image"])}" loading="lazy" referrerpolicy="no-referrer" alt="">' if x["image"] else '<div class="noimg">bez fotky</div>'
    return f'''<a class="card" href="{escape(x["url"])}" target="_blank" rel="noopener" data-country="{x["country"]}">
  <div class="ph">{img}</div>
  <div class="body">
    <div class="top"><span class="yr{" unk" if not x["year"] else ""}">{x["year"] or "rok ?"}</span><span class="price">{fmt_czk(x["price_czk"])}</span>{orig}</div>
    {both(x["title"], x.get("title_tr"), "title")}
    <div class="meta">{meta}</div>
    <div class="loc">{flag} {escape(x["location"] or "")}{dist} · {escape(x["source"])}</div>
    <div class="badges">{"".join(badges)}</div>
    {both(x["desc"], x.get("desc_tr"), "desc")}
  </div>
</a>'''


def render(sections, status, rate, medians, total):
    st = []
    for name, s in status.items():
        if s["ok"]:
            st.append(f'<span class="src ok">{escape(name)} ✓ {s["found"]}</span>')
        else:
            st.append(f'<span class="src err" title="{escape(s["error"])}">{escape(name)} ✗ chyba</span>')
    med = " · ".join(f"{escape(g)}: {fmt_czk(m)}" for g, m in sorted(medians.items()) if g.isdigit())
    nav, body = [], []
    for key, title, sub, items in sections:
        if not items:
            continue
        priv = [x for x in items if not x["dealer"]]
        dl = [x for x in items if x["dealer"]]
        extra = f' <span class="cnt dlc">+{len(dl)} 🏪</span>' if dl else ""
        nav.append(f'<a href="#{key}">{title} <b>{len(priv)}</b>{extra}</a>')
        open_attr = "" if key == "unknown" else " open"
        grid = f'<div class="grid">{"".join(card(x) for x in priv)}</div>' if priv else '<p class="empty">Od soukromníků nic.</p>'
        dealers = (f'<details class="dealers"><summary>🏪 Od prodejců / ze salonů ({len(dl)}) – rozkliknout</summary>'
                   f'<div class="grid">{"".join(card(x) for x in dl)}</div></details>') if dl else ""
        body.append(f'<details id="{key}" class="sec"{open_attr}><summary><h2>{title} <span class="cnt">{len(priv)}</span>{extra}</h2>'
                    f'<p>{sub}</p></summary>{grid}{dealers}</details>')
    links = " · ".join(f'<a href="{escape(u)}" target="_blank" rel="noopener">{escape(t)}</a>' for t, u in MANUAL_LINKS)
    return f'''<!doctype html>
<html lang="cs"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Duke 390 hlídač</title>
<style>
:root{{--bg:#f6f5f2;--card:#fff;--ink:#1d1d1b;--mut:#6b6a66;--line:#e4e2dc;--acc:#ff6a00;
--deal:#0b7a3e;--deal-bg:#dff3e7;--exh:#7a3df0;--exh-bg:#ece4fd;--my:#0a5fb4;--my-bg:#e0edfb;--new:#fff;--new-bg:#ff6a00;--qs:#8a5a00;--qs-bg:#fdf0d2;--dmg:#a11;--dmg-bg:#fde3e3}}
@media (prefers-color-scheme:dark){{:root{{--bg:#151514;--card:#201f1d;--ink:#eeece6;--mut:#a09d95;--line:#33312d;
--deal:#6fdc9d;--deal-bg:#173524;--exh:#c4a8ff;--exh-bg:#2c2246;--my:#8cc4ff;--my-bg:#172a40;--qs:#ffd27a;--qs-bg:#3a2c10;--dmg:#ff9a9a;--dmg-bg:#3d1b1b}}}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--bg);color:var(--ink);font:15px/1.45 system-ui,-apple-system,"Segoe UI",sans-serif}}
header{{padding:22px 16px 10px;max-width:1300px;margin:auto}}h1{{margin:0;font-size:26px}}h1 span{{color:var(--acc)}}
.sub{{color:var(--mut);margin:4px 0 10px}}.srcs{{display:flex;flex-wrap:wrap;gap:6px;margin:8px 0}}
.src{{font-size:12px;padding:3px 8px;border-radius:99px;border:1px solid var(--line)}}.src.err{{color:var(--dmg);background:var(--dmg-bg);border-color:transparent}}
nav{{position:sticky;top:0;z-index:5;background:var(--bg);border-bottom:1px solid var(--line);padding:8px 16px;display:flex;gap:8px;flex-wrap:wrap;justify-content:center}}
nav a,nav button{{font:inherit;font-size:13px;text-decoration:none;color:var(--ink);padding:5px 11px;border-radius:99px;background:var(--card);border:1px solid var(--line);cursor:pointer}}
nav button.on{{background:var(--ink);color:var(--bg)}}
main{{max-width:1300px;margin:auto;padding:0 16px 60px}}.sec{{margin-top:22px}}summary{{cursor:pointer;list-style:none}}summary::-webkit-details-marker{{display:none}}
h2{{margin:0;font-size:20px}}.cnt{{color:var(--mut);font-weight:400}}summary p{{margin:2px 0 12px;color:var(--mut);font-size:13px}}
.grid{{display:grid;grid-template-columns:repeat(auto-fill,minmax(290px,1fr));gap:14px}}
.card{{display:flex;flex-direction:column;background:var(--card);border:1px solid var(--line);border-radius:12px;overflow:hidden;color:inherit;text-decoration:none;transition:transform .12s,box-shadow .12s}}
.card:hover{{transform:translateY(-2px);box-shadow:0 6px 20px rgba(0,0,0,.12)}}
.ph{{aspect-ratio:4/3;background:var(--line);overflow:hidden}}.ph img{{width:100%;height:100%;object-fit:cover;display:block}}
.noimg{{display:grid;place-items:center;height:100%;color:var(--mut)}}
.body{{padding:11px 13px 13px;display:flex;flex-direction:column;gap:4px}}
.top{{display:flex;align-items:baseline;gap:8px}}.price{{font-size:21px;font-weight:700}}.orig{{color:var(--mut);font-size:13px}}
.yr{{align-self:center;font-weight:700;font-size:15px;padding:2px 9px;border-radius:7px;background:var(--ink);color:var(--bg)}}
.yr.unk{{background:var(--line);color:var(--mut)}}
.title{{font-weight:600}}.meta,.loc{{color:var(--mut);font-size:13px}}
.badges{{display:flex;flex-wrap:wrap;gap:5px;margin:3px 0}}.b{{font-size:11.5px;font-weight:600;padding:2px 7px;border-radius:6px;background:var(--line)}}
.b.new{{background:var(--new-bg);color:var(--new)}}.b.deal,.b.drop{{background:var(--deal-bg);color:var(--deal)}}
.b.exh{{background:var(--exh-bg);color:var(--exh)}}.b.qs{{background:var(--qs-bg);color:var(--qs)}}.b.my{{background:var(--my-bg);color:var(--my)}}.b.dmg{{background:var(--dmg-bg);color:var(--dmg)}}
.desc{{font-size:12.5px;color:var(--mut);display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden}}
footer{{color:var(--mut);font-size:13px;margin-top:30px}}footer a{{color:inherit}}
.hide{{display:none!important}}
.dealers{{margin-top:12px;border:1px dashed var(--line);border-radius:12px;padding:8px 12px}}
.dealers>summary{{color:var(--mut);font-size:14px;font-weight:600;padding:4px 0}}.dealers[open]>summary{{margin-bottom:10px}}
.dealers .card{{opacity:.92}}.empty{{color:var(--mut);font-size:13px;margin:0}}.dlc{{font-size:13px}}
body:not(.orig) .og,body.orig .tr{{display:none}}
.desc{{-webkit-line-clamp:4!important}}
</style></head><body>
<header>
<h1>KTM Duke 390 <span>2023+</span></h1>
<div class="sub">Aktualizováno {NOW.strftime("%-d. %-m. %Y %H:%M")} · {total} inzerátů · ČR + Německo do {MAX_DRIVE_HOURS_DE:g} h z Prahy · 1 € = {rate:.2f} Kč</div>
<div class="sub">Mediány: {med or "zatím málo dat"}</div>
<div class="srcs">{"".join(st)}</div>
</header>
<nav>{"".join(nav)}<button data-f="all" class="on">Vše</button><button data-f="CZ">🇨🇿 ČR</button><button data-f="DE">🇩🇪 DE</button><button id="lang" title="Přepnout mezi překladem a originálem">🌐 EN</button><button id="dltoggle">🏪 Ukázat prodejce</button><button id="refresh" class="hide">🔄 Aktualizovat</button></nav>
<main>{"".join(body) or "<p>Nic nenalezeno.</p>"}
<footer><p>Ručně: {links}</p><p>Vzdálenost v Německu je odhad podle PSČ (vzdušná čára × 1,27 při ~100 km/h). Data PSČ © GeoNames (CC BY 4.0).</p></footer>
</main>
<script>
const rb=document.getElementById('refresh');
if(location.protocol.startsWith('http')){{
 rb.classList.remove('hide');
 const poll=async()=>{{const s=await (await fetch('/status')).json();
  if(s.running){{rb.textContent='⏳ Stahuji… (1–3 min)';rb.disabled=true;setTimeout(poll,4000)}}
  else if(rb.disabled){{location.reload()}}}};
 rb.onclick=async()=>{{await fetch('/refresh',{{method:'POST'}});poll()}};
 poll();
}}
const dt=document.getElementById('dltoggle');
dt.onclick=()=>{{const open=dt.dataset.open!=='1';dt.dataset.open=open?'1':'';
 document.querySelectorAll('details.dealers').forEach(d=>d.open=open);
 dt.textContent=open?'🏪 Skrýt prodejce':'🏪 Ukázat prodejce';dt.classList.toggle('on',open)}};
const lang=document.getElementById('lang');
function setLang(orig){{document.body.classList.toggle('orig',orig);lang.textContent=orig?'🌐 Originál':'🌐 EN';
 try{{localStorage.setItem('dukeLangOrig',orig?'1':'')}}catch(e){{}}}}
try{{setLang(localStorage.getItem('dukeLangOrig')==='1')}}catch(e){{}}
lang.onclick=()=>setLang(!document.body.classList.contains('orig'));
document.querySelectorAll('nav button[data-f]').forEach(b=>b.onclick=()=>{{
 document.querySelectorAll('nav button[data-f]').forEach(x=>x.classList.toggle('on',x===b));
 const f=b.dataset.f;document.querySelectorAll('.card').forEach(c=>c.classList.toggle('hide',f!=='all'&&c.dataset.country!==f));
}});
</script>
</body></html>'''


if __name__ == "__main__":
    run(notify="--no-notify" not in sys.argv, open_report="--open" in sys.argv)
