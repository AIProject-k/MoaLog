"""CLIP ONNX 모델을 내려받는다. python setup_model.py

데스크톱에서 돌리는 바로 이 .onnx 파일이 안드로이드(ONNX Runtime Mobile)에서도 그대로 돈다.
그래서 여기서 잰 정확도가 안드로이드 정확도의 진짜 예측치가 된다.

모델: CLIP ViT-B/32 (OpenAI, MIT 라이선스) int8 양자화본.
      MobileCLIP이 4배 작지만 Apple 연구용 라이선스라 쓰지 않는다.
"""
import pathlib, sys, urllib.request

REPO = "Xenova/clip-vit-base-patch32"
BASE = f"https://huggingface.co/{REPO}/resolve/main/"
MODELS = pathlib.Path(__file__).parent / "models"
FILES = [
    "onnx/vision_model_quantized.onnx",
    "onnx/text_model_quantized.onnx",
    "tokenizer.json",
    "preprocessor_config.json",
]


def fetch(rel):
    dest = MODELS / pathlib.Path(rel).name
    if dest.exists() and dest.stat().st_size > 0:
        print(f"  이미 있음  {dest.name} ({dest.stat().st_size/1e6:.1f}MB)")
        return dest
    req = urllib.request.Request(BASE + rel, headers={"User-Agent": "photo-log/0.1"})
    with urllib.request.urlopen(req, timeout=120) as r:
        total = int(r.headers.get("Content-Length") or 0)
        done = mark = 0
        tmp = dest.with_suffix(dest.suffix + ".part")
        print(f"  {dest.name} ({total/1e6:.1f}MB) ", end="", flush=True)
        with open(tmp, "wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)
                done += len(chunk)
                if done - mark >= 10 << 20:          # 10MB마다 점 하나
                    mark = done
                    print(".", end="", flush=True)
        print(" 완료")
    tmp.replace(dest)
    return dest


def main():
    MODELS.mkdir(exist_ok=True)
    print(f"{REPO} (MIT) → models/")
    for rel in FILES:
        try:
            fetch(rel)
        except Exception as e:
            print(f"  실패 {rel}: {type(e).__name__}: {e}")
            return 1
    total = sum(p.stat().st_size for p in MODELS.iterdir()) / 1e6
    print(f"\n완료 · 총 {total:.1f}MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
