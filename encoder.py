"""CLIP ONNX 래퍼. 임베딩 하나로 분류·검색·유사중복을 전부 처리한다.

데스크톱에서 쓰는 이 .onnx 파일이 안드로이드에서도 그대로 돈다.
튜닝값과 프롬프트는 전부 config.json에 있으므로 포팅 시 함께 복사하면 된다.
"""
import json, pathlib, threading
import numpy as np
import onnxruntime as ort
from PIL import Image
from tokenizers import Tokenizer

MODELS = pathlib.Path(__file__).parent / "models"
CTX = 77                                    # CLIP 텍스트 컨텍스트 길이

# variant별 파일 세트. "ko"는 한국어를 그대로 받으므로 ko_dict 번역이 필요 없다.
FILES = {
    "en": ("vision_model_quantized.onnx", "text_model_quantized.onnx",
           "tokenizer.json", "preprocessor_config.json"),
    "ko": ("vision_model_ko_int8.onnx", "text_model_ko_int8.onnx",
           "tokenizer_ko.json", "preprocessor_config_ko.json"),
}


def paths(variant):
    return [MODELS / f for f in FILES[variant]]


def _unit(a):
    """L2 정규화. 이렇게 저장해 두면 코사인 유사도가 단순 내적이 된다."""
    return a / np.maximum(np.linalg.norm(a, axis=-1, keepdims=True), 1e-12)


class Encoder:
    def __init__(self, variant="en", infer_batch=1):
        self.variant = variant
        self.infer_batch = max(1, int(infer_batch))
        self.suffix = "_ko" if variant == "ko" else ""
        vision, text, tokjson, ppjson = paths(variant)
        pp = json.loads(ppjson.read_text())
        self.mean = np.array(pp["image_mean"], np.float32)
        self.std = np.array(pp["image_std"], np.float32)
        self.px = pp["crop_size"]["height"]
        so = ort.SessionOptions()
        so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        self.vis = ort.InferenceSession(str(vision), so, providers=["CPUExecutionProvider"])
        self.txt = ort.InferenceSession(str(text), so, providers=["CPUExecutionProvider"])
        self.tok = Tokenizer.from_file(str(tokjson))
        eot = self.tok.token_to_id("<|endoftext|>")
        self.tok.enable_truncation(CTX)
        self.tok.enable_padding(length=CTX, pad_id=eot, pad_token="<|endoftext|>")
        self._cache, self._lock = {}, threading.Lock()

    # ---------- 이미지 ----------

    def _prep(self, im):
        """CLIP 전처리: 짧은 변 224로 리사이즈 → 중앙 크롭 → 정규화.

        BILINEAR은 안드로이드 Bitmap.createScaledBitmap(filter=true)와 같은 보간이다.
        제품은 폰이므로 데스크톱을 폰에 맞춘다 — 두 쪽 임베딩이 같아야
        여기서 잰 정확도가 폰의 정확도가 된다."""
        im = im.convert("RGB")
        w, h = im.size
        s = self.px / min(w, h)
        im = im.resize((max(self.px, round(w * s)), max(self.px, round(h * s))), Image.BILINEAR)
        w, h = im.size
        l, t = (w - self.px) // 2, (h - self.px) // 2
        a = np.asarray(im.crop((l, t, l + self.px, t + self.px)), np.float32) / 255.0
        return ((a - self.mean) / self.std).transpose(2, 0, 1)

    def _run(self, sess, name, arr):
        """infer_batch 단위로 쪼개 넣는다. 기본 1인 이유는 encode_images 참조."""
        n = self.infer_batch
        if len(arr) <= n:
            return sess.run(None, {name: arr})[0]
        return np.concatenate([sess.run(None, {name: arr[i:i + n]})[0]
                               for i in range(0, len(arr), n)])

    def encode_images(self, images):
        """한 장씩 넣는다 (infer_batch=1). 배치로 넣으면 임베딩이 달라진다.

        int8 동적 양자화는 활성값 스케일을 추론 시점에 잡는데, 그 스케일이 배치
        텐서 전체에서 나온다 — 같은 배치에 들어간 다른 사진이 내 임베딩을 바꾼다.
        실측(400장, ko): 스캔 순서만 섞어도 같은 사진의 코사인이 중앙 0.9857,
        최저 0.9435까지 떨어지고 11장의 1순위 라벨이 뒤집혔다. dupe_similarity
        0.95와 같은 크기의 잡음이라, 폴더를 다시 스캔하면 중복 그룹과 검색 결과가
        바뀐다. 1장씩 넣으면 임베딩이 그 사진만의 함수가 된다.

        ponytail: 대가는 속도다 (데스크톱 14.6 → 24.4ms/장). 속도가 문제가 되면
        정적 양자화(교정된 고정 스케일)로 올릴 것 — 그러면 배치를 되살릴 수 있고
        QNN(NPU) 경로도 열린다. config.json의 infer_batch로 실험만 해 볼 수 있다.
        """
        arr = np.stack([self._prep(im) for im in images]).astype(np.float32)
        return _unit(self._run(self.vis, "pixel_values", arr))

    # ---------- 텍스트 ----------

    def encode_text(self, texts):
        one = isinstance(texts, str)
        enc = self.tok.encode_batch([texts] if one else list(texts))
        ids = np.array([e.ids for e in enc], dtype=np.int64)
        v = _unit(self._run(self.txt, "input_ids", ids))     # 이미지와 같은 이유로 1문장씩
        return v[0] if one else v

    def prompt_matrix(self, key, prompt_map, templates=("{}",)):
        """카테고리별 프롬프트 앙상블(평균) 행렬. 한 번만 계산하고 캐시한다.

        templates는 각 문장에 씌우는 틀("휴대폰으로 찍은 {}")이다. 문장 × 틀을 전부 임베딩해
        평균내면 CLIP 표준 앙상블이 된다 — 400장에서 0.838 → 0.858. 캐시 키에 틀을 넣어
        틀이 바뀌면 다시 계산한다."""
        ck = (key, tuple(templates))
        with self._lock:
            if ck not in self._cache:
                labels = list(prompt_map)
                mat = np.stack([_unit(self.encode_text(
                    [t.format(p) for p in prompt_map[l] for t in templates]).mean(0)) for l in labels])
                self._cache[ck] = (labels, mat.astype(np.float32))
            return self._cache[ck]

    # ---------- 분류 ----------

    def classify(self, vec, cfg):
        tags = []
        key = "categories" + self.suffix
        tpl = tuple(cfg.get("prompt_templates" + self.suffix, ["{}"]))
        labels, mat = self.prompt_matrix(key, cfg[key], tpl)
        # CLIP 원 코사인은 0.15~0.35 좁은 구간에 몰린다. 학습된 온도(≈100)로
        # softmax를 씌워야 사진 간 비교가 가능한 확률이 된다.
        logits = cfg.get("logit_scale", 100.0) * (mat @ vec)
        p = np.exp(logits - logits.max())
        p /= p.sum()
        order = np.argsort(-p)
        i = int(order[0])
        if float(p[i]) >= cfg["classify_min_score"]:
            tags.append((labels[i], round(float(p[i]), 3), "clip"))
        else:
            tags.append(("미분류", round(float(p[i]), 3), "clip"))
        # 대분류는 배타적이지 않다. '창가 캣타워의 고양이'는 반려동물이면서 실내일상이고,
        # 하나만 붙이라고 강제하면 둘 중 하나는 반드시 틀린 답이 된다.
        # 단 문서·화면캡처는 2순위로 붙이지 않는다 — '약간 문서인 사진'은 말이 안 되고,
        # 허용하면 그 앨범이 잡동사니가 된다(정밀도 0.72→0.61).
        primary_only = set(cfg.get("primary_only", ()))
        for j in order[1:cfg["max_categories"]]:
            lab = labels[int(j)]
            if float(p[j]) >= cfg["secondary_min_score"] and lab not in primary_only:
                tags.append((lab, round(float(p[j]), 3), "clip"))

        # ponytail: 속성은 서로 배타적이지 않아 softmax를 못 쓴다. 원 코사인 + 임계값으로 간다.
        # 오탐이 잦으면 긍정/부정 프롬프트 쌍의 2-way softmax로 올릴 것.
        # 2단계: 부모 안에서만 다투는 세부 종류. 형제끼리 softmax라 다른 부모의 표를
        # 빼앗지 않는다 — 평면으로 늘리면 제로섬(8→14개에서 0.86→0.84)이지만 계층은
        # 부모 정확도를 건드리지 않는다. 확실하지 않으면(sub_min_score 미만) 부모 태그만 남긴다.
        subs = cfg.get("subcategories" + self.suffix, {})
        parent = labels[i]
        if float(p[i]) >= cfg["classify_min_score"] and parent in subs:
            slabels, smat = self.prompt_matrix("sub:" + parent + self.suffix, subs[parent], tpl)
            sl = cfg.get("logit_scale", 100.0) * (smat @ vec)
            sp = np.exp(sl - sl.max()); sp /= sp.sum()
            j = int(np.argmax(sp))
            if float(sp[j]) >= cfg.get("sub_min_score", 0.4):
                tags.append((slabels[j], round(float(sp[j]), 3), "clip"))

        # 임계값은 **속성마다 다르다.** 원 코사인은 분포가 속성별로 달라서 전역값
        # 하나를 쓰면 어떤 속성에는 p99, 어떤 속성에는 p70이 된다 — 실측으로 '지도화면'이
        # 풍경 사진 25장에, '눈비'가 반려동물 15장에 붙었다. attr_thresholds_*에 속성별
        # 값을 두고, 없는 속성만 공용값으로 떨어진다.
        akey = "attributes" + self.suffix
        fallback = cfg.get("attr_tag_threshold" + self.suffix, cfg["attr_tag_threshold"])
        per = cfg.get("attr_thresholds" + self.suffix, {})
        alabels, amat = self.prompt_matrix(akey, cfg[akey])
        for lab, s in zip(alabels, amat @ vec):
            if float(s) >= per.get(lab, fallback):
                tags.append((lab, round(float(s), 3), "clip"))
        return tags

    # ---------- 한국어 검색어 ----------

    def translate(self, q, cfg):
        """(영어질의, 처리됨?) 이미지 임베딩이 영어 텍스트 타워 기준이라 검색어를 영어로 바꾼다.

        토큰 앞부분과 일치하면 채택한다 — 한국어 조사('바다에서', '꽃을')를 자연히 흡수한다.
        긴 키를 먼저 본다: '비행기'가 '비'보다 먼저 걸려야 한다.

        처리됨=False로 돌아오면 CLIP에 한글이 그대로 들어간다. 토크나이저가 한글을
        의미 없는 토큰으로 만들어 '그럴듯하지만 무의미한' 결과가 나오므로,
        호출 측은 반드시 사용자에게 알려야 한다."""
        if self.variant == "ko":
            return q, True                 # 한국어 모델은 검색어를 그대로 받는다
        keys = sorted(cfg["ko_dict"], key=len, reverse=True)
        hits = []
        for tok in q.split():
            for k in keys:
                if tok.startswith(k):
                    hits.append(cfg["ko_dict"][k])
                    break
        if hits:
            return ", ".join(dict.fromkeys(hits)), True
        hangul = any("가" <= ch <= "힣" for ch in q)
        return q, not hangul               # 한글이 없으면 영어 질의로 보고 그대로 통과


_enc, _enc_lock = None, threading.Lock()


def get(variant=None):
    """모델이 없으면 None. 앱은 CLIP 없이도 규칙 분류·타임라인·완전중복으로 동작한다."""
    global _enc
    if variant is None:
        import store
        variant = store.load_config().get("model_variant", "en")
    if not all(p.exists() for p in paths(variant)):
        return None
    import store
    with _enc_lock:
        if _enc is None or _enc.variant != variant:
            _enc = Encoder(variant, store.load_config().get("infer_batch", 1))
    return _enc
