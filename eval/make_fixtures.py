"""데스크톱-안드로이드 패리티 계약을 만든다.  python eval/make_fixtures.py

    python eval/make_fixtures.py            # 생성 + 자체 검증
    python eval/make_fixtures.py --verify   # 생성 없이 지금 코드가 계약을 지키는지만 확인

안드로이드 구현이 맞는지 아닌지를 가르는 기준선이다. 여기 든 숫자가 폰에서 그대로
나오면 데스크톱에서 잰 정확도(0.86)가 폰의 정확도다. 안 나오면 폰이 다른 모델을
돌리고 있는 것이고, 그건 에러가 아니라 '그럴듯한 오답'으로 나타난다.

만드는 것 (eval/fixtures/):
    manifest.json    모델 sha256 · 전처리 파라미터 · 판정 기준
    tokens_ko.json   한국어 문장 → 토큰 id     (정확히 일치해야 함)
    texts_ko.json    문장 → 512차원 임베딩     (코사인)
    images/*.jpg     사진 64장 + 임베딩(images_ko.bin) + 1순위 라벨
    prompts_*.bin    카테고리·속성 프롬프트 행렬 (분류 로직 대조용)
    prep_ko.bin      데스크톱이 CLIP에 넣은 224 CHW 텐서 8장 (전처리 vs 모델 분리용)
    tokenizer/       폰이 읽을 vocab.txt · merges.txt · config.json(정규식 포함)
    ATTRIBUTION.csv  그 64장의 출처·라이선스

--verify는 데스크톱 자신을 계약과 대조한다. _prep이나 모델을 건드렸을 때
조용히 어긋나는 것을 여기서 잡는다.
"""
import argparse, csv, hashlib, json, pathlib, shutil, sys
import numpy as np
from PIL import Image

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent.parent))
import store, encoder                                            # noqa: E402

ROOT = pathlib.Path(__file__).parent
OUT = ROOT / "fixtures"
PER_CATEGORY = 8                      # 8분류 × 8장 = 64장

# 폰과 데스크톱이 같다고 말하려면 이만큼은 같아야 한다.
# 토큰은 정수라 '거의 같다'가 없다 — 하나만 달라도 다른 문장이 된다.
TOKENS_MUST_MATCH_EXACTLY = True
COS_MIN = 0.96                        # Phase 0 실측으로 확정 (2026-09-02). 아래 참조.
LABEL_MIN = 62                        # 64장 중 1순위 라벨이 같아야 하는 장수 — 진짜 기준

# COS_MIN이 0.96인 이유 (Android 15 / API 35 실측, 사진 64장):
#
#   전처리만  이 기기 전처리 vs 데스크톱 텐서 : cos 1.000000, 최대 픽셀차 0.015
#   모델만    데스크톱 텐서 → 이 기기 ONNX    : cos 1.000000
#   종단간    사진 파일 → 임베딩              : 중앙 0.992, 최소 0.970
#   1순위 라벨                                : 64/64 일치
#
#   앞의 두 줄이 1.000000이므로 **우리 코드에는 차이가 없다.** 종단간에 남는 0.97은
#   JPEG 디코더 차이다 — 최악 사진은 4:2:0 크로마 서브샘플링이고, libjpeg(PIL)의
#   fancy upsampling과 Skia(안드로이드)의 업샘플링이 다르다. 우리가 고칠 수 있는
#   코드가 아니고, int8 동적 양자화가 그 작은 입력 차이를 증폭한다.
#
#   그래서 코사인 기준은 실측 최소(0.970) 아래로 여유를 두어 0.96으로 두고,
#   **판정은 LABEL_MIN에 맡긴다.** 코사인 0.99와 0.97의 차이는 사용자가 볼 수 없지만
#   라벨이 뒤집히면 보인다. 라벨이 64/64로 맞으므로 데스크톱 튜닝값이 그대로 유효하다.
#   이 숫자들이 나빠지면 그건 새 회귀지 이 기준의 문제가 아니다.


# ---------- 토큰 계약 ----------

def korean_corpus(cfg):
    """토크나이저를 흔드는 문장들. 실제로 앱에 들어오는 것 + 깨지기 쉬운 것."""
    out = []
    for key in ("categories_ko", "attributes_ko", "categories", "attributes"):
        out += [p for v in cfg.get(key, {}).values() for p in v]
    out += list(cfg.get("ko_dict", {}))                       # 사전 표제어
    out += ["고양이", "강아지 사진", "맛있는 음식", "영수증", "바다", "산 풍경", "사람 얼굴",
            "거실 인테리어", "컴퓨터 화면 캡처", "신발", "친구들이랑 같이 찍은 사진",
            "눈 내린 겨울 풍경", "접시에 담긴 요리", "종이에 적힌 글씨", "웹사이트 화면",
            "작년 여름 강릉에서 먹은 회", "카톡으로 받은 계좌번호 캡처", "제주도 여행"]

    # 조사는 한국어 검색의 기본이다. 앞부분 일치 방식이 여기서 갈린다.
    for stem in ["바다", "꽃", "고양이", "제주도", "회사", "학교"]:
        out += [stem + j for j in ("", "에서", "의", "를", "은", "이랑", "까지", "에게")]

    # 숫자 — ORT-extensions CLIPTokenizer가 여기서 깨진다 (아래 check_ort_tokenizer 참조)
    out += ["3월", "12월", "2026년", "2026년 1월 영수증", "15000원", "iPhone 15 Pro",
            "3.5인치", "제2형", "1 2 3", "010-1234-5678", "오후 3시 30분"]

    # 유니코드 공백 — 정규화가 이걸 U+0020으로 접어야 한다. 자바의 \\s는 ASCII만
    # 잡고 안드로이드(ICU)는 더 잡아서, 여기를 계약에 넣어 두지 않으면 폰과 갈린다.
    # (실제로 갈렸다: UNICODE_CHARACTER_CLASS 플래그가 안드로이드에서 예외를 던진다)
    out += ["전각\u3000공백", "NBSP\u00a0공백", "탭\t사이", "줄\n바꿈",
            "\u2009얇은\u2009공백\u2009", "\u200b제로폭"]

    # 섞임·잡음
    out += ["a red car", "Seoul 2026", "카페 latte 사진", "😀 이모지 섞임 🌊", "🐈",
            "ㅋㅋㅋㅋ", "ㅠㅠ", "  공백   많은  문장  ", "!!!???", "···", "―", "…",
            "", " ", "가", "한글과English가 섞인 문장", "０１２ 전각숫자", "ﾊﾝｶｸ",
            "긴 문장 " * 30]                                   # 77토큰 잘림 경계

    seen, uniq = set(), []
    for t in out:
        if t not in seen:
            seen.add(t); uniq.append(t)
    return uniq


def check_ort_tokenizer(texts, hf_ids):
    """models/tokenizer_ko.onnx(ORT-extensions CLIPTokenizer)가 계약을 지키는지 본다.

    2026-09-02 실측: **지키지 못한다.** 연속된 숫자 2자리 이상에서 갈린다.
    ko tokenizer.json의 사전 분할 정규식은 숫자를 한 자씩 끊지만([\\p{N}]),
    그 op은 CLIP 원본 정규식([\\p{N}]+)을 C++에 박아 두고 숫자를 묶는다.
    "2026" → HF ['2</w>','0</w>','2</w>','6</w>'] vs ORT ['2','0','2','6</w>'].
    op에 정규식 속성이 없어(vocab/merges뿐) 재export로도 못 고친다.

    그래서 안드로이드는 Kotlin BPE를 직접 쓴다 (ANDROID.md §7.3 Option 2).
    그 구현은 tokenizer_ko.json에 적힌 정규식을 읽어야 한다 — 교과서 CLIP
    정규식을 그대로 쓰면 똑같은 함정에 빠진다. 이 함수가 그 증거를 남긴다.
    """
    try:
        import onnxruntime as ort
        from onnxruntime_extensions import get_library_path
    except ImportError:
        return {"available": False, "reason": "onnxruntime_extensions 미설치"}
    p = encoder.MODELS / "tokenizer_ko.onnx"
    if not p.exists():
        return {"available": False, "reason": "tokenizer_ko.onnx 없음"}
    so = ort.SessionOptions(); so.register_custom_ops_library(get_library_path())
    sess = ort.InferenceSession(str(p), so, providers=["CPUExecutionProvider"])
    bad = [t for t, want in zip(texts, hf_ids)
           if sess.run(None, {"input_text": np.array([t])})[0][0].tolist() != want]
    return {"available": True, "total": len(texts), "mismatch": len(bad),
            "examples": bad[:8],
            "verdict": "사용 가능" if not bad else "사용 불가 — Kotlin BPE로 갈 것"}


# ---------- 폰이 읽을 토크나이저 자산 ----------

def emit_tokenizer_assets(variant):
    """tokenizer.json을 평문 vocab/merges로 풀어 놓는다.

    폰에서 4.3MB JSON을 파싱할 이유가 없다. 줄 단위 텍스트면 BufferedReader로 읽히고
    JSON 라이브러리가 필요 없다. 토큰은 바이트 레벨로 인코딩돼 있어 개행이 들어갈 수
    없으므로(0x0A는 'Ċ'로 매핑된다) 한 줄에 하나씩 안전하게 담긴다.

    정규식·특수토큰도 함께 적는다 — 안드로이드 구현이 교과서 CLIP 정규식을 쓰지 않고
    **이 파일에 적힌 것**을 쓰게 하기 위함이다. 그 차이가 ORT-extensions op을
    쓸 수 없게 만든 바로 그 지점이다(check_ort_tokenizer 참조).
    """
    NL = chr(10)
    tokjson = json.loads(encoder.paths(variant)[2].read_text(encoding="utf-8"))
    model, out = tokjson["model"], OUT / "tokenizer"
    out.mkdir(parents=True, exist_ok=True)

    vocab = model["vocab"]
    inv = [None] * (max(vocab.values()) + 1)
    for tok, i in vocab.items():
        inv[i] = tok
    assert all(t and NL not in t for t in inv), "vocab에 줄 단위로 못 담는 토큰이 있다"
    (out / "vocab.txt").write_text(NL.join(inv), encoding="utf-8")

    merges = ["%s %s" % (a, b) if isinstance(a, str) and not isinstance(a, list) else " ".join(a)
              for a, b in ((m if isinstance(m, list) else m.split(" ", 1)) for m in model["merges"])]
    (out / "merges.txt").write_text(NL.join(merges), encoding="utf-8")

    pre = tokjson["pre_tokenizer"]["pretokenizers"][0]
    cfg = {
        "split_regex": pre["pattern"]["Regex"],
        "normalize": [n["type"] for n in tokjson["normalizer"]["normalizers"]],
        "end_of_word_suffix": model.get("end_of_word_suffix"),
        "bos_id": vocab["<|startoftext|>"], "eos_id": vocab["<|endoftext|>"],
        "unk_token": model.get("unk_token"), "ctx": encoder.CTX,
        "vocab_size": len(inv), "merges": len(merges),
    }
    json.dump(cfg, open(out / "config.json", "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    return out, len(inv), len(merges)


# ---------- 이미지 계약 ----------

def pick_images():
    """분류마다 PER_CATEGORY장. 파일명 순으로 고정해 실행마다 같은 집합이 나온다."""
    rows = [r for r in csv.DictReader(open(ROOT / "labels.csv", encoding="utf-8"))
            if (ROOT / r["path"]).exists()]
    by_cat = {}
    for r in sorted(rows, key=lambda r: r["path"]):
        by_cat.setdefault(r["category"], []).append(r)
    return [r for v in by_cat.values() for r in v[:PER_CATEGORY]]


def copy_attribution(picked):
    """쓴 사진의 출처만 골라 함께 둔다. 라이선스는 파일을 따라다녀야 한다."""
    src = {r["file"]: r for r in csv.DictReader(open(ROOT / "ATTRIBUTION.csv", encoding="utf-8"))}
    with open(OUT / "ATTRIBUTION.csv", "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=["file", "title", "creator", "license", "tier", "source"])
        w.writeheader()
        for r in picked:
            name = pathlib.Path(r["path"]).name
            w.writerow(src.get(name, {"file": name, "title": "", "creator": "",
                                      "license": "unknown", "tier": "", "source": ""}))


def sha8(p):
    return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()[:16]


def save_f32(path, a):
    """float32 리틀엔디언 원시 바이트. numpy도 Kotlin도 한 줄로 읽는다 —
    .npy는 헤더 파싱이 필요해 폰 쪽에 공짜가 아니다. 모양은 manifest에 적는다."""
    a.astype("<f4").tofile(path)
    return list(a.shape)


def load_f32(path, cols):
    return np.fromfile(path, dtype="<f4").reshape(-1, cols)


PREP_N = 8                            # 전처리 텐서를 담을 장수 (8×3×224×224×4 = 4.8MB)


def emit_prep_tensors(enc, picked):
    """데스크톱이 CLIP에 실제로 넣은 224×224 CHW 텐서를 그대로 담는다.

    이게 없으면 폰에서 임베딩이 안 맞을 때 **전처리가 다른 건지 모델이 다른 건지**
    구분할 수 없다. 이 파일이 있으면 폰은 두 가지를 따로 잴 수 있다:
      (a) 자기 전처리 결과를 이 텐서와 비교  → 전처리만의 차이
      (b) 이 텐서를 자기 ONNX에 넣어 임베딩 비교 → 모델만의 차이
    실제로 첫 실측에서 (b)는 1.000000인데 (a)가 0.97이었다 — 원인이 한쪽에 있었다.
    """
    tensors = np.stack([enc._prep(Image.open(ROOT / r["path"])) for r in picked[:PREP_N]])
    save_f32(OUT / "prep_ko.bin", tensors.astype(np.float32))
    return {"files": [pathlib.Path(r["path"]).name for r in picked[:PREP_N]],
            "shape": list(tensors.shape)}


def emit_prompt_matrix(enc, cfg):
    """카테고리·속성·세부종류 프롬프트 앙상블 행렬. 폰은 텍스트 모델로 직접 만들지만(§7.4),
    분류 로직만 따로 검증하려면 기준 행렬이 있어야 한다. 둘 다 이걸로 대조한다.

    **classify()와 같은 틀(templates)을 써야 한다.** 카테고리·세부종류는 틀을 씌우고
    속성은 안 씌운다 — 여기가 어긋나면 폰이 다른 행렬로 같은 라벨을 낸다."""
    tpl = tuple(cfg.get("prompt_templates" + enc.suffix, ["{}"]))
    out = {}
    for kind, t in (("categories", tpl), ("attributes", ("{}",))):
        key = kind + enc.suffix
        labels, mat = enc.prompt_matrix(key, cfg[key], t)
        save_f32(OUT / f"prompts_{kind}.bin", mat)
        out[kind] = {"labels": list(labels), "shape": list(mat.shape)}

    # 세부 종류: 부모마다 행렬이 다르다. 한 파일에 이어 붙이고 manifest에 offset을 적는다.
    subs = cfg.get("subcategories" + enc.suffix, {})
    rows, index, off = [], {}, 0
    for parent, table in subs.items():
        labels, mat = enc.prompt_matrix("sub:" + parent + enc.suffix, table, tpl)
        index[parent] = {"labels": list(labels), "offset": off, "count": len(labels)}
        rows.append(mat); off += len(labels)
    if rows:
        save_f32(OUT / "prompts_sub.bin", np.concatenate(rows))
    out["sub"] = index
    out["templates"] = list(tpl)
    return out


# ---------- 생성 / 검증 ----------

def build(enc, cfg, verify_only):
    texts = korean_corpus(cfg)
    # enc.tok은 77 패딩·잘림이 켜져 있다. 계약에는 BPE가 실제로 내는 원본 열을 담는다 —
    # 패딩은 그 위에 얹는 별개의 층이고, 폰에서 따로 검증하는 편이 어디가 틀렸는지 분명하다.
    from tokenizers import Tokenizer
    raw_tok = Tokenizer.from_file(str(encoder.paths(enc.variant)[2]))
    ids = [raw_tok.encode(t).ids for t in texts]
    tvecs = np.stack([enc.encode_text(t) for t in texts]).astype(np.float32)

    picked = pick_images()
    imgs = [Image.open(ROOT / r["path"]) for r in picked]
    ivecs = enc.encode_images(imgs).astype(np.float32)
    sub_all = {x for d in cfg.get("subcategories" + enc.suffix, {}).values() for x in d}
    tagged = [enc.classify(v, cfg) for v in ivecs]
    labels = [t[0][0] for t in tagged]
    sublabels = [next((l for l, _, _ in t if l in sub_all), None) for t in tagged]

    vis, txt, tokjson, _ = encoder.paths(enc.variant)
    man = {
        "model_variant": enc.variant,
        "model_sha16": {p.name: sha8(p) for p in (vis, txt, tokjson)},
        "infer_batch": enc.infer_batch,
        "preprocess": {"px": enc.px, "resize": "BILINEAR", "crop": "center",
                       "mean": enc.mean.tolist(), "std": enc.std.tolist()},
        "criteria": {"tokens": "정확히 일치", "cos_min": COS_MIN,
                     "label_min": f"{LABEL_MIN}/{len(picked)}"},
        # 계약의 ids는 BPE 원본 열이다. 모델에 넣기 전에 이 규칙으로 77까지 채운다.
        "padding": {"ctx": encoder.CTX, "pad_token": "<|endoftext|>",
                    "rule": "77로 자르고, 모자라면 eot로 채운다 (attention_mask 없음)"},
        "counts": {"texts": len(texts), "images": len(picked)},
    }

    if verify_only:
        return verify(man, texts, ids, tvecs, picked, ivecs, labels)

    OUT.mkdir(exist_ok=True)
    (OUT / "images").mkdir(exist_ok=True)
    for r in picked:
        shutil.copy(ROOT / r["path"], OUT / "images" / pathlib.Path(r["path"]).name)
    copy_attribution(picked)

    json.dump([{"text": t, "ids": i} for t, i in zip(texts, ids)],
              open(OUT / "tokens_ko.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    json.dump({"texts": texts}, open(OUT / "texts_ko.json", "w", encoding="utf-8"),
              ensure_ascii=False, indent=1)
    save_f32(OUT / "texts_ko.bin", tvecs)
    json.dump([{"file": pathlib.Path(r["path"]).name, "truth": r["category"], "top1": l, "sub": sl}
               for r, l, sl in zip(picked, labels, sublabels)],
              open(OUT / "images_ko.json", "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    save_f32(OUT / "images_ko.bin", ivecs)

    man["prompts"] = emit_prompt_matrix(enc, cfg)
    man["prep"] = emit_prep_tensors(enc, picked)
    man["embeddings"] = {"texts_ko.bin": [len(texts), int(tvecs.shape[1])],
                         "images_ko.bin": [len(picked), int(ivecs.shape[1])],
                         "dtype": "float32 little-endian"}
    man["thresholds"] = {k: cfg[k] for k in
                         ("classify_min_score", "secondary_min_score", "max_categories",
                          "logit_scale", "attr_tag_threshold") if k in cfg}
    man["thresholds"]["attr_tag_threshold_used"] = cfg.get(
        "attr_tag_threshold" + enc.suffix, cfg["attr_tag_threshold"])
    man["thresholds"]["primary_only"] = list(cfg.get("primary_only", ()))
    tdir, nv, nm = emit_tokenizer_assets(enc.variant)
    man["tokenizer_assets"] = {"dir": tdir.name, "vocab": nv, "merges": nm}
    man["ort_tokenizer_check"] = check_ort_tokenizer(texts, ids)
    json.dump(man, open(OUT / "manifest.json", "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)

    print(f"eval/fixtures/ 생성 — 문장 {len(texts)} · 사진 {len(picked)} · 모델 {enc.variant}")
    print(f"  토큰 {len(texts)}개 · 텍스트 임베딩 {tvecs.shape} · 이미지 임베딩 {ivecs.shape}")
    print(f"  폰용 토크나이저 자산: vocab {nv} · merges {nm} → fixtures/tokenizer/")
    pm = man["prompts"]
    print(f"  프롬프트 행렬: 카테고리 {pm['categories']['shape']} · 속성 {pm['attributes']['shape']}")
    print(f"  전처리 텐서: {man['prep']['shape']} (전처리와 모델을 갈라 재기 위한 것)")
    c = man["ort_tokenizer_check"]
    if c.get("available"):
        print(f"  tokenizer_ko.onnx 대조: {c['total']-c['mismatch']}/{c['total']} 일치 → {c['verdict']}")
        for e in c["examples"][:3]:
            print(f"      불일치 예: {e!r}")
    else:
        print(f"  tokenizer_ko.onnx 대조: 건너뜀 ({c['reason']})")
    return 0


def verify(man, texts, ids, tvecs, picked, ivecs, labels):
    """지금 코드가 저장된 계약과 같은 값을 내는가."""
    if not (OUT / "manifest.json").exists():
        print("계약이 없습니다. 먼저 python eval/make_fixtures.py 를 실행하세요.")
        return 1
    old = json.load(open(OUT / "manifest.json", encoding="utf-8"))
    fail = []

    if old["model_sha16"] != man["model_sha16"]:
        fail.append("모델 파일이 바뀌었다 — 계약을 다시 만들어야 한다")
    if old["preprocess"] != man["preprocess"]:
        fail.append(f"전처리가 바뀌었다: {old['preprocess']} → {man['preprocess']}")

    ref_tok = json.load(open(OUT / "tokens_ko.json", encoding="utf-8"))
    if [r["text"] for r in ref_tok] != texts:
        fail.append("문장 목록이 바뀌었다 — 계약을 다시 만들 것")
    else:
        n = sum(1 for r, got in zip(ref_tok, ids) if r["ids"] != got)
        print(f"  토큰       {len(texts)-n}/{len(texts)} 일치")
        if n:
            fail.append(f"토큰 {n}개 불일치")

    for name, cur, path in [("텍스트", tvecs, "texts_ko.bin"), ("이미지", ivecs, "images_ko.bin")]:
        ref = load_f32(OUT / path, cur.shape[1])
        if ref.shape != cur.shape:
            fail.append(f"{name} 임베딩 개수가 다르다 {ref.shape} vs {cur.shape}")
            continue
        cos = (ref * cur).sum(1)
        print(f"  {name} 임베딩  중앙 {np.median(cos):.6f}  최소 {cos.min():.6f}"
              f"  ({COS_MIN} 이상 {(cos >= COS_MIN).sum()}/{len(cos)})")
        if cos.min() < COS_MIN:
            fail.append(f"{name} 임베딩 코사인 최소 {cos.min():.6f} < {COS_MIN}")

    ref_img = json.load(open(OUT / "images_ko.json", encoding="utf-8"))
    same = sum(1 for r, l in zip(ref_img, labels) if r["top1"] == l)
    print(f"  1순위 라벨  {same}/{len(labels)} 일치")
    if same < LABEL_MIN:
        fail.append(f"1순위 라벨 일치 {same} < {LABEL_MIN}")

    if fail:
        print("\n계약 위반:")
        for f in fail:
            print("  ✗", f)
        return 1
    print("\n계약 준수.")
    return 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--verify", action="store_true", help="생성하지 않고 대조만 한다")
    ap.add_argument("--variant", default=None, help="en | ko (기본: config의 model_variant)")
    a = ap.parse_args()

    enc = encoder.get(a.variant)
    if enc is None:
        print("모델이 없습니다. python export_ko_model.py 를 먼저 실행하세요.")
        return 1
    if enc.infer_batch != 1:
        print(f"경고: infer_batch={enc.infer_batch}. 배치가 1이 아니면 임베딩이 "
              f"같이 넣은 사진에 좌우되어 계약이 재현되지 않는다.")
    return build(enc, store.load_config(), a.verify)


if __name__ == "__main__":
    sys.exit(main())
