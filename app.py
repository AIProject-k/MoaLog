"""로컬 사진 자동분류 앱. python app.py 실행 후 http://localhost:8000"""
import pathlib, threading, datetime as dt
import numpy as np
from fastapi import FastAPI, UploadFile, File, Body
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

import store, pipeline

ROOT = pathlib.Path(__file__).parent
UPLOADS = ROOT / "data" / "uploads"

app = FastAPI(title="Photo Log")
app.mount("/static", StaticFiles(directory=ROOT / "static"), name="static")

STATE = {"running": False, "total": 0, "done": 0, "msg": "대기 중", "clip": False}
_lock = threading.Lock()


def _encoder():
    """CLIP 모델이 없어도 앱은 돈다 — 규칙 태그·타임라인·완전중복은 그대로 동작한다."""
    try:
        import encoder
        enc = encoder.get()
        STATE["clip"] = enc is not None
        return enc
    except Exception as e:
        STATE["clip"] = False
        STATE["msg"] = f"CLIP 미탑재({type(e).__name__}) — 규칙 분류만 동작"
        return None


def _worker():
    conn = store.connect()                      # 워커 전용 커넥션 (스레드 분리)
    cfg = store.load_config()
    enc = _encoder()
    try:
        stale = store.check_variant(conn, enc.variant if enc else "none")
        if stale:
            STATE["msg"] = f"모델이 바뀌어 {stale:,}장을 다시 임베딩합니다…"
        while True:
            n = pipeline.process_batch(conn, cfg, enc)
            STATE["total"], STATE["done"] = store.counts(conn)
            if n == 0:
                break
            STATE["msg"] = f"{STATE['done']:,} / {STATE['total']:,}장 처리 중"
        STATE["msg"] = "중복 그룹핑 중…"
        pipeline.group_dupes(conn, cfg, use_embeddings=enc is not None)
        STATE["msg"] = "타임라인 구성 중…"
        n_ev = pipeline.build_events(conn, cfg)
        failed = conn.execute("SELECT COUNT(*) c FROM photos WHERE indexed_at IS NOT NULL"
                              " AND thumb_path IS NULL").fetchone()["c"]
        STATE["msg"] = (f"완료 · 이벤트 {n_ev}개"
                        + (f" · 읽기 실패 {failed}장" if failed else "")
                        + ("" if enc else " · CLIP 없음(규칙 분류만)"))
    except Exception as e:
        STATE["msg"] = f"오류: {type(e).__name__}: {e}"
    finally:
        STATE["running"] = False
        conn.close()


def _start():
    with _lock:
        if STATE["running"]:
            return False
        STATE.update(running=True, msg="시작 중…")
        threading.Thread(target=_worker, daemon=True).start()
        return True


# ---------- 라우트 ----------

@app.get("/", response_class=HTMLResponse)
def index():
    return (ROOT / "static" / "index.html").read_text(encoding="utf-8")


@app.post("/api/scan")
def scan(folder: str = Body(..., embed=True)):
    p = pathlib.Path(folder.strip().strip('"'))
    if not p.is_dir():
        return JSONResponse({"error": f"폴더를 찾을 수 없습니다: {p}"}, status_code=400)
    n = pipeline.scan_folder(store.conn(), p, store.load_config())
    _start()
    return {"found": n, "started": True}


@app.post("/api/upload")
async def upload(files: list[UploadFile] = File(...)):
    UPLOADS.mkdir(parents=True, exist_ok=True)
    saved = []
    for f in files:
        dest = UPLOADS / pathlib.Path(f.filename).name
        i = 1
        while dest.exists():                     # 이름 충돌 시 덮어쓰지 않는다
            dest = UPLOADS / f"{pathlib.Path(f.filename).stem}_{i}{dest.suffix}"
            i += 1
        dest.write_bytes(await f.read())
        saved.append(dest)
    store.queue_paths(store.conn(), saved)
    _start()
    return {"saved": len(saved), "started": True}


@app.get("/api/status")
def status():
    STATE["total"], STATE["done"] = store.counts(store.conn())
    return STATE


@app.get("/api/tags")
def tags():
    return [{"label": r["label"], "n": r["n"]} for r in store.all_tags(store.conn())]


def _card(r):
    ts = r["taken_at"]
    return {"id": r["id"], "name": pathlib.Path(r["path"]).name,
            "when": dt.datetime.fromtimestamp(ts).strftime("%Y.%m.%d %H:%M") if ts else "날짜미상",
            "exact_date": r["taken_at_source"] == "exif",
            "wh": f"{r['width']}×{r['height']}", "bytes": r["bytes"]}


@app.get("/api/photos")
def photos(tags: str = "", order: str = "new", limit: int = 200, offset: int = 0):
    tl = [t for t in tags.split(",") if t]
    return [_card(r) for r in store.query_photos(store.conn(), tl, order, limit, offset)]


@app.get("/api/timeline")
def timeline(limit: int = 100):
    out = []
    for e in store.conn().execute("SELECT * FROM events ORDER BY started_at DESC LIMIT ?", (limit,)):
        ph = store.conn().execute("""SELECT photo_id, is_cover FROM event_photos
                             WHERE event_id=? ORDER BY is_cover DESC""", (e["id"],)).fetchall()
        out.append({
            "id": e["id"], "title": e["title"], "count": len(ph),
            "covers": [r["photo_id"] for r in ph if r["is_cover"]][:3],
            "span": dt.datetime.fromtimestamp(e["started_at"]).strftime("%H:%M") + "–"
                    + dt.datetime.fromtimestamp(e["ended_at"]).strftime("%H:%M"),
            "gps": None if e["gps_lat"] is None else f"{e['gps_lat']:.4f}, {e['gps_lon']:.4f}",
            "place": e["place_name"],
        })
    return out


@app.get("/api/cleanup")
def cleanup():
    cfg = store.load_config()
    blurry, lowq, dupes = pipeline.cleanup_report(store.conn(), cfg)
    groups = {}
    for d in dupes:
        groups.setdefault(d["group_id"], {"kind": d["kind"], "items": []})["items"].append(
            {"id": d["photo_id"], "best": bool(d["is_best"]), "bytes": d["bytes"],
             "name": pathlib.Path(d["path"]).name})
    return {
        "dupes": list(groups.values()),
        "dupe_bytes": sum(d["bytes"] or 0 for d in dupes if not d["is_best"]),
        "blurry": [{"id": r["id"], "name": pathlib.Path(r["path"]).name,
                    "bytes": r["bytes"], "score": round(r["blur_score"], 1)} for r in blurry],
        "lowq": [{"id": r["id"], "name": pathlib.Path(r["path"]).name,
                  "bytes": r["bytes"], "score": round(r["brightness"], 1)} for r in lowq],
        "thresholds": {k: cfg[k] for k in
                       ("blur_threshold", "brightness_dark", "brightness_blown", "min_dimension")},
        "dist": pipeline.blur_distribution(store.conn()),
    }


@app.post("/api/trash")
def trash(ids: list[int] = Body(..., embed=True)):
    """삭제하지 않는다 — 휴지통으로만 보낸다. 잘못 지운 사진은 돌아오지 않는다."""
    from send2trash import send2trash
    ids, kept = _keep_one_per_group(set(ids), store.conn())
    moved, failed = [], []
    for pid in ids:
        row = store.photo(store.conn(), pid)
        if not row:
            continue
        try:
            send2trash(str(pathlib.Path(row["path"])))
            moved.append(pid)
        except Exception as e:
            failed.append({"id": pid, "why": str(e)})
    store.delete_photos(store.conn(), moved)
    for pid in moved:
        pathlib.Path(pipeline.THUMBS / f"{pid}.jpg").unlink(missing_ok=True)
    return {"moved": len(moved), "failed": failed, "kept": kept}


def _keep_one_per_group(want, c):
    """한 중복 그룹의 사본을 전부 지우려 하면 1장은 남긴다.
    중복 정리는 '한 장만 남기기'지 '사진을 없애기'가 아니다."""
    groups = {}
    for r in c.execute("SELECT group_id, photo_id, is_best FROM dupe_groups"):
        groups.setdefault(r["group_id"], []).append((r["photo_id"], r["is_best"]))
    kept = []
    for members in groups.values():
        pids = {p for p, _ in members}
        if pids and pids <= want:                    # 그룹 전원이 삭제 대상
            survivor = next((p for p, b in members if b), min(pids))
            want.discard(survivor)
            kept.append(survivor)
    return list(want), kept


@app.post("/api/config")
def set_config(patch: dict = Body(...)):
    cfg = store.load_config()
    allowed = {"blur_threshold", "brightness_dark", "brightness_blown", "min_dimension",
               "dupe_similarity", "burst_gap_sec", "event_gap_hours",
               "classify_min_score", "attr_tag_threshold", "attr_tag_threshold_ko"}
    for k, v in patch.items():
        if k in allowed:
            cfg[k] = float(v) if isinstance(cfg[k], float) else int(v)
    store.save_config(cfg)
    return {k: cfg[k] for k in allowed}


@app.post("/api/search")
def search(q: str = Body(..., embed=True), limit: int = Body(120, embed=True)):
    enc = _encoder()
    if enc is None:
        return JSONResponse({"error": "CLIP 모델이 없어 검색을 쓸 수 없습니다. "
                                      "python setup_model.py 를 먼저 실행하세요."}, status_code=503)
    rows = store.conn().execute("SELECT id, embedding FROM photos WHERE embedding IS NOT NULL").fetchall()
    if not rows:
        return JSONResponse({"error": "임베딩이 있는 사진이 없습니다. 다시 스캔하세요."},
                            status_code=503)
    en, ok = enc.translate(q, store.load_config())
    mat = np.stack([np.frombuffer(r["embedding"], dtype=np.float32) for r in rows])
    sims = mat @ enc.encode_text(en)                     # 정규화 저장 → 내적 = 코사인
    order = np.argsort(-sims)[:limit]
    ids = [rows[i]["id"] for i in order]
    cards = [_card(r) for r in store.query_photos(store.conn(), ids=ids, limit=limit)]
    score = {rows[i]["id"]: float(sims[i]) for i in order}
    for c in cards:
        c["score"] = round(score[c["id"]], 3)
    # 번역이 안 되면 CLIP에 한글이 그대로 들어가 결과가 '그럴듯하지만 무의미'해진다.
    # 조용히 넘기면 사용자가 그걸 알 방법이 없으므로 반드시 알린다.
    return {"query": en, "translated": ok, "results": cards,
            "warning": None if ok else
            f"'{q}'를 영어로 바꾸지 못했습니다 — 결과가 부정확합니다. "
            f"config.json의 ko_dict에 단어를 추가하세요."}


@app.get("/thumb/{pid}")
def thumb(pid: int):
    row = store.photo(store.conn(), pid)
    if not row or not row["thumb_path"] or not pathlib.Path(row["thumb_path"]).exists():
        return JSONResponse({"error": "no thumb"}, status_code=404)
    return FileResponse(row["thumb_path"], media_type="image/jpeg")


@app.get("/photo/{pid}")
def full(pid: int):
    row = store.photo(store.conn(), pid)
    if not row or not pathlib.Path(row["path"]).exists():
        return JSONResponse({"error": "not found"}, status_code=404)
    return FileResponse(row["path"])


@app.get("/api/detail/{pid}")
def detail(pid: int):
    row = store.photo(store.conn(), pid)
    if not row:
        return JSONResponse({"error": "not found"}, status_code=404)
    tags = store.conn().execute("SELECT label, score, source FROM tags WHERE photo_id=?"
                        " ORDER BY source, score DESC", (pid,)).fetchall()
    return {"path": row["path"], "wh": f"{row['width']}×{row['height']}",
            "bytes": row["bytes"], "camera": row["camera_model"],
            "when": dt.datetime.fromtimestamp(row["taken_at"]).strftime("%Y.%m.%d %H:%M")
                    if row["taken_at"] else "날짜미상",
            "when_src": row["taken_at_source"],
            "gps": None if row["gps_lat"] is None else f"{row['gps_lat']:.5f}, {row['gps_lon']:.5f}",
            "blur": round(row["blur_score"] or 0, 1), "bright": round(row["brightness"] or 0, 1),
            "tags": [{"label": t["label"], "score": t["score"], "source": t["source"]} for t in tags]}


if __name__ == "__main__":
    import uvicorn, webbrowser
    webbrowser.open("http://localhost:8000")
    uvicorn.run(app, host="127.0.0.1", port=8000, log_level="warning")
