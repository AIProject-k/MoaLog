"""한국어 CLIP을 ONNX로 1회 export한다. python export_ko_model.py

    pip install torch --index-url https://download.pytorch.org/whl/cpu
    pip install transformers onnx

torch/transformers는 **이 스크립트에서만** 쓴다. 앱 실행에는 필요 없다.
결과물(.onnx + tokenizer.json)만 있으면 onnxruntime으로 돌아가고,
그 파일이 그대로 안드로이드(ONNX Runtime Mobile)로 간다.

모델: Bingsu/clip-vit-base-patch32-ko (MIT)
      영어 CLIP ViT-B/32와 아키텍처가 동일(512차원)해서 encoder.py를 그대로 쓴다.
      단 토크나이저는 BPE 병합을 한국어로 재학습한 것이라 반드시 함께 받아야 한다.
"""
import pathlib, shutil, sys

REPO = "Bingsu/clip-vit-base-patch32-ko"
OUT = pathlib.Path(__file__).parent / "models"


def main():
    try:
        import torch
        from transformers import CLIPModel, CLIPTokenizerFast, CLIPImageProcessor
    except ImportError as e:
        print(f"빌드 의존성이 없습니다 ({e.name}). 위 주석의 pip 명령을 먼저 실행하세요.")
        return 1

    OUT.mkdir(exist_ok=True)
    print(f"{REPO} 내려받는 중… (약 605MB, 캐시됨)")
    model = CLIPModel.from_pretrained(REPO).eval()

    # transformers 5.x의 get_*_features는 텐서가 아니라 BaseModelOutputWithPooling을
    # 돌려준다. 투영된 512차원 임베딩은 pooler_output이고, last_hidden_state는
    # (1,50,768) 원시 출력이다. 그냥 반환하면 768차원 쪽이 export되어 모델이 망가진다.
    class Vision(torch.nn.Module):
        def __init__(self, m):
            super().__init__(); self.m = m

        def forward(self, pixel_values):
            return self.m.get_image_features(pixel_values=pixel_values).pooler_output

    class Text(torch.nn.Module):
        # attention_mask를 받지 않는다 — CLIP은 eot 토큰 위치에서 풀링하고
        # 패딩도 eot로 하므로 마스크 없이 원본 구현과 같은 결과가 나온다.
        def __init__(self, m):
            super().__init__(); self.m = m

        def forward(self, input_ids):
            return self.m.get_text_features(input_ids=input_ids).pooler_output

    specs = [
        ("vision_model.onnx", Vision(model), (torch.randn(1, 3, 224, 224),),
         ["pixel_values"], ["image_embeds"],
         {"pixel_values": {0: "batch_size"}, "image_embeds": {0: "batch_size"}}),
        ("text_model.onnx", Text(model), (torch.ones(1, 77, dtype=torch.long),),
         ["input_ids"], ["text_embeds"],
         {"input_ids": {0: "batch_size", 1: "sequence_length"},
          "text_embeds": {0: "batch_size"}}),
    ]
    import numpy as np
    import onnxruntime as ort
    from onnxruntime.quantization import quantize_dynamic, QuantType

    def run(path, feed):
        s = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
        return s.run(None, feed)[0]

    def cos(a, b):
        a, b = a.ravel(), b.ravel()
        return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b)))

    for name, mod, args, ins, outs, axes in specs:
        fp32 = OUT / ("_fp32_" + name)
        print(f"  export {name} …")
        with torch.inference_mode():
            # dynamo=False: 새 exporter가 만든 그래프는 onnx shape inference를 통과하지 못해
            # 양자화 단계에서 터진다. 레거시 TorchScript 경로가 양자화와 궁합이 맞다.
            torch.onnx.export(mod, args, str(fp32), input_names=ins, output_names=outs,
                              dynamic_axes=axes, opset_version=17,
                              do_constant_folding=True, dynamo=False)

        # 조용히 틀린 export는 이후 측정을 전부 오염시킨다. torch 원본과 대조한다.
        with torch.inference_mode():
            ref = mod(*args).numpy()
        feed = {ins[0]: args[0].numpy()}
        got = run(fp32, feed)
        assert got.shape == ref.shape, (got.shape, ref.shape)
        c32 = cos(ref, got)
        assert c32 > 0.9999, f"fp32 export가 torch와 다르다 (cos={c32:.5f})"

        dest = OUT / name.replace(".onnx", "_ko_int8.onnx")
        # Conv는 절대 넣지 않는다 — patch embedding이 ConvInteger가 되는데 ORT CPU에
        # 구현이 없어 모델이 아예 로드되지 않는다. Gather는 텍스트 임베딩 테이블
        # (49408×512)을 담당해서 넣으면 140MB→64MB가 되고 정확도는 그대로다.
        # 모델마다 되는 조합이 달라 넓은 쪽부터 시도하고 로드 실패 시 좁힌다.
        c8 = None
        for ops in (["MatMul", "Gather"], ["MatMul"]):
            quantize_dynamic(str(fp32), str(dest), weight_type=QuantType.QInt8,
                             op_types_to_quantize=ops)
            try:
                c8 = cos(ref, run(dest, feed))
                print(f"      양자화 대상 {ops}")
                break
            except Exception as e:
                print(f"      {ops} 실패({type(e).__name__}) → 범위를 좁힘")
        assert c8 is not None, "어떤 조합으로도 로드되는 int8 모델을 만들지 못했다"
        for junk in OUT.glob("_fp32_*"):        # 양자화가 남기는 외부 데이터 파일 포함
            junk.unlink(missing_ok=True)
        print(f"    → {dest.name}  {dest.stat().st_size/1e6:.1f}MB  "
              f"(torch 대비 코사인 fp32 {c32:.5f} / int8 {c8:.4f})")
        if c8 < 0.99:
            print(f"       경고: int8 양자화 손실이 큽니다. fp32 사용을 검토하세요.")

    # 토크나이저와 전처리 설정도 한국어판 것으로 받는다 (BPE 병합 규칙이 다르다)
    CLIPTokenizerFast.from_pretrained(REPO).save_pretrained(OUT / "_tok")
    shutil.copy(OUT / "_tok" / "tokenizer.json", OUT / "tokenizer_ko.json")
    shutil.rmtree(OUT / "_tok")
    CLIPImageProcessor.from_pretrained(REPO).save_pretrained(OUT / "_pp")
    shutil.copy(OUT / "_pp" / "preprocessor_config.json", OUT / "preprocessor_config_ko.json")
    shutil.rmtree(OUT / "_pp")

    print("\n완료. config.json의 model.variant를 \"ko\"로 두면 이 파일들을 쓴다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
