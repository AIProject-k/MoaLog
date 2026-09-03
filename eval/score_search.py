"""한국어 자연어 검색 정확도 측정. python eval/score_search.py [en|ko]

분류(score.py)는 내가 미리 써 둔 프롬프트를 쓰지만, 검색은 사용자가 아무 말이나 친다.
그래서 따로 잰다 — 카테고리마다 한국어 질의를 던져 상위 10장 중 몇 장이
그 카테고리인지(P@10) 본다.

en 모델은 ko_dict 번역을 거치고, ko 모델은 한국어를 그대로 받는다.
사전에 없는 말(마지막 세 줄)이 진짜 차이가 드러나는 곳이다.
"""
import csv, pathlib, sys
import numpy as np
from PIL import Image

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
import store, encoder                                            # noqa: E402

ROOT = pathlib.Path(__file__).parent

# (질의, 정답 카테고리) — 앞쪽은 사전에 있는 쉬운 말, 뒤로 갈수록 사전에 없는 자연스러운 말
QUERIES = [
    ("고양이",                    "반려동물"),
    ("강아지 사진",               "반려동물"),
    ("맛있는 음식",               "음식"),
    ("영수증",                    "문서/영수증"),
    ("바다",                      "풍경/여행"),
    ("산 풍경",                   "풍경/여행"),
    ("사람 얼굴",                 "인물"),
    ("거실 인테리어",             "실내일상"),
    ("컴퓨터 화면 캡처",          "화면캡처내용"),
    ("신발",                      "상품/쇼핑"),
    # --- 아래는 ko_dict에 통째로는 없는 자연스러운 문장 ---
    ("친구들이랑 같이 찍은 사진", "인물"),
    ("눈 내린 겨울 풍경",         "풍경/여행"),
    ("접시에 담긴 요리",          "음식"),
    ("종이에 적힌 글씨",          "문서/영수증"),
    ("웹사이트 화면",             "화면캡처내용"),
]


def main(variant=None, k=10):
    enc = encoder.get(variant)
    if enc is None:
        print("모델이 없습니다.")
        return 1
    cfg = store.load_config()
    rows = list(csv.DictReader(open(ROOT / "labels.csv", encoding="utf-8")))
    rows = [r for r in rows if (ROOT / r["path"]).exists()]
    if not rows:
        print("평가셋이 없습니다.")
        return 1

    V = []
    for i in range(0, len(rows), 32):
        V.append(enc.encode_images([Image.open(ROOT / r["path"]) for r in rows[i:i + 32]]))
    V = np.concatenate(V)
    truth = [r["category"] for r in rows]

    print(f"{len(rows)}장 · 모델 {enc.variant} · P@{k}\n")
    print(f"{'질의':26s} {'번역/입력':38s} {'P@'+str(k):>6s}")
    print("-" * 74)
    scores = []
    for q, gold in QUERIES:
        text, ok = enc.translate(q, cfg)
        sims = V @ enc.encode_text(text)
        top = np.argsort(-sims)[:k]
        hit = sum(1 for i in top if truth[i] == gold)
        scores.append(hit / k)
        mark = "" if ok else "  ← 번역실패"
        print(f"{q:26s} {text[:36]:38s} {hit/k:6.1f}{mark}")
    print("-" * 74)
    easy, hard = np.mean(scores[:10]), np.mean(scores[10:])
    print(f"평균 P@{k}  전체 {np.mean(scores):.2f}  ·  사전에 있는 말 {easy:.2f}  "
          f"·  사전에 없는 말 {hard:.2f}")
    return 0


if __name__ == "__main__":
    sys.exit(main(variant=sys.argv[1] if len(sys.argv) > 1 else None))
