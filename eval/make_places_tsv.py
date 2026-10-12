"""GeoNames cities15000에서 앱용 장소 표(places.tsv)를 만든다.

입력: GeoNames cities15000.zip (https://download.geonames.org/export/dump/cities15000.zip, CC BY 4.0)
출력: android/app/src/main/assets/places.tsv

규칙
- 한글 대체 이름이 있는 도시만 넣는다. 한국어 검색어로만 장소를 알아보게 하려는 것이다.
  영어 도시 이름은 "nice" 같은 일반 단어와 겹치므로 넣지 않는다.
- 이름은 가장 짧은 한글 이름, 나머지 한글 이름은 다른 이름(alias)이다. "강릉" / "강릉시".
- 같은 이름이 여럿이면 인구가 가장 많은 것 하나만 남긴다.
- 반경은 인구로 정한다(대략적인 행정 구역 크기). 이벤트 장소 이름과 장소 검색 모두 이 반경을 쓴다.

사용: python eval/make_places_tsv.py <cities15000.zip> [출력 경로]
"""
import pathlib
import re
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_OUT = ROOT / "android" / "app" / "src" / "main" / "assets" / "places.tsv"
# 대표 이름을 정해 둔 것. 키는 옛 이름이나 흔한 별칭, 값은 지금 부르는 이름.
PREFERRED = {"경성": "서울"}
HANGUL = re.compile(r"[가-힣]")
DIGIT = re.compile(r"\d")


def radius_km(population: int) -> int:
    if population >= 1_000_000:
        return 30
    if population >= 200_000:
        return 20
    if population >= 50_000:
        return 15
    return 10


def stem(name: str) -> str:
    """"강릉시" → "강릉". 시·군·구를 뗀 형태도 사람들이 부르는 말이다."""
    if len(name) >= 3 and name[-1] in "시군구":
        return name[:-1]
    return name


def main() -> None:
    src = pathlib.Path(sys.argv[1])
    out = pathlib.Path(sys.argv[2]) if len(sys.argv) > 2 else DEFAULT_OUT
    with zipfile.ZipFile(src) as z:
        text = z.read("cities15000.txt").decode("utf-8")

    # 1) 한글 이름 후보를 모은다. 공백은 지운다("가고시마 시" → "가고시마시").
    entries = []
    freq: dict[str, int] = {}
    for line in text.splitlines():
        c = line.split("	")
        if len(c) < 15:
            continue
        names = []
        for n in c[3].split(","):
            n = n.replace(" ", "").strip()
            if HANGUL.search(n) and len(n) >= 2 and not DIGIT.search(n) and n not in names:
                names.append(n)
        if not names:
            continue
        for n in names:
            freq[n] = freq.get(n, 0) + 1
        entries.append((float(c[4]), float(c[5]), int(c[14] or 0), names))

    # 2) 대표 이름은 여러 도시 목록에 가장 많이 나온 것(같으면 짧은 것). "경성"보다 "서울"이 먼저다.
    best: dict[str, tuple[int, str]] = {}
    for lat, lon, pop, names in entries:
        name = max(names, key=lambda n: (freq[n], -len(n)))
        # 옛 이름이 더 자주 나오는 도시는 지금 부르는 이름을 고른다(서울: "경성"이 아니라 "서울").
        name = next((PREFERRED[n] for n in names if n in PREFERRED), name)
        others = [n for n in names if n != name]
        stems = {stem(n) for n in [name, *others] if stem(n) != n}
        aliases = sorted(({n for n in others} | stems) - {name}, key=len)
        row = f"{name}	{'|'.join(aliases)}	{lat:.5f}	{lon:.5f}	{radius_km(pop)}"
        if name not in best or pop > best[name][0]:
            best[name] = (pop, row)

    rows = [row for _, row in sorted(best.values(), key=lambda t: t[1])]
    header = [
        "# MoaLog 장소 표 — 자동 생성(eval/make_places_tsv.py). 손으로 고치지 않는다.",
        "# 출처: GeoNames cities15000 (https://www.geonames.org/), 라이선스 CC BY 4.0.",
        "# 형식: 이름 \t 다른 이름(|) \t 위도 \t 경도 \t 반경(km)",
    ]
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(header + rows) + "\n", encoding="utf-8")
    print(out, len(rows), "places")


if __name__ == "__main__":
    main()
