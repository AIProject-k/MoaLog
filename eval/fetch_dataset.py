"""분류 정확도 측정용 평가셋을 Wikimedia Commons에서 받는다 (자유 라이선스만).

검색어 자체가 정답 라벨이다 — 'food meal'로 받은 사진의 정답은 '음식'.
어노테이션 파일 파싱이 필요 없고 우리 8분류에 정확히 대응한다.

라이선스는 CC0/퍼블릭도메인을 먼저 채우고, 모자랄 때만 CC BY류로 보충한다.
어떤 라이선스가 몇 장인지 마지막에 그대로 보고한다.

    python eval/fetch_dataset.py [카테고리당장수]
"""
import csv, html, json, pathlib, re, sys, time, urllib.parse, urllib.request
from PIL import Image

ROOT = pathlib.Path(__file__).parent
OUT = ROOT / "dataset"
API = "https://commons.wikimedia.org/w/api.php"
UA = {"User-Agent": "photo-log-eval/0.1 (local zero-shot classification benchmark)"}

# Commons는 미술관·도서관 아카이브라 스캔한 판화/스케치/그림이 대량으로 섞인다.
# MET이 제목을 "Mountain Landscape"로 단 연필 스케치북 페이지가 '풍경' 정답으로
# 들어오면, 그걸 '문서'라고 답한 분류기가 오답 처리된다. 측정 도구가 망가지는 것이다.
# 그래서 사진 카테고리에서는 스캔본을 검색 단계와 제목 단계에서 두 번 걸러낸다.
PHOTO_NEG = ("-sketchbook -sketch -drawing -engraving -lithograph -etching -painting "
             "-woodcut -manuscript -illustration -postcard -diagram -poster -watercolor")
BLOCK = ("sketchbook", "sketch", "drawing", "engraving", "lithograph", "etching",
         "painting", "woodcut", "manuscript", "illustration", "postcard", "diagram",
         "poster", "watercolor", "aquatint", "mezzotint", "nypl", "_met_", "folio",
         "plate_", "album_", "portfolio", "drawn", "gravure", "daguerreotype")

# 카테고리: (폴더명, 검색어들, 스캔본 허용 여부)
PLAN = {
    "인물":         ("person",    ["portrait person face", "people group friends",
                                    "woman smiling portrait"], False),
    "음식":         ("food",      ["food meal plate", "restaurant dish cuisine",
                                    "cake dessert pastry"], False),
    "풍경/여행":    ("landscape", ["landscape mountain scenery", "beach sea coast",
                                    "city skyline street view"], False),
    "반려동물":     ("pet",       ["dog pet domestic", "cat pet domestic",
                                    "puppy kitten"], False),
    "문서/영수증":  ("document",  ["receipt paper", "invoice document form",
                                    "printed letter typed page"], True),
    "상품/쇼핑":    ("product",   ["shoes product photo", "clothing garment studio",
                                    "handbag product white background"], False),
    "실내일상":     ("indoor",    ["living room interior", "bedroom furniture interior",
                                    "kitchen home interior"], False),
    "화면캡처내용": ("screen",    ["software screenshot", "web browser screenshot",
                                    "application user interface screenshot"], True),
}

# 앞쪽일수록 선호. 여기 없는 라이선스는 아예 쓰지 않는다.
TIERS = [
    ("CC0/PD",  ("cc0", "public domain", "pd-", "no restrictions")),
    ("CC BY",   ("cc by 4.0", "cc by 3.0", "cc by 2.0", "cc by 2.5", "cc by 1.0")),
    ("CC BY-SA", ("cc by-sa",)),
]


def tier_of(lic):
    low = (lic or "").lower()
    for i, (_, keys) in enumerate(TIERS):
        if any(k in low for k in keys):
            return i
    return None                                   # 자유 라이선스가 아니면 버린다


def search(terms, limit=100, scans_ok=False):
    url = API + "?" + urllib.parse.urlencode({
        "action": "query", "generator": "search",
        "gsrsearch": f"filetype:bitmap {terms}" + ("" if scans_ok else " " + PHOTO_NEG), "gsrnamespace": "6", "gsrlimit": str(limit),
        "prop": "imageinfo", "iiprop": "url|extmetadata|size", "iiurlwidth": "640",
        "format": "json", "formatversion": "2",
    })
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=40) as r:
        pages = json.load(r).get("query", {}).get("pages", [])
    out = []
    for p in pages:
        ii = (p.get("imageinfo") or [None])[0]
        if not ii or not ii.get("thumburl"):
            continue
        em = ii.get("extmetadata", {})
        lic = (em.get("LicenseShortName") or {}).get("value", "")
        t = tier_of(lic)
        if t is None:
            continue
        artist = re.sub(r"<[^>]+>", "", (em.get("Artist") or {}).get("value", ""))
        if not scans_ok and any(b in p["title"].lower() for b in BLOCK):
            continue                               # 제목에 스캔본 신호가 있으면 버린다
        out.append({"tier": t, "title": p["title"], "thumb": ii["thumburl"],
                    "license": lic, "artist": html.unescape(artist).strip()[:60],
                    "page": ii.get("descriptionurl", "")})
    return out


def grab(url, dest):
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=40) as r:
            dest.write_bytes(r.read())
        with Image.open(dest) as im:               # 깨진 파일은 즉시 버린다
            im.verify()
        with Image.open(dest) as im:
            if min(im.size) < 120:
                dest.unlink(); return False
        return True
    except Exception:
        dest.unlink(missing_ok=True)
        return False


def main(per_cat=50):
    OUT.mkdir(parents=True, exist_ok=True)
    labels, credits, lic_count = [], [], {}
    for cat, (slug, queries, scans_ok) in PLAN.items():
        d = OUT / slug
        d.mkdir(exist_ok=True)
        for f in d.glob("*"):
            f.unlink()

        cands, seen = [], set()
        for q in queries:
            try:
                hits = search(q, scans_ok=scans_ok)
            except Exception as e:
                print(f"  {cat}: 검색 실패 {q!r} ({type(e).__name__})")
                continue
            for h in hits:
                if h["title"] not in seen:
                    seen.add(h["title"]); cands.append(h)
            time.sleep(0.5)
        cands.sort(key=lambda h: h["tier"])        # CC0/PD 먼저

        got = 0
        for h in cands:
            if got >= per_cat:
                break
            name = re.sub(r"[^\w.-]", "_", h["title"].removeprefix("File:"))[:70]
            dest = d / (pathlib.Path(name).stem + ".jpg")
            if dest.exists() or not grab(h["thumb"], dest):
                continue
            got += 1
            tier_name = TIERS[h["tier"]][0]
            lic_count[tier_name] = lic_count.get(tier_name, 0) + 1
            labels.append({"path": str(dest.relative_to(ROOT)).replace("\\", "/"),
                           "category": cat})
            credits.append({"file": dest.name, "title": h["title"], "creator": h["artist"],
                            "license": h["license"], "tier": tier_name, "source": h["page"]})
        print(f"  {cat:12s} {got:3d}장  (후보 {len(cands)})")

    if not labels:
        print("\n한 장도 받지 못했습니다. 네트워크를 확인하세요.")
        return
    for name, rows in (("labels.csv", labels), ("ATTRIBUTION.csv", credits)):
        with open(ROOT / name, "w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            w.writeheader(); w.writerows(rows)
    mb = sum(p.stat().st_size for p in OUT.rglob("*.jpg")) / 1e6
    print(f"\n총 {len(labels)}장 · {mb:.1f}MB → {OUT}")
    print("라이선스 구성: " + ", ".join(f"{k} {v}장" for k, v in sorted(lic_count.items())))
    print("라벨: eval/labels.csv · 출처: eval/ATTRIBUTION.csv")


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 50)
