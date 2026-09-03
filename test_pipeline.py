"""자체 점검. 프레임워크 없음 — python test_pipeline.py 로 실행.
로직이 깨지면 실패하는 최소한만 검사한다."""
import pathlib, tempfile, datetime as dt
import numpy as np
from PIL import Image

import store, pipeline

CFG = store.load_config()


def img(path, size=(800, 600), seed=0):
    rng = np.random.default_rng(seed)
    Image.fromarray(rng.integers(0, 255, (size[1], size[0], 3), dtype=np.uint8)).save(path)
    return path


def row(conn, pid, taken, vec, blur=100.0):
    conn.execute("""INSERT INTO photos(id,path,taken_at,taken_at_source,blur_score,
                    embedding,thumb_path,indexed_at,width,height,bytes)
                    VALUES (?,?,?,'exif',?,?,'t.jpg',1,10,10,100)""",
                 (pid, f"p{pid}.jpg", taken, blur,
                  (vec / np.linalg.norm(vec)).astype(np.float32).tobytes()))


def main():
    tmp = pathlib.Path(tempfile.mkdtemp())
    pipeline.THUMBS = tmp / "thumbs"

    # 1. EXIF 없는 이미지는 파일 수정시각으로 폴백하고, 그 사실을 기록한다
    p = img(tmp / "plain.png")
    meta, tags, base = pipeline.analyze(p, 1, CFG)
    assert meta["taken_at_source"] == "mtime", meta["taken_at_source"]
    assert meta["taken_at"] > 0
    assert pathlib.Path(meta["thumb_path"]).exists()
    assert meta["width"] == 800 and meta["height"] == 600, (meta["width"], meta["height"])
    # 촬영시각을 모르면 야간 태그를 붙이지 않는다 (mtime의 시각은 촬영시점이 아니다)
    assert "야간" not in [t[0] for t in tags], tags
    print("  ok  EXIF 없음 → mtime 폴백 + 야간 미부여")

    # 2. 흐린 이미지가 선명한 이미지보다 낮은 점수를 받는다
    sharp = pipeline.laplacian_var(np.asarray(base.convert("L"), np.float32))
    blurred = pipeline.laplacian_var(
        np.asarray(base.filter(__import__("PIL.ImageFilter", fromlist=["x"]).GaussianBlur(6))
                   .convert("L"), np.float32))
    assert blurred < sharp, (blurred, sharp)
    print(f"  ok  선명도 판별 (선명 {sharp:.0f} > 흐림 {blurred:.0f})")

    # 3. 규칙 태그: 카메라 없음 + 화면비 16:9 → 스크린샷
    shot = {"camera_model": None, "width": 1080, "height": 1920,
            "taken_at": None, "taken_at_source": "mtime", "gps_lat": None}
    assert "스크린샷" in [t[0] for t in pipeline.rule_tags(shot, CFG)]
    cam = {**shot, "camera_model": "Apple iPhone 15", "width": 4032, "height": 3024}
    assert "카메라촬영" in [t[0] for t in pipeline.rule_tags(cam, CFG)]
    night = {**cam, "taken_at": int(dt.datetime(2026, 8, 1, 23, 0).timestamp()),
             "taken_at_source": "exif"}
    assert "야간" in [t[0] for t in pipeline.rule_tags(night, CFG)]
    print("  ok  규칙 태그 (스크린샷 / 카메라촬영 / 야간)")

    # 4. 동일 파일 복사본 → exact 그룹 1개, 남길 것 1장
    conn = store.connect(tmp / "t.db")
    a, b = img(tmp / "a.jpg", seed=1), img(tmp / "b.jpg", seed=1)
    import shutil; shutil.copy(a, b)
    store.queue_paths(conn, [a, b])
    pipeline.process_batch(conn, CFG)
    n = pipeline.group_dupes(conn, CFG, use_embeddings=False)
    g = conn.execute("SELECT kind, SUM(is_best) best, COUNT(*) n FROM dupe_groups").fetchone()
    assert n == 1 and g["n"] == 2 and g["best"] == 1 and g["kind"] == "exact", dict(g)
    print("  ok  완전중복 SHA-256 그룹핑 (2장 → 1그룹, 남길 것 1장)")

    # 4b. 원본 판별: 사본은 이름이 길어진다. 스캔 순서(id)로 고르면 사본을 남기게 된다.
    for orig, copy in [("cafe_01.jpg", "cafe_01 - 복사본.jpg"),
                       ("trip_01.jpg", "trip_01(1).jpg"),
                       ("IMG_0042.JPG", "IMG_0042 - Copy.JPG"),
                       ("a.png", "a (2).png")]:
        assert pipeline.origin_rank(orig, CFG) < pipeline.origin_rank(copy, CFG), (orig, copy)
    print("  ok  중복 중 원본 판별 (복사본/(1)/Copy 접미사를 사본으로 인식)")

    # 5. 정규화된 임베딩의 자기 내적은 1.0
    v = np.random.default_rng(0).normal(size=512).astype(np.float32)
    v /= np.linalg.norm(v)
    assert abs(float(v @ v) - 1.0) < 1e-5
    print("  ok  L2 정규화 → 내적 = 코사인")

    # 6. 연사 판정에 시간 조건이 실제로 걸려 있는가 (이 프로젝트에서 제일 틀리기 쉬운 곳)
    conn2 = store.connect(tmp / "t2.db")
    base_v = np.random.default_rng(1).normal(size=512)
    t0 = 1_700_000_000
    row(conn2, 1, t0, base_v)
    row(conn2, 2, t0 + 5, base_v + 0.01, blur=200.0)          # 5초 뒤, 거의 동일 → 연사
    row(conn2, 3, t0 + 86400, base_v + 0.01)                  # 하루 뒤, 거의 동일 → 연사 아님
    conn2.commit()
    pipeline.group_dupes(conn2, CFG, use_embeddings=True)
    ids = {r["photo_id"] for r in conn2.execute("SELECT photo_id FROM dupe_groups")}
    assert ids == {1, 2}, f"3번(하루 뒤 유사 사진)이 중복으로 잘못 묶임: {ids}"
    best = conn2.execute("SELECT photo_id FROM dupe_groups WHERE is_best=1").fetchone()[0]
    assert best == 2, f"가장 선명한 사진을 남겨야 하는데 {best}를 골랐다"
    print("  ok  연사 = 유사도 AND 시간 (하루 뒤 유사 사진은 안 묶임, 선명한 쪽을 남김)")

    # 7. 이벤트 분리: 간격이 event_gap_hours를 넘으면 나뉜다
    conn3 = store.connect(tmp / "t3.db")
    gap = CFG["event_gap_hours"] * 3600
    # 간격은 '연속한 두 사진' 사이로 잰다. 세 번째는 두 번째로부터 gap을 넘겨야 한다.
    for i, off in enumerate([0, 600, 600 + gap + 60], start=1):
        row(conn3, i, t0 + off, base_v)
    conn3.commit()
    assert pipeline.build_events(conn3, CFG) == 2
    sizes = sorted(r["n"] for r in conn3.execute(
        "SELECT COUNT(*) n FROM event_photos GROUP BY event_id"))
    assert sizes == [1, 2], sizes
    title = conn3.execute("SELECT title FROM events ORDER BY started_at").fetchone()[0]
    assert "장" in title, title
    print(f"  ok  타임라인 이벤트 분리 (3장 → 2이벤트 {sizes}, 제목 '{title}')")

    # 8. 삭제 가드: 한 중복 그룹을 통째로 지우려 하면 1장은 남긴다
    import app
    # 6번에서 만든 연사 그룹(1,2)을 재사용
    survivors_ids, kept = app._keep_one_per_group({1, 2}, conn2)
    assert kept == [2] and survivors_ids == [1], (survivors_ids, kept)
    partial, kept2 = app._keep_one_per_group({1}, conn2)   # 일부만 지우는 건 그대로 통과
    assert partial == [1] and kept2 == [], (partial, kept2)
    print("  ok  삭제 가드 (그룹 전원 선택 시 1장 보존, 부분 선택은 통과)")

    # 9. 커넥션은 스레드마다 따로. 공유하면 썸네일 병렬 요청에서 InterfaceError로 터진다.
    import concurrent.futures as cf
    store.DB = tmp / "t4.db"
    store.conn().execute("INSERT INTO photos(path) VALUES ('x.jpg')")
    store.conn().commit()

    def hammer(_):
        c = store.conn()
        return id(c), c.execute("SELECT COUNT(*) FROM photos").fetchone()[0]

    with cf.ThreadPoolExecutor(12) as ex:
        got = list(ex.map(hammer, range(120)))          # 예외가 나면 여기서 터진다
    assert {n for _, n in got} == {1}, got
    assert len({cid for cid, _ in got}) > 1, "스레드가 커넥션을 공유하고 있다"
    print(f"  ok  스레드별 커넥션 분리 (12스레드 120회 동시 조회, 커넥션 "
          f"{len({c for c, _ in got})}개)")

    # 10. CLIP 인코더 (모델이 있을 때만)
    import encoder
    enc = encoder.get()
    if enc is None:
        print("  --  CLIP 인코더 건너뜀 (python setup_model.py 미실행)")
    else:
        from PIL import ImageDraw
        v = enc.encode_text("a photo of a dog")
        assert v.shape == (512,) and abs(float(v @ v) - 1.0) < 1e-4, v.shape
        assert not np.allclose(v, enc.encode_text("a scanned receipt")), "다른 문장이 같은 벡터"
        ckey = "categories" + enc.suffix
        assert enc.prompt_matrix(ckey, CFG[ckey])[1].shape[0] == len(CFG[ckey])

        # 글자로 꽉 찬 종이 vs 컬러 사진 → 문서 프롬프트가 종이 쪽에서 더 높아야 한다
        doc = Image.new("RGB", (400, 500), "white")
        dr = ImageDraw.Draw(doc)
        for y in range(20, 480, 18):
            dr.text((20, y), "TOTAL 12,400 KRW   THANK YOU   2026-08-15", fill="black")
        photo = Image.fromarray(np.random.default_rng(3).integers(
            0, 255, (400, 500, 3), dtype=np.uint8))
        V = enc.encode_images([doc, photo])
        t = enc.encode_text("영수증 스캔" if enc.variant == "ko"
                            else "a scanned receipt with printed text")
        assert float(V[0] @ t) > float(V[1] @ t), (float(V[0] @ t), float(V[1] @ t))
        if enc.variant == "ko":
            # 한국어 모델은 번역 없이 한글을 그대로 받는 것이 존재 이유다
            assert enc.translate("횡설수설", CFG) == ("횡설수설", True)
            ko = enc.encode_text("고양이 사진")
            assert not np.allclose(ko, enc.encode_text("영수증")), "한글 문장이 구분되지 않음"
        else:
            en, ok = enc.translate("작년 여름 바다 사진", CFG)
            assert ok and "sea" in en, (en, ok)
            assert enc.translate("비행기", CFG)[0] == "an airplane"  # '비'가 아니라 '비행기'
            assert enc.translate("바다에서", CFG)[0].startswith("the sea")   # 조사 흡수
            assert enc.translate("횡설수설", CFG)[1] is False        # 못 바꾸면 알려야 한다
            assert enc.translate("a red car", CFG)[1] is True       # 영어는 그대로 통과
        print(f"  ok  CLIP 인코더 [{enc.variant}] "
              f"(영수증 {float(V[0]@t):.3f} > 노이즈 {float(V[1]@t):.3f})")

        # 10b. 임베딩은 그 사진만의 함수여야 한다 — 같이 넣은 사진에 좌우되면 안 된다.
        # int8 동적 양자화의 활성값 스케일이 배치 전체에서 나오기 때문에, 배치로
        # 넣으면 스캔 순서만 바뀌어도 중복 그룹과 검색 결과가 바뀐다(실측 11/400장).
        alone = enc.encode_images([doc])[0]
        with_others = enc.encode_images([photo, doc, photo, doc, photo])[1]
        c = float(alone @ with_others)
        assert c > 0.9999, f"배치 구성이 임베딩을 바꿨다 (cos={c:.4f}) — infer_batch를 확인"
        print(f"  ok  임베딩 결정성 (혼자 vs 5장 배치 안, cos={c:.6f})")

    # 11. 모델을 바꾸면 기존 임베딩은 무효다 — 다른 공간의 벡터를 비교하면 결과가 엉킨다
    conn5 = store.connect(tmp / "t5.db")
    conn5.execute("INSERT INTO photos(path,embedding,indexed_at) VALUES ('z.jpg',X'00',1)")
    conn5.commit()
    assert store.check_variant(conn5, "en") == 1        # 첫 기록 → 무효화
    assert store.check_variant(conn5, "en") == 0        # 같은 모델 → 그대로
    conn5.execute("UPDATE photos SET embedding=X'00', indexed_at=1")
    assert store.check_variant(conn5, "ko") == 1        # 모델 교체 → 다시 무효화
    r = conn5.execute("SELECT embedding IS NULL n, indexed_at IS NULL i FROM photos").fetchone()
    assert r["n"] and r["i"], "임베딩과 처리표시가 비워지지 않았다"
    print("  ok  모델 교체 시 기존 임베딩 무효화 (재임베딩 강제)")

    print("\n전부 통과.")


if __name__ == "__main__":
    main()
