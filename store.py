"""SQLite 저장소. ORM 없음 — 스키마를 안드로이드 Room으로 그대로 옮기기 위함."""
import sqlite3, json, pathlib, threading

DB = pathlib.Path(__file__).parent / "data" / "photos.db"

SCHEMA = """
CREATE TABLE IF NOT EXISTS photos (
  id INTEGER PRIMARY KEY,
  path TEXT UNIQUE NOT NULL,
  sha256 TEXT,
  bytes INTEGER,
  taken_at INTEGER,              -- unix seconds
  taken_at_source TEXT,          -- 'exif' | 'mtime' | 'none'
  gps_lat REAL, gps_lon REAL,
  width INTEGER, height INTEGER,
  camera_model TEXT,
  blur_score REAL, brightness REAL,
  embedding BLOB,                -- float32, L2 정규화 완료 상태 (M1)
  thumb_path TEXT,
  indexed_at INTEGER             -- NULL이면 미처리 → 재시작 시 여기부터 재개
);
CREATE INDEX IF NOT EXISTS ix_photos_pending ON photos(indexed_at) WHERE indexed_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_photos_taken  ON photos(taken_at);
CREATE INDEX IF NOT EXISTS ix_photos_sha    ON photos(sha256);

CREATE TABLE IF NOT EXISTS tags (
  photo_id INTEGER NOT NULL REFERENCES photos(id) ON DELETE CASCADE,
  label TEXT NOT NULL,
  score REAL,
  source TEXT NOT NULL,          -- 'rule' | 'clip' | 'cloud'
  PRIMARY KEY (photo_id, label)
);
CREATE INDEX IF NOT EXISTS ix_tags_label ON tags(label);

CREATE TABLE IF NOT EXISTS events (
  id INTEGER PRIMARY KEY,
  started_at INTEGER, ended_at INTEGER,
  gps_lat REAL, gps_lon REAL,
  place_name TEXT, title TEXT, summary TEXT
);
CREATE TABLE IF NOT EXISTS event_photos (
  event_id INTEGER NOT NULL REFERENCES events(id) ON DELETE CASCADE,
  photo_id INTEGER NOT NULL REFERENCES photos(id) ON DELETE CASCADE,
  is_cover INTEGER DEFAULT 0,
  PRIMARY KEY (event_id, photo_id)
);

CREATE TABLE IF NOT EXISTS meta (
  k TEXT PRIMARY KEY, v TEXT
);
CREATE TABLE IF NOT EXISTS dupe_groups (
  group_id INTEGER NOT NULL,
  photo_id INTEGER NOT NULL REFERENCES photos(id) ON DELETE CASCADE,
  kind TEXT NOT NULL,            -- 'exact' | 'burst'
  is_best INTEGER DEFAULT 0,
  PRIMARY KEY (group_id, photo_id)
);
"""


def connect(path=None):
    conn = sqlite3.connect(path or DB, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute("PRAGMA journal_mode=WAL")
    conn.executescript(SCHEMA)
    return conn


_local = threading.local()


def conn():
    """스레드마다 커넥션 하나. 절대 공유하지 않는다.

    check_same_thread=False는 '검사를 끄는' 옵션이지 동시 사용을 안전하게
    만들어 주는 옵션이 아니다. FastAPI는 sync 엔드포인트를 스레드풀에서 돌리므로
    커넥션을 공유하면 썸네일 병렬 요청 같은 데서 InterfaceError로 터진다.
    WAL 모드라 커넥션이 여러 개여도 읽기/쓰기가 서로 막지 않는다."""
    c = getattr(_local, "c", None)
    if c is None:
        c = _local.c = connect()
    return c


def load_config():
    p = pathlib.Path(__file__).parent / "config.json"
    return json.loads(p.read_text(encoding="utf-8"))


def save_config(cfg):
    p = pathlib.Path(__file__).parent / "config.json"
    p.write_text(json.dumps(cfg, ensure_ascii=False, indent=2), encoding="utf-8")


def check_variant(conn, variant):
    """모델을 바꾸면 기존 임베딩은 못 쓴다 — 다른 공간의 벡터라 유사도가 무의미해진다.
    바뀌었으면 전부 미처리로 되돌려 다시 임베딩하게 한다 (400장에 9초)."""
    row = conn.execute("SELECT v FROM meta WHERE k='model_variant'").fetchone()
    old = row["v"] if row else None
    if old == variant:
        return 0
    n = conn.execute("SELECT COUNT(*) c FROM photos WHERE embedding IS NOT NULL").fetchone()["c"]
    if n:
        conn.execute("UPDATE photos SET embedding=NULL, indexed_at=NULL")
    conn.execute("INSERT OR REPLACE INTO meta(k,v) VALUES ('model_variant',?)", (variant,))
    conn.commit()
    return n


def queue_paths(conn, paths):
    """새 경로만 큐잉. path UNIQUE라 재스캔해도 중복되지 않는다."""
    cur = conn.executemany("INSERT OR IGNORE INTO photos(path) VALUES (?)",
                           [(str(p),) for p in paths])
    conn.commit()
    return cur.rowcount


def pending(conn, limit):
    return conn.execute(
        "SELECT id, path FROM photos WHERE indexed_at IS NULL ORDER BY id LIMIT ?",
        (limit,)).fetchall()


def counts(conn):
    row = conn.execute(
        "SELECT COUNT(*) t, COUNT(indexed_at) d FROM photos").fetchone()
    return row["t"], row["d"]


def save_photo(conn, pid, meta, tags):
    conn.execute("""UPDATE photos SET sha256=:sha256, bytes=:bytes, taken_at=:taken_at,
        taken_at_source=:taken_at_source, gps_lat=:gps_lat, gps_lon=:gps_lon,
        width=:width, height=:height, camera_model=:camera_model,
        blur_score=:blur_score, brightness=:brightness, embedding=:embedding,
        thumb_path=:thumb_path, indexed_at=strftime('%s','now') WHERE id=:id""",
        {**meta, "id": pid})
    conn.execute("DELETE FROM tags WHERE photo_id=?", (pid,))
    conn.executemany("INSERT OR REPLACE INTO tags(photo_id,label,score,source) VALUES (?,?,?,?)",
                     [(pid, l, s, src) for l, s, src in tags])


def mark_failed(conn, pid):
    """열 수 없는 파일도 indexed_at을 채워 무한 재시도를 막는다."""
    conn.execute("UPDATE photos SET indexed_at=strftime('%s','now'),"
                 " taken_at_source='none' WHERE id=?", (pid,))


def all_tags(conn):
    return conn.execute(
        "SELECT label, COUNT(*) n FROM tags GROUP BY label ORDER BY n DESC").fetchall()


def query_photos(conn, tags=None, order="new", limit=200, offset=0, ids=None):
    where, args = ["indexed_at IS NOT NULL", "thumb_path IS NOT NULL"], []
    if tags:
        where.append("id IN (SELECT photo_id FROM tags WHERE label IN (%s)"
                     " GROUP BY photo_id HAVING COUNT(DISTINCT label)=?)"
                     % ",".join("?" * len(tags)))
        args += list(tags) + [len(tags)]
    if ids is not None:
        if not ids:
            return []
        where.append("id IN (%s)" % ",".join("?" * len(ids)))
        args += list(ids)
    sql = ("SELECT id, path, taken_at, taken_at_source, width, height, bytes, blur_score "
           "FROM photos WHERE " + " AND ".join(where))
    if ids is not None:
        # 검색 결과는 호출 측이 정한 순서(유사도)를 유지한다
        rows = conn.execute(sql, args).fetchall()
        rank = {i: n for n, i in enumerate(ids)}
        return sorted(rows, key=lambda r: rank[r["id"]])[offset:offset + limit]
    sql += " ORDER BY taken_at IS NULL, taken_at " + ("DESC" if order == "new" else "ASC")
    sql += " LIMIT ? OFFSET ?"
    return conn.execute(sql, args + [limit, offset]).fetchall()


def photo(conn, pid):
    return conn.execute("SELECT * FROM photos WHERE id=?", (pid,)).fetchone()


def delete_photos(conn, ids):
    conn.executemany("DELETE FROM photos WHERE id=?", [(i,) for i in ids])
    conn.commit()
