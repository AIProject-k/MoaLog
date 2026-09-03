"""스캔 → EXIF → 썸네일 → 품질점수 → 규칙태그 → (M1: CLIP) → 중복/이벤트 그룹핑."""
import hashlib, pathlib, re, datetime as dt
import numpy as np
from PIL import Image, ImageOps

import store

# HEIC(아이폰)는 pillow-heif가 있으면 자동으로 열린다. 없으면 그 파일만 건너뛴다.
try:
    from pillow_heif import register_heif_opener
    register_heif_opener()
except ImportError:
    pass

ROOT = pathlib.Path(__file__).parent
THUMBS = ROOT / "data" / "thumbs"


# ---------- EXIF ----------

def _dms(vals, ref):
    d, m, s = (float(v) for v in vals)
    deg = d + m / 60 + s / 3600
    return -deg if str(ref).upper().startswith(("S", "W")) else deg


def read_exif(img):
    """(taken_at, camera, lat, lon). 실패해도 절대 예외를 올리지 않는다."""
    taken, lat, lon, cam = None, None, None, None
    try:
        ex = img.getexif()
        if not ex:
            return None, None, None, None
        make, model = ex.get(0x010F), ex.get(0x0110)
        cam = " ".join(str(x).strip() for x in (make, model) if x) or None

        ifd = ex.get_ifd(0x8769)                                     # Exif IFD
        raw = ifd.get(0x9003) or ifd.get(0x9004) or ex.get(0x0132)   # Original/Digitized/DateTime
        if raw:
            try:
                # EXIF에는 타임존이 없다. 개인 사진첩이므로 로컬시각으로 해석한다.
                taken = int(dt.datetime.strptime(str(raw).strip(), "%Y:%m:%d %H:%M:%S").timestamp())
            except (ValueError, OSError):
                taken = None                    # "0000:00:00 00:00:00" 같은 쓰레기값

        gps = ex.get_ifd(0x8825)
        if gps and gps.get(2) and gps.get(4):
            lat, lon = _dms(gps[2], gps.get(1, "N")), _dms(gps[4], gps.get(3, "E"))
            if not (-90 <= lat <= 90 and -180 <= lon <= 180) or (lat == 0 and lon == 0):
                lat = lon = None
    except Exception:
        pass
    return taken, cam, lat, lon


# ---------- 품질 점수 ----------

def laplacian_var(gray):
    """선명도. 낮을수록 흐리다. 크기 의존적이므로 호출 전에 긴 변을 정규화해 둘 것."""
    if min(gray.shape) < 3:
        return 0.0
    lap = (gray[:-2, 1:-1] + gray[2:, 1:-1] + gray[1:-1, :-2] + gray[1:-1, 2:]
           - 4 * gray[1:-1, 1:-1])
    return float(lap.var())


# ---------- 규칙 태그 (Tier 0, 비용 0) ----------

def rule_tags(meta, cfg):
    tags = []
    w, h = meta["width"] or 0, meta["height"] or 0
    ratio = max(w, h) / min(w, h) if w and h else 0.0
    tol = cfg["screen_ratio_tolerance"]
    looks_like_screen = bool(ratio) and any(abs(ratio - r) / r <= tol for r in cfg["screen_ratios"])

    if meta["camera_model"]:
        tags.append(("카메라촬영", None, "rule"))
    elif looks_like_screen:
        tags.append(("스크린샷", None, "rule"))
    else:
        tags.append(("저장/다운로드", None, "rule"))

    # 촬영시각을 실제로 아는 경우에만 야간 판정. mtime의 시각은 촬영시점이 아니다.
    if meta["taken_at"] and meta["taken_at_source"] == "exif":
        hour = dt.datetime.fromtimestamp(meta["taken_at"]).hour
        if hour >= 20 or hour < 6:
            tags.append(("야간", None, "rule"))

    if meta["gps_lat"] is not None:
        tags.append(("위치있음", None, "rule"))
    return tags


# ---------- 1장 분석 (디코드 1회로 EXIF·썸네일·품질을 전부 뽑는다) ----------

def analyze(path, pid, cfg):
    p = pathlib.Path(path)
    st = p.stat()
    with open(p, "rb") as f:
        sha = hashlib.file_digest(f, "sha256").hexdigest()

    with Image.open(p) as img:
        w, h = img.size                                # draft()가 크기를 바꾸므로 먼저 확보
        taken, cam, lat, lon = read_exif(img)
        # 필요 이상으로 디코드하지 않는다. CLIP은 224, 썸네일은 thumb_px면 되므로
        # decode_px 위로는 전부 낭비다 — 여기가 파이프라인에서 두 번째로 비싼 단계였다.
        img.draft("RGB", (cfg["decode_px"],) * 2)      # JPEG 축소 디코딩(타 포맷은 무시됨)
        im = ImageOps.exif_transpose(img).convert("RGB")

    # 흐림 점수: 먼저 L로 바꾸고 줄인다. 1채널 리사이즈가 RGB보다 3배 싸다.
    # 긴 변을 analysis_px로 맞춰 해상도 의존성을 제거한다.
    g = im.convert("L")
    s = cfg["analysis_px"] / max(g.size)
    if s < 1:
        g = g.resize((max(1, round(g.width * s)), max(1, round(g.height * s))), Image.BILINEAR)
    gray = np.asarray(g, dtype=np.float32)

    # CLIP 입력용. 전처리가 짧은 변을 224로 맞춰 크롭하므로 그 이상은 필요 없다.
    s = cfg["analysis_px"] / min(im.size)
    base = im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))),
                     Image.BILINEAR) if s < 1 else im

    THUMBS.mkdir(parents=True, exist_ok=True)
    thumb = THUMBS / f"{pid}.jpg"
    # base가 별도 객체면 im을 그대로 줄여도 안전하다 (thumbnail은 제자리 수정).
    thumb_img = im if base is not im else im.copy()
    thumb_img.thumbnail((cfg["thumb_px"],) * 2, Image.BILINEAR, reducing_gap=2.0)
    thumb_img.save(thumb, "JPEG", quality=80)

    meta = {
        "sha256": sha, "bytes": st.st_size,
        "taken_at": taken or int(st.st_mtime),
        "taken_at_source": "exif" if taken else "mtime",
        "gps_lat": lat, "gps_lon": lon,
        "width": w, "height": h, "camera_model": cam,
        "blur_score": laplacian_var(gray), "brightness": float(gray.mean()),
        "embedding": None, "thumb_path": str(thumb),
    }
    return meta, rule_tags(meta, cfg), base


# ---------- 배치 처리 ----------

def scan_folder(conn, root, cfg):
    exts = {e.lower() for e in cfg["extensions"]}
    found = [p for p in pathlib.Path(root).rglob("*")
             if p.is_file() and p.suffix.lower() in exts]
    store.queue_paths(conn, found)
    return len(found)


def process_batch(conn, cfg, encoder=None):
    """처리한 장수를 반환. 0이면 큐가 비었다. 배치마다 커밋해 중단 복구를 보장한다."""
    rows = store.pending(conn, cfg["batch_size"])
    if not rows:
        return 0
    ok = []
    for r in rows:
        try:
            meta, tags, base = analyze(r["path"], r["id"], cfg)
            ok.append([r["id"], meta, tags, base])
        except Exception:
            store.mark_failed(conn, r["id"])       # 열 수 없는 파일도 재시도하지 않는다

    if encoder is not None and ok:                 # M1: CLIP 임베딩 + 제로샷 분류
        vecs = encoder.encode_images([b for *_, b in ok])
        for entry, v in zip(ok, vecs):
            entry[1]["embedding"] = v.astype(np.float32).tobytes()
            entry[2].extend(encoder.classify(v, cfg))

    for pid, meta, tags, _ in ok:
        store.save_photo(conn, pid, meta, tags)
    conn.commit()
    return len(rows)


# ---------- 중복 그룹핑 ----------

_COPY_SUFFIX = re.compile(r"[\(\[]\d+[\)\]]\s*$")


def origin_rank(path, cfg):
    """중복 중 '원본일 가능성' 순위. 작을수록 원본.

    스캔 순서(id)로 고르면 안 된다 — 'cafe_01 - 복사본.jpg'가 'cafe_01.jpg'보다
    사전순으로 앞서서 사본을 원본으로 골라버린다. 사본은 이름에 접미사가 붙어
    길어진다는 성질을 쓴다."""
    name = pathlib.Path(path).name
    stem = pathlib.Path(name).stem.lower()
    marked = (any(h.lower() in stem for h in cfg["copy_markers"])
              or bool(_COPY_SUFFIX.search(stem)))
    return (marked, len(name), name)


def group_dupes(conn, cfg, use_embeddings=True):
    """완전중복(SHA-256)은 항상, 연사(임베딩+시간)는 임베딩이 있을 때만."""
    conn.execute("DELETE FROM dupe_groups")
    gid, grouped = 0, set()

    rows = conn.execute("""SELECT id, path, sha256 FROM photos
        WHERE sha256 IS NOT NULL AND thumb_path IS NOT NULL AND sha256 IN (
            SELECT sha256 FROM photos WHERE sha256 IS NOT NULL AND thumb_path IS NOT NULL
            GROUP BY sha256 HAVING COUNT(*) > 1)
        ORDER BY sha256, id""").fetchall()
    by_sha = {}
    for r in rows:
        by_sha.setdefault(r["sha256"], []).append(r)

    for members in by_sha.values():
        gid += 1
        best = min(members, key=lambda r: origin_rank(r["path"], cfg))["id"]
        grouped.update(r["id"] for r in members)
        conn.executemany("INSERT INTO dupe_groups VALUES (?,?,?,?)",
                         [(gid, r["id"], "exact", 1 if r["id"] == best else 0) for r in members])

    if use_embeddings:
        gid = _group_bursts(conn, cfg, gid, grouped)
    conn.commit()
    return gid


def _group_bursts(conn, cfg, gid, grouped):
    """연사 판정에는 시간 조건을 반드시 AND로 건다. 임베딩 유사도만 쓰면
    다른 날 같은 카페에서 찍은 커피 사진이 서로 중복으로 잡힌다.

    시간은 EXIF 촬영시각일 때만 믿는다. mtime은 '파일이 생긴 시각'이라
    한 번에 내려받은 사진들이 전부 초 단위로 붙어 버린다 — 그러면 시간 조건이
    항상 참이 되어 유사도만으로 그룹이 정해진다. 규칙태그의 야간 판정이
    같은 이유로 이미 이 가드를 쓴다."""
    rows = conn.execute("""SELECT id, taken_at, blur_score, embedding FROM photos
                           WHERE embedding IS NOT NULL AND taken_at IS NOT NULL
                             AND taken_at_source = 'exif'
                             AND thumb_path IS NOT NULL
                           ORDER BY taken_at""").fetchall()
    if len(rows) < 2:
        return gid
    thr, gap = cfg["dupe_similarity"], cfg["burst_gap_sec"]
    vecs = [np.frombuffer(r["embedding"], dtype=np.float32) for r in rows]

    run = [0]
    for i in range(1, len(rows) + 1):
        cont = (i < len(rows)
                and rows[i]["taken_at"] - rows[i - 1]["taken_at"] <= gap
                and float(vecs[i] @ vecs[run[0]]) >= thr)
        if cont:
            run.append(i)
            continue
        if len(run) > 1 and not any(rows[j]["id"] in grouped for j in run):
            gid += 1
            best = max(run, key=lambda j: rows[j]["blur_score"] or 0)   # 가장 선명한 것을 남긴다
            conn.executemany("INSERT INTO dupe_groups VALUES (?,?,?,?)",
                             [(gid, rows[j]["id"], "burst", 1 if j == best else 0) for j in run])
        run = [i]
    return gid


# ---------- 타임라인 이벤트 (AI 불필요, EXIF 시각만 사용) ----------

def build_events(conn, cfg):
    conn.execute("DELETE FROM event_photos")
    conn.execute("DELETE FROM events")
    gap = cfg["event_gap_hours"] * 3600
    rows = conn.execute("""SELECT id, taken_at, gps_lat, gps_lon, blur_score FROM photos
                           WHERE taken_at IS NOT NULL AND thumb_path IS NOT NULL
                           ORDER BY taken_at""").fetchall()
    groups, cur = [], []
    for r in rows:
        if cur and r["taken_at"] - cur[-1]["taken_at"] > gap:
            groups.append(cur)
            cur = []
        cur.append(r)
    if cur:
        groups.append(cur)

    for g in groups:
        pts = [(r["gps_lat"], r["gps_lon"]) for r in g if r["gps_lat"] is not None]
        lat = sum(p[0] for p in pts) / len(pts) if pts else None
        lon = sum(p[1] for p in pts) / len(pts) if pts else None
        eid = conn.execute(
            "INSERT INTO events(started_at,ended_at,gps_lat,gps_lon) VALUES (?,?,?,?)",
            (g[0]["taken_at"], g[-1]["taken_at"], lat, lon)).lastrowid
        covers = {r["id"] for r in sorted(g, key=lambda r: r["blur_score"] or 0, reverse=True)[:3]}
        conn.executemany("INSERT INTO event_photos VALUES (?,?,?)",
                         [(eid, r["id"], 1 if r["id"] in covers else 0) for r in g])
        conn.execute("UPDATE events SET title=? WHERE id=?", (_event_title(conn, eid, g), eid))
    conn.commit()
    return len(groups)


def _event_title(conn, eid, g):
    d = dt.datetime.fromtimestamp(g[0]["taken_at"])
    head = f"{d.month}월 {d.day}일"
    top = conn.execute("""SELECT t.label, COUNT(*) n FROM tags t
                          JOIN event_photos ep ON ep.photo_id = t.photo_id
                          WHERE ep.event_id=? AND t.source IN ('clip','cloud')
                          GROUP BY t.label ORDER BY n DESC LIMIT 2""", (eid,)).fetchall()
    if top:
        return head + " · " + ", ".join(f"{r['label']} {r['n']}장" for r in top)
    return f"{head} · 사진 {len(g)}장"


# ---------- 정리 후보 ----------

def cleanup_report(conn, cfg):
    q = lambda sql, *a: conn.execute(sql, a).fetchall()
    blurry = q("""SELECT id, path, bytes, blur_score FROM photos
                  WHERE thumb_path IS NOT NULL AND blur_score < ?
                  ORDER BY blur_score LIMIT 500""", cfg["blur_threshold"])
    lowq = q("""SELECT id, path, bytes, brightness FROM photos
                WHERE thumb_path IS NOT NULL
                  AND (brightness < ? OR brightness > ? OR MAX(width,height) < ?)
                ORDER BY bytes DESC LIMIT 500""",
             cfg["brightness_dark"], cfg["brightness_blown"], cfg["min_dimension"])
    dupes = q("""SELECT d.group_id, d.kind, d.photo_id, d.is_best, p.path, p.bytes
                 FROM dupe_groups d JOIN photos p ON p.id = d.photo_id
                 ORDER BY d.group_id, d.is_best DESC""")
    return blurry, lowq, dupes


def blur_distribution(conn):
    """흐림 임계값은 카메라·피사체마다 달라서 기본값을 박을 수 없다.
    사용자가 슬라이더를 어디에 둘지 판단하도록 실제 분포를 넘긴다."""
    scores = [r[0] for r in conn.execute(
        "SELECT blur_score FROM photos WHERE thumb_path IS NOT NULL AND blur_score IS NOT NULL")]
    if not scores:
        return {}
    a = np.array(scores)
    return {f"p{p}": round(float(np.percentile(a, p)), 1) for p in (1, 5, 10, 25, 50, 90)}
