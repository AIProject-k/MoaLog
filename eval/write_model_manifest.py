"""앱에 든 ONNX 모델의 SHA-256을 manifest.json으로 적는다 (계획서 MLG-103).

모델 파일을 바꾸면 이 스크립트를 다시 돌린다. 돌리지 않고 파일만 바꾸면 앱이
"모델이 손상되었다"고 멈춘다 — 조용히 틀린 모델을 쓰지 않으려는 의도된 동작이다.

사용: python eval/write_model_manifest.py
"""
import hashlib
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[1]
MODELS = ROOT / "android" / "app" / "src" / "main" / "assets" / "models"
# AppGraph.ONNX_ASSETS와 같아야 한다. 다르면 앱이 "manifest에 없다"고 알려 준다.
FILES = ["vision_model_ko_int8.onnx", "text_model_ko_int8.onnx"]


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    manifest = {
        "schema_version": 1,
        "files": {name: sha256(MODELS / name) for name in FILES},
        # 앱이 시작할 때 크기만 값싸게 확인한다. 잘리거나 지워진 모델을 잡으려는 것이다.
        "sizes": {name: (MODELS / name).stat().st_size for name in FILES},
    }
    out = MODELS / "manifest.json"
    out.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(out)
    for name, digest in manifest["files"].items():
        print(f"  {digest}  {name}")


if __name__ == "__main__":
    main()
