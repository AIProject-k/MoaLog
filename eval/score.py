"""제로샷 분류 정확도 측정. M1의 관문.

    python eval/score.py

eval/labels.csv(검색어 = 정답 라벨)로 카테고리별 precision/recall/F1과
혼동행렬을 낸다. 여기 숫자가 안 나오면 프롬프트를 먼저 고친다 — 모델 교체는 마지막.
"""
import csv, pathlib, sys, time
import numpy as np
from PIL import Image

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
import store, encoder                                            # noqa: E402

ROOT = pathlib.Path(__file__).parent


def load():
    with open(ROOT / "labels.csv", encoding="utf-8") as f:
        rows = [r for r in csv.DictReader(f)]
    return [(ROOT / r["path"], r["category"]) for r in rows
            if (ROOT / r["path"]).exists()]


def main(batch=32, variant=None):
    enc = encoder.get(variant)
    if enc is None:
        print("모델이 없습니다. python setup_model.py 를 먼저 실행하세요.")
        return 1
    cfg = store.load_config()
    data = load()
    if not data:
        print("평가셋이 없습니다. python eval/fetch_dataset.py 를 먼저 실행하세요.")
        return 1
    cats = list(cfg["categories"])
    print(f"{len(data)}장 · {len(cats)}분류 · 임계값 {cfg['classify_min_score']}\n")

    truth, pred, allpred, unsure = [], [], [], 0
    t0 = time.time()
    for i in range(0, len(data), batch):
        chunk = data[i:i + batch]
        imgs = []
        for p, _ in chunk:
            try:
                imgs.append(Image.open(p))
            except Exception:
                imgs.append(Image.new("RGB", (224, 224)))
        V = enc.encode_images(imgs)
        for (p, gt), v in zip(chunk, V):
            # 앱과 똑같은 경로로 분류한다. 여기서 로직을 복제하면 앱이 아닌 걸 재게 된다.
            got = [t[0] for t in enc.classify(v, cfg) if t[0] in cats or t[0] == "미분류"]
            unsure += got[0] == "미분류"
            pred.append(got[0])
            allpred.append(got)
            truth.append(gt)
    dt = time.time() - t0
    print(f"추론 {dt:.1f}s ({dt/len(data)*1000:.0f}ms/장)\n")

    # ---- 카테고리별 지표 ----
    print(f"{'분류':14s} {'정답수':>5s} {'맞춤':>5s} {'precision':>10s} {'recall':>8s} {'F1':>7s}")
    print("-" * 56)
    f1s = []
    for c in cats:
        tp = sum(1 for t, q in zip(truth, pred) if t == c and q == c)
        fp = sum(1 for t, q in zip(truth, pred) if t != c and q == c)
        fn = sum(1 for t, q in zip(truth, pred) if t == c and q != c)
        n = tp + fn
        pr = tp / (tp + fp) if tp + fp else 0.0
        rc = tp / n if n else 0.0
        f1 = 2 * pr * rc / (pr + rc) if pr + rc else 0.0
        f1s.append(f1)
        print(f"{c:14s} {n:5d} {tp:5d} {pr:10.2f} {rc:8.2f} {f1:7.2f}")
    acc = sum(1 for t, q in zip(truth, pred) if t == q) / len(truth)
    # 멀티라벨: 정답이 붙은 태그들 안에 있으면 사용자가 그 앨범에서 찾을 수 있다.
    # 태그를 많이 붙일수록 공짜로 오르는 값이라 사진당 평균 태그 수를 반드시 같이 본다.
    cover = sum(1 for t, g in zip(truth, allpred) if t in g) / len(truth)
    ntags = np.mean([len([x for x in g if x != "미분류"]) for g in allpred])
    print("-" * 56)
    print(f"{'1순위 정확도':14s} {acc:.2f}   ·  미분류 {unsure}장 "
          f"({unsure/len(truth)*100:.0f}%)  ·  평균 F1 {np.mean(f1s):.2f}")
    print(f"{'태그 안에 포함':14s} {cover:.2f}   ·  사진당 태그 평균 {ntags:.2f}개"
          f"  (많이 붙일수록 오르니 같이 볼 것)\n")

    # ---- 혼동행렬 (행=정답, 열=예측) ----
    idx = {c: i for i, c in enumerate(cats + ["미분류"])}
    M = np.zeros((len(cats), len(cats) + 1), int)
    for t, q in zip(truth, pred):
        M[idx[t], idx[q]] += 1
    head = "".join(f"{c[:4]:>6s}" for c in cats) + f"{'미분류':>6s}"
    print("혼동행렬 (행=정답, 열=예측)")
    print(f"{'':14s}{head}")
    for c in cats:
        row = "".join(f"{v:6d}" if v else f"{'·':>6s}" for v in M[idx[c]])
        print(f"{c:14s}{row}")

    worst = sorted(zip(cats, f1s), key=lambda x: x[1])[:3]
    print("\n가장 약한 분류: " + ", ".join(f"{c}(F1 {v:.2f})" for c, v in worst))
    print("→ 먼저 config.json의 해당 프롬프트를 고쳐 볼 것. 모델 교체는 마지막 수단.")
    return 0


if __name__ == "__main__":
    # python eval/score.py [en|ko]  — 인자가 없으면 config의 model_variant를 쓴다
    sys.exit(main(variant=sys.argv[1] if len(sys.argv) > 1 else None))
