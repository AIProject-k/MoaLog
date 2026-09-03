# 안드로이드 설계 — 사진 자동분류 (에이닷 로그류)

데스크톱판([README.md](README.md))은 테스트베드였다. 이 문서는 거기서 검증한 것을
**안드로이드 제품으로 옮기는 전체 설계**다. 결정마다 이유를 적었다 — 이유가 사라지면 결정도 바꾼다.

작성 2026-09-02. 데스크톱 측정치(400장, ko 모델, 정확도 0.86, 검색 P@10 0.85)를 전제로 한다.

---

## 0. 한 장 요약

**무엇** — 폰 갤러리를 읽어 ① 주제별 앨범(자동 태그) ② 시간·장소로 묶인 기록(**로그**)
③ 한국어 문장 검색 ④ 중복·흐림 정리. 전부 기기 안에서. **인터넷 권한 없음.**

**어떻게** — 데스크톱에서 검증한 한국어 CLIP(ONNX int8, 160MB) **하나**가 앨범·검색·유사중복을 덮는다.
로그·정리·출처 판정은 EXIF와 MediaStore 메타데이터만 쓴다. 학습 0회, 벡터DB 0개, 서버 0대.

| 그대로 감 | 다시 짬 | 새로 생김 |
|---|---|---|
| `models/*.onnx` 3개 | Python → Kotlin | 출처 태그 (`OWNER_PACKAGE_NAME`, 경로) |
| `config.json` (프롬프트·임계값) | FastAPI → 없음 | **2단계 인덱싱** (로그 먼저, AI 태그 나중) |
| 판정 로직 — `classify`, 규칙태그, 연사, 이벤트 | HTML → Compose | 시스템 휴지통 (`createTrashRequest`) |
| SQLite 스키마 → Room | 폴더 스캔 → MediaStore | 오프라인 장소명 |
| 평가셋·채점 방식 | 썸네일 생성 → **MediaStore 썸네일 재사용** | OCR 검색, 얼굴 수 (2단계) |
| | 검색 전량 SELECT → **메모리 행렬** | 사용자 교정 → 개인화 |

---

## 1. 원칙

1. **온디바이스만.** `INTERNET` 권한을 매니페스트에 넣지 않는다. 사용자가 앱 정보에서 직접 확인할 수 있는 약속이다.
2. **모델 하나.** 새 기능이 새 모델을 요구하면 먼저 임베딩·메타데이터로 되는지 본다. 안 되면 2단계 이후로.
3. **튠값은 `config.json`.** 데스크톱과 같은 파일이 `assets/`로 간다. 코드에 상수를 박지 않는다.
4. **데스크톱은 예측기다.** 같은 `.onnx` · 같은 토크나이저 · 같은 전처리 → 같은 벡터. 패리티 테스트(§15)가 이를 증명한다.
   둘이 다르면 **데스크톱을 폰에 맞춘다** — 제품은 폰이다.
5. **파일을 지우지 않는다.** 시스템 휴지통만. 그룹당 1장은 코드가 강제로 남긴다.
6. **사진은 기기를 떠나지 않는다.** 클라우드 재분류(`config.cloud`)는 별도 빌드 flavor로만 존재한다.

---

## 2. 플랫폼 결정

| 항목 | 결정 | 이유 |
|---|---|---|
| minSdk | **30** (Android 11) | `createTrashRequest`·`GENERATION_*`·`setRequireOriginal`이 전부 29~30+. 2026년 한국 활성 기기 거의 전부 |
| targetSdk | 최신 (35/36) | Play 사진 권한 정책 대응에 필수 |
| 언어 / UI | Kotlin · Jetpack Compose · Material 3 | |
| DI | **없음** — `AppGraph` object에서 수동 주입 | 1인 프로젝트에 Hilt는 비용만 든다 |
| 비동기 | Coroutines / Flow | |
| 저장 | Room + Paging 3 | 스키마를 그대로 옮기고 컴파일 타임에 SQL 검증. 1만 장 그리드는 페이징 필수 |
| 백그라운드 | WorkManager (장기 실행 워커 + 포그라운드 알림) | 플랫폼 권장 경로. Android 14 포그라운드 타입, 15 시간제한을 WorkManager가 흡수 |
| 이미지 로딩 | Coil | content URI → MediaStore 썸네일 |
| ML | `onnxruntime-android` | 데스크톱과 **같은 `.onnx`** |
| 토크나이저 | **Kotlin BPE 직접 구현** (~150줄, 의존성 0) | ORT-extensions의 CLIPTokenizer는 이 모델을 재현하지 못한다 — §7.3에서 검증 |
| 모델 배포 | Play Asset Delivery **install-time** 팩 | 160MB. 베이스 모듈 200MB 제한 우회. 첫 실행에 다운로드 없음 = 인터넷 권한 불필요 |
| 넣지 않는 것 | 네트워크 라이브러리, 분석 SDK, Firebase, 벡터DB, Hilt, 자체 썸네일 캐시 | 각각 필요해지는 순간이 오면 그때 |

---

## 3. 시스템 지도

```
 MediaStore ──(증분: GENERATION_MODIFIED)──▶ MediaIndex ──▶ Room.photos(state=NEW)
      ▲ ContentObserver → 즉시 큐잉                              │
                                                                ▼
 ┌─────────────────── IndexWorker (WorkManager, 재개 가능) ───────────────────┐
 │  Pass 1  ≈ 2ms/장   EXIF·크기·출처 → 규칙태그 → 완전중복 후보 → 이벤트(로그) │  ← 10초 안에 로그가 뜬다
 │  Pass 2  ≈ 50~100ms/장  MediaStore 썸네일 → CLIP → 태그·임베딩 → 연사·제목  │  ← AI 태그가 채워진다
 └────────────────────────────────────────────────────────────────────────────┘
                                                                │
        ┌──────────────┬───────────────┬────────────────────────┼──────────────┐
      앨범(tags)     로그(events)     검색(SearchIndex 메모리 행렬 + 질의 파서)    정리(dupes·blur)
```

**2단계로 나누는 이유.** 데스크톱은 장당 16ms라 한 번에 했다. 폰은 CLIP만 50~100ms라 1만 장에 15분이다.
그 15분 동안 빈 화면을 보여줄 수 없다. EXIF만으로 만들 수 있는 것(타임라인·출처·완전중복)은
Pass 1에서 몇 초 만에 끝내고, AI 태그는 뒤에서 채운다. 사용자는 설치 직후 자기 로그를 본다.

---

## 4. 미디어 인덱싱 (MediaStore)

### 4.1 무엇을 읽나

```
_ID, DISPLAY_NAME, RELATIVE_PATH, BUCKET_DISPLAY_NAME, OWNER_PACKAGE_NAME,
MIME_TYPE, SIZE, WIDTH, HEIGHT, ORIENTATION, DATE_TAKEN, DATE_MODIFIED,
GENERATION_ADDED, GENERATION_MODIFIED, IS_TRASHED, IS_PENDING
```

선택: `IS_TRASHED=0 AND IS_PENDING=0 AND MIME_TYPE LIKE 'image/%' AND GENERATION_MODIFIED > :last`.
확장자 목록(`config.extensions`)은 MIME으로 대체한다. GIF는 첫 프레임만 본다.

### 4.2 증분과 삭제

- 마지막 동기화의 `MediaStore.getGeneration()`을 `meta`에 저장. 다음엔 그보다 큰 generation만 읽는다 — 1만 장 갤러리에 사진 3장 추가 = 3행 조회.
- `ContentObserver`가 변경을 알리면 워커를 큐잉(expedited). 앱이 열려 있을 때만 등록한다.
- 삭제 감지: 동기화 때 전체 `_ID`만 뽑아 집합 차이(1만 개 정수, 수십 ms). 없어진 행은 Room에서 지운다(태그·그룹은 CASCADE).
- `path UNIQUE` 대신 **`media_id` PK**. 경로는 사용자가 옮길 수 있고, ID는 안 바뀐다.

### 4.3 권한 (셋 다 처리해야 한다)

| 상황 | 권한 | 동작 |
|---|---|---|
| Android 13+ 전체 | `READ_MEDIA_IMAGES` | 정상 |
| Android 14+ **일부 선택** | `READ_MEDIA_VISUAL_USER_SELECTED` | 허용된 사진만 인덱싱. 로그 상단에 "전체 허용" 배너 |
| Android 11~12 | `READ_EXTERNAL_STORAGE` (maxSdk 32) | 정상 |
| GPS 읽기 | `ACCESS_MEDIA_LOCATION` + `MediaStore.setRequireOriginal(uri)` | **없으면 EXIF GPS가 시스템에 의해 지워져서 온다.** '위치있음' 태그와 이벤트 장소가 통째로 사라진다 |

### 4.4 출처 태그 — 데스크톱 규칙의 업그레이드

데스크톱은 EXIF 카메라 유무 + 화면비로 카메라촬영/스크린샷/저장·다운로드를 갈랐다.
안드로이드는 **누가 이 파일을 넣었는지**를 준다. 훨씬 강한 신호다.

| 신호 (우선순위 순) | 태그 |
|---|---|
| `RELATIVE_PATH` `DCIM/Camera/` · 카메라 앱 패키지 · EXIF Make/Model 있음 | 카메라촬영 |
| `Pictures/Screenshots/` · `DCIM/Screenshots/` · `com.android.systemui` 등 캡처 주체 | 스크린샷 |
| `com.kakao.talk` · `Pictures/KakaoTalk/` · 기타 메신저 패키지 | **메신저저장** (신규) |
| `Download/` · `IS_DOWNLOAD` | 다운로드 |
| 나머지 | 저장/다운로드 → 화면비 휴리스틱(데스크톱 방식)으로 스크린샷 보정 |

규칙은 코드가 아니라 `config.provenance_rules`(경로 접두·패키지 → 태그 목록)에 둔다.
USB로 복사한 파일은 `OWNER_PACKAGE_NAME`이 비어 오므로 휴리스틱 폴백은 남긴다.

**README의 '되돌린 시도'가 여기서 풀린다.** 문서와 화면캡처는 문장으로 못 가른다고 결론 났는데,
스크린샷 폴더에서 온 파일은 CLIP이 뭐라 하든 화면캡처다. 출처 태그가 `문서/영수증` 후보에서 스크린샷을 빼 준다.

### 4.5 촬영시각

`DATE_TAKEN`을 믿지 않는다 — 스캐너가 EXIF 없을 때 무엇을 채우는지 기기마다 다르다.
`ExifInterface`로 헤더만 직접 읽어(전체 디코드 없음, ~1ms) 데스크톱과 같은 `taken_at_source ∈ exif|mtime`을 기록한다.
`OffsetTimeOriginal`(EXIF 2.31)이 있으면 정확한 시간대, 없으면 기기 로컬 시간대 — 데스크톱과 같은 해석.

**이 출처 구분이 연사 판정과 로그 품질을 지킨다** (§11, §12). 카톡 저장 사진·스크린샷은 촬영시각이 없다.

---

## 5. 처리 파이프라인 (WorkManager)

### 5.1 상태 머신

```
photos.state:  NEW ──Pass1──▶ META ──Pass2──▶ DONE
                 └──────────── FAILED (재시도 없음: 열 수 없는 파일)
worker:        IDLE → SYNC → PASS1 → GROUP1(완전중복·이벤트) → PASS2 → GROUP2(연사·제목) → IDLE
```

배치마다 커밋. 어디서 죽어도 `state`가 남아 있어 다음 실행이 이어 간다 — 데스크톱의 `indexed_at IS NULL` 패턴 그대로.

### 5.2 워커

- `CoroutineWorker`, 고유 이름 `"index"`, `ExistingWorkPolicy.KEEP`.
- 대량 작업은 `setForeground()`로 진행률 알림을 띄운다. 포그라운드 타입 `dataSync` (Android 14 필수 선언). Android 15의 `mediaProcessing` 타입과 6시간/일 제한은 검토 대상 — 1만 장 15분은 여유가 크다.
- 첫 인덱싱은 **사용자가 시작**한다(권한 직후). 옵션 "충전 중에만 AI 태그 계산" 기본 꺼짐.
- 증분(사진 몇 장 추가)은 `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)`로 즉시.

### 5.3 제어

| 조건 | 동작 |
|---|---|
| 열 상태 `THERMAL_STATUS_SEVERE` 이상 | Pass 2 일시정지, 60초 후 재확인 |
| 배터리 < 15% 이고 충전 중 아님 | Pass 2 일시정지 (Pass 1은 계속 — 싸다) |
| Pass 2 추론 배치 | **1장.** 메모리 때문이 아니라 정확성 때문이다 — §7.5 |
| Pass 2 커밋 배치 | 32장 (중단 복구 단위). 추론 배치와 별개다 |
| ORT 스레드 | intra-op = 빅코어 수 (보통 4). 리틀코어를 섞으면 느려진다 |
| 실패 파일 | `FAILED`로 마킹, 재시도 없음. 완료 알림에 "읽기 실패 n장" |

---

## 6. 이미지 입력

### 6.1 원본을 디코드하지 않는다

데스크톱 프로파일에서 **썸네일 생성 + JPEG 디코드가 46%**였다. 폰에는 이미 시스템 썸네일이 있다.

```kotlin
resolver.loadThumbnail(uri, Size(320, 320), null)   // API 29+, 시스템 캐시, 회전 적용 완료
```

- 이 320px 비트맵 하나로 CLIP 입력(224 크롭)·흐림·밝기를 전부 뽑는다.
- 근거: README 실측 — 디코드를 256/320/384로 바꿔도 정확도는 0.853~0.863 무작위 변동. 224만 넘으면 된다.
- **자체 썸네일 저장 0바이트.** 갤러리 그리드도 Coil이 같은 시스템 썸네일을 쓴다.
- `loadThumbnail`이 실패하는 파일(일부 포맷·손상)은 `ImageDecoder` + `setTargetSampleSize`로 폴백 — 데스크톱 `draft()`와 같은 축소 디코드.
- 원본 바이트를 읽는 경우는 **둘뿐**: EXIF 헤더(§4.5), 완전중복 후보의 해시(§12.1).

### 6.2 전처리 패리티

| 단계 | 데스크톱 (`encoder._prep`) | 안드로이드 | 조치 |
|---|---|---|---|
| 리사이즈 | 짧은 변 224, **BILINEAR** | `Bitmap.createScaledBitmap(filter=true)` = **bilinear** | ✔ 완료 — 데스크톱을 폰에 맞췄다. 정확도 0.86 유지 |
| 크롭 | 중앙 224×224 | 같음 | |
| 정규화 | `(x/255 − mean)/std`, CHW | 같음, `preprocessor_config_ko.json` 값 | |
| 흐림·밝기 | L 변환 → 긴 변 256 → 라플라시안 분산 / 평균 | 320 썸네일 → 같은 절차 | 스케일 동일 → `blur_threshold` 의미 유지 |

**리사이즈는 `Bitmap.createScaledBitmap`으로 하면 안 된다.** 그건 2탭 이중선형이라 원본
픽셀 4개만 보고 만든다. 3~4배로 줄이면 그 사이 픽셀이 통째로 버려져 계단이 생기는데,
PIL의 `Image.BILINEAR`은 축소 배율만큼 필터 지지폭을 넓혀 그 구간을 평균낸다.

Phase 0 실측(기기, 사진 8장) — 같은 사진에서 만든 CLIP 입력 텐서 비교:

| 안드로이드 리사이즈 방식 | 텐서 cos | 최대 픽셀차 | 종단간 임베딩 cos (중앙/최소) | 1순위 라벨 |
|---|---|---|---|---|
| `createScaledBitmap` 한 번에 | 0.9448 | 2.34 | 0.922 / 0.827 | 62/64 |
| 절반씩 반복 축소 | — | — | 0.975 / 0.905 | 61/64 |
| **`Resampler`(PIL 호환 삼각 필터)** | **1.000000** | **0.015** | **0.992 / 0.970** | **64/64** |

그래서 `core/Resampler.kt`에 PIL의 `precompute_coeffs`와 같은 계수 계산을 직접 넣었다
(분리형 2패스, 삼각 필터, 지지폭 = 1.0 × 축소배율). 폰을 데스크톱에 맞추는 게 아니라
**양쪽 다 제대로 된 필터를 쓰게** 하는 방향이다 — 에일리어싱을 남기는 쪽에 맞추면
숫자는 맞고 품질은 잃는다.

디코드는 **알파를 미리 곱하지 않는다**(`inPremultiplied = false`). 안드로이드 기본은
미리 곱하지만 PIL의 `convert("RGB")`는 알파를 그냥 버린다. 반투명 PNG(스티커·일부
스크린샷)에서 갈린다.

---

## 7. ML 런타임 (ONNX Runtime)

### 7.1 세션 수명

| 세션 | 크기 | 언제 올리나 | 언제 내리나 |
|---|---|---|---|
| vision (int8) | 96MB | Pass 2 시작 | Pass 2 끝 |
| text (int8) | 64MB | 검색 화면 진입 · 프롬프트 행렬 계산 | 검색 화면 이탈 30초 후 |

둘을 동시에 올리는 경우는 프롬프트 행렬을 처음 만들 때뿐이다. 인덱싱 중 RSS +200MB 안팎을 예산으로 잡는다.
모델은 **파일 경로**로 연다. 96MB를 `byte[]`로 Java 힙에 올리면 OOM이다.

### 7.2 실행 프로바이더

| EP | 판단 |
|---|---|
| **CPU (MLAS, NEON)** | **기본.** 동적 양자화 `MatMulInteger`를 실제로 돌리는 유일한 EP |
| NNAPI | Android 15부터 deprecated. 의존하지 않는다 |
| XNNPACK | fp32 Conv/MatMul 중심. 동적 양자화 그래프는 CPU로 떨어져 이득 없음 |
| QNN (Qualcomm HTP) | 정적 양자화 + 벤더 SDK 필요. 속도가 부족하면 3단계에서 |

**측정 전 추정치** (Phase 0에서 확정한다):

| 항목 | 추정 | 비고 |
|---|---|---|
| vision int8, 1장 | 40~120ms | SoC·빅코어 수에 따라. 데스크톱 배치 7ms와 비교 금지 |
| text int8, 1질의 | 30~80ms | 검색 디바운스 300ms 뒤에 숨는다 |
| 1만 장 Pass 2 | 10~20분 | 충전 중 권장, 필수 아님 |
| ARMv8.0(dotprod 없음) 구형 코어 | int8이 fp32보다 **느릴 수 있음** | Phase 0에서 확인. fp32 vision은 350MB라 대안이 아니다 → 정적 양자화·QNN 검토 |

### 7.3 토크나이저 — 가장 조용히 망가지는 지점

데스크톱은 HF `tokenizers`(Rust)를 쓴다. 안드로이드에 그 바인딩은 없다. 두 길:

1. **ORT-extensions `CLIPTokenizer` 커스텀 op** — `models/tokenizer_ko.onnx`(3.2MB)가 이미 만들어져 있다. 단 **아무 코드도 안 쓰고 있어 검증이 안 됐다.**
   한국어 재학습 BPE(vocab·merges)를 이 op가 그대로 재현하는지가 관건.
2. **Kotlin 바이트 수준 BPE** (~150줄): CLIP 정규식 분리 → 바이트 인코딩 → merges 순위 병합 → `</w>`. 의존성 0.

어느 쪽이든 계약은 하나다: **`eval/fixtures/tokens_ko.json`** — 한국어 문장 1,000개(프롬프트 전부 + 검색어 + 조사·띄어쓰기·이모지·영문 혼용)의 HF 토크나이저 id 열.
폰에서 같은 id가 나와야 한다. 하나라도 다르면 임베딩이 다른 공간으로 간다 — 그리고 **에러가 아니라 '그럴듯한' 결과가 나온다.**

**2026-09-02 검증 완료 — 1번 탈락, 2번으로 간다.**

`eval/make_fixtures.py`가 305문장을 대조한 결과 **296/305 일치, 9개 불일치**. 전부
**연속된 숫자 2자리 이상**이다:

```
"2026"   HF   ['2</w>','0</w>','2</w>','6</w>']     ← tokenizer_ko.json의 정규식 [\p{N}] (한 자씩)
         ORT  ['2',    '0',    '2',    '6</w>']     ← op에 박힌 CLIP 원본 정규식 [\p{N}]+ (묶음)
```

`"3월"`·`"1 2 3"`처럼 숫자가 떨어져 있으면 맞고, `"12월"`·`"2026년"`·`"iPhone 15"`에서 갈린다.
op의 속성은 `vocab`·`merges`뿐이라 **정규식을 넘길 방법이 없다 — 재export로도 못 고친다.**

→ **Kotlin BPE를 직접 쓴다.** 그 구현은 반드시 `tokenizer_ko.json`의 `pre_tokenizer.Split.pattern`을
읽어야 한다. 교과서 CLIP 정규식을 그대로 옮기면 똑같은 함정에 빠지고, 증상은 에러가 아니라
"2026년 영수증" 검색이 조용히 엉뚱해지는 것이다. `models/tokenizer_ko.onnx`는 이 판정의
근거로만 남기고 앱에는 넣지 않는다.

### 7.5 배치가 임베딩을 바꾼다 — 추론은 1장씩

**동적 양자화는 활성값 스케일을 추론 시점에 배치 텐서 전체에서 잡는다.** 그래서 한 사진의
임베딩이 같은 배치에 들어간 다른 사진에 좌우된다. 400장 실측(ko, int8):

| 비교 | 같은 사진의 코사인 (중앙 / 최소) | 1순위 라벨이 뒤집힌 장수 |
|---|---|---|
| 배치 1 vs 배치 32 | 0.9871 / 0.9522 | 5 / 400 |
| **스캔 순서만 섞음** (둘 다 배치 32) | 0.9857 / **0.9435** | **11 / 400** |

전체 정확도는 0.863 ↔ 0.865로 멀쩡하다. 집계로는 안 보이고 사진 단위로만 보인다.

**왜 그냥 둘 수 없나.** 잡음의 크기(최소 0.9435)가 `dupe_similarity`(0.95)와 같다. 즉
*같은 사진끼리도* 중복 판정을 통과하지 못할 수 있고, 폴더를 다시 스캔하면 순서가 바뀌어
중복 그룹과 검색 결과가 달라진다. 재현되지 않는 인덱스는 인덱스가 아니다.

**해결: `infer_batch = 1`.** 배치가 1이면 활성값 스케일이 그 사진에서만 나오므로 임베딩이
사진만의 함수가 된다. 데스크톱 실측 14.6 → 24.4ms/장. 폰은 어차피 1~4장 단위라 잃는 게 없다.
`config.batch_size`(32)는 DB 커밋 단위라 그대로 둔다.

`ponytail:` 속도가 문제가 되면 **정적 양자화**(교정된 고정 스케일)로 올린다 — 배치를
되살릴 수 있고 §7.2의 QNN 경로도 그때 열린다. 그 전엔 1을 유지한다.

### 7.4 프롬프트 행렬 캐시

카테고리 8×~4.5문장 + 속성 5×2 ≈ 46문장. 텍스트 모델로 한 번 계산(~2~3초)해 `meta`에 blob으로 저장.
키 = `(model_id, config 해시)`. 둘 중 하나가 바뀌면 재계산. 데스크톱 `prompt_matrix` 캐시의 영속 버전.

---

## 8. 분류 · 태깅

### 8.1 `classify()`는 1:1 이식

softmax(온도 100) → 1순위(`classify_min_score`) → 2·3순위(`secondary_min_score`, `primary_only` 제외) → 속성(원 코사인 임계).
로직을 손대지 않는다 — 여기 숫자가 데스크톱 0.86의 근거다. 온도 `100`은 코드 상수였는데 `config.logit_scale`로 옮긴다.

### 8.2 태그 출처

`tags.source ∈ rule | clip | ocr | face | user`. UI는 출처 배지를 보여 준다 — 사용자가 "왜 이 태그인가"를 알 수 있어야 교정할 마음이 든다.

### 8.3 속성 임계값은 모델별이다 (데스크톱 분석에서 발견)

`attr_tag_threshold`는 원 코사인이라 모델마다 분포가 다른데 값이 하나다. ko 모델 400장 실측:

| 속성 | 중앙 | p90 | p99 | max | 0.24 초과 |
|---|---|---|---|---|---|
| 셀카 | 0.177 | 0.211 | 0.233 | 0.271 | **2장** |
| 단체사진 | 0.198 | 0.223 | 0.254 | 0.264 | 13장 |
| 실외 | 0.195 | 0.236 | 0.251 | 0.263 | 26장 |
| 텍스트많음 | 0.211 | 0.235 | 0.256 | 0.263 | 25장 |
| 저조도 | 0.202 | 0.227 | 0.251 | 0.257 | 10장 |

0.24는 셀카의 p99 위다 — 사실상 꺼져 있다. `attr_tag_threshold_ko`를 분리하고 **0.225 근처**에서 시작, 본인 갤러리로 다시 잰다.
분류(softmax)는 variant를 타도 멀쩡하지만 속성은 아니다.

### 8.3b 종류를 늘리는 법 — 실측으로 정한 규칙 (2026-09-02)

카테고리 8 → 14개로 늘리자 정확도 0.86 → 0.82, 미분류 1 → 7장. **softmax는 제로섬이다.**
인물 7장이 '행사/축제'로 빠졌다 — 결혼식·생일 프롬프트가 사람으로 가득해서다.
그래서 종류를 늘리는 데 세 가지 규칙을 세웠다:

| 규칙 | 근거 |
|---|---|
| **1. 카테고리는 겹치지 않는 장면만.** 하위 종류('카페')는 카테고리로 넣지 않는다 | '카페'는 음식과 표를 나눠 둘 다 떨어진다 |
| **2. 하위 종류는 계층 2단계로.** 부모 1순위 뒤 **형제끼리만** softmax | 다른 부모의 표를 안 빼앗아 부모 정확도가 구조적으로 그대로 (0.838 → 0.838 실측) |
| **3. 속성은 속성별 임계값으로.** 전역값 하나는 금지 | 전역 0.225에서 '지도화면'이 풍경 25장에, '눈비'가 반려동물 15장에 붙었다 |

그리고 두 가지를 더 찾았다:

- **`classify_min_score`는 카테고리 개수에 딸린 값이다.** 8개 기준 0.25를 14개에 그대로 쓰면
  미분류만 는다. 스윕: 0.25 → 미분류 7장·0.828, **0.16 → 1장·0.838**.
- **프롬프트 템플릿 앙상블**이 공짜로 2점을 준다. `'{}' + '휴대폰으로 찍은 {}' + '갤러리에 있는 {}'`
  → 0.838 → **0.858**. 속성에는 씌우지 않는다(임계값이 현재 분포 기준).

최종: **카테고리 14 · 세부 59 · 속성 14**, 정확도 **0.86**(8개일 때와 같음), 태그 포함율 0.88 → **0.90**.
세부 종류 정답은 평가셋에 없어 정확도는 못 재고 분포만 봤다 — 음식 → 한식 18·카페 13,
반려동물 → 강아지 23·고양이 18. 본인 갤러리로 재는 것이 다음이다.

신호가 없어 **버린 속성**: 눈비(반려동물에 붙음), 역광(고르게 붙어 신호 없음), 꽃(10장 발화, 엉뚱한 곳).
종류를 늘리는 게 목적이라도 틀리게 붙는 태그는 앨범을 잡동사니로 만든다.

### 8.4 인물 약점(F1 0.60)의 진짜 해법은 CLIP 밖에 있다

2단계: ML Kit 얼굴 **검출**(온디바이스, 신원 아님) → 얼굴 수·최대 얼굴 비율.
`얼굴 ≥ 1 → 인물`, `얼굴 1개 & 비율 > 0.2 → 셀카`, `얼굴 ≥ 3 → 단체사진`. 규칙이 CLIP 속성을 덮어쓴다.
생체 템플릿을 저장하지 않으므로(개수만) 민감정보 문제가 없다. 신원 클러스터링(같은 사람 앨범)은 §17 3단계, 별도 동의.

### 8.5 개인화 — 학습 없이 (구현됨: `core/Personalize.kt`)

사용자가 태그를 고치면(`user_feedback`) 그 사진 임베딩을 해당 카테고리의 **이미지 프로토타입**으로 쓴다.

```
proto[c] = unit( text_proto[c] + α · mean(confirmed_image_embeds[c]) ),  α = 0.5,  확정 ≥ 5장부터
```

부정 피드백("이건 음식 아님")은 무시한다 — `ponytail: 빼기는 수학이 지저분하다. 긍정만으로 충분한지 먼저 본다.`
프로토타입이 바뀌면 영향받는 사진만 백그라운드 재분류(임베딩은 그대로, 행렬곱만).

---

## 9. 저장 (Room)

데스크톱 스키마를 그대로 옮기되 표시한 열만 바뀐다.

```sql
photos (
  media_id INTEGER PRIMARY KEY,        -- 변경: path → MediaStore _ID
  uri TEXT NOT NULL, display_name TEXT,
  bucket TEXT, rel_path TEXT, owner_pkg TEXT,   -- 추가: 출처 판정 근거
  mime TEXT, bytes INTEGER, width INTEGER, height INTEGER, orientation INTEGER,
  generation INTEGER,                   -- 추가: 증분 동기화
  taken_at INTEGER, taken_at_source TEXT,       -- 'exif' | 'mtime'
  gps_lat REAL, gps_lon REAL, camera_model TEXT,
  blur_score REAL, brightness REAL,
  embedding BLOB,                       -- float32×512, L2 정규화 (2KB)
  model_id TEXT,                        -- 추가: 어느 모델의 벡터인가
  state INTEGER NOT NULL DEFAULT 0      -- 변경: indexed_at → NEW/META/DONE/FAILED
);
tags (media_id, label, score, source, PRIMARY KEY(media_id,label));   -- source에 ocr/face/user 추가
events (id, started_at, ended_at, gps_lat, gps_lon, place_name, title, summary);
event_photos (event_id, media_id, is_cover);
dupe_groups (group_id, media_id, kind, is_best);                      -- kind: exact | burst | similar(2단계)
user_feedback (media_id, label, verdict, ts);                         -- 추가
ocr_text — FTS4(media_id, text);                                      -- 2단계
meta (k, v);   -- last_generation, model_id, prompt_matrix(blob), config_hash
```

- 1만 장 ≈ 25MB (대부분 임베딩). 10만 장까지 이 구조로 간다.
- Room 마이그레이션은 버전별 `Migration`으로. 파괴적 재생성은 임베딩 15분을 날리므로 금지.
- **백업 제외**: 전부 파생 데이터다. `dataExtractionRules`로 DB·모델 복사본을 뺀다. `user_feedback`만 예외로 남길 가치가 있다 — 1단계에서는 통째로 제외, 2단계에서 분리.

---

## 10. 검색

### 10.1 인덱스는 메모리에 한 번

데스크톱은 질의마다 임베딩 전량을 SELECT했다(400장 0.8MB라 티가 안 났다). 폰에서 1만 장이면 질의당 20MB 읽기 — 못 쓴다.

```kotlin
class SearchIndex(val ids: IntArray, val mat: FloatArray /* N×512 */) {
    fun topK(q: FloatArray, k: Int, allow: BitSet? = null): IntArray   // 내적 = 코사인(정규화 저장)
}
```

- 앱 시작 시 Room에서 한 번 로드(1만 장 20MB, ~100ms). 새 임베딩은 append, 모델 교체 시 재로드.
- 1만×512 내적 ≈ 5M MAC ≈ 5~10ms Kotlin 루프. 벡터DB 없음.
- `// ponytail: fp32 완전탐색. 5만 장 넘으면 fp16(절반) → int8 + 청크 스캔. 그 전엔 손대지 않는다.`

### 10.2 질의 파서 — 규칙 20줄

사용자는 "작년 여름 강릉에서 먹은 회"라고 친다. CLIP은 "회"를 알지만 "작년"을 모른다.

| 패턴 | 처리 |
|---|---|
| `작년·올해·지난달·N월·N년·봄여름가을겨울·밤/야간·주말` | 시간 범위 → `allow` 비트셋 |
| 이벤트 `place_name`에 있는 지명 | 장소 필터 |
| 태그 라벨과 일치하는 단어 | 태그 필터 (AND) |
| 나머지 | CLIP 텍스트 임베딩 → 랭킹 |

필터가 비면 전체에서 랭킹. 파서가 소비한 단어는 화면에 칩으로 보여 준다 — 잘못 해석했으면 사용자가 칩을 끈다.

### 10.3 2단계: OCR 텍스트 결합

스크린샷·문서는 ML Kit 텍스트 인식(한국어, 온디바이스)으로 글자를 뽑아 `ocr_text` FTS에 넣는다.
질의 단어가 FTS에 걸리면 그 사진의 CLIP 점수에 가산. "카톡으로 받은 계좌번호 캡처"가 여기서 된다.
스크린샷이 갤러리의 30~40%인 한국 사용자에게 이게 CLIP만큼 중요하다.

### 10.4 UX

디바운스 300ms · 텍스트 세션은 검색 화면에 있는 동안 유지 · 결과 카드에 점수와 상위 태그 표시(왜 나왔는지).

---

## 11. 로그 (타임라인 · 이벤트 · 장소)

이 앱의 얼굴이다. 앨범은 갤러리 앱도 준다. **시간과 장소로 묶인 기록**이 차이다.

### 11.1 이벤트 만들기 — 데스크톱 로직 + 폰 신호

| 규칙 | 데스크톱 | 안드로이드 |
|---|---|---|
| 시간 간격 | 연속 사진 > `event_gap_hours`(3h) → 분리 | 같음 |
| 위치 점프 | 없음 (평가셋에 GPS 2장) | 연속 사진 > `location_split_km`(30) → 분리. GPS 있을 때만 |
| **대상** | `taken_at IS NOT NULL` 전부 | **`taken_at_source='exif'`만** — 카톡 저장·스크린샷은 다운로드 시각으로 여행 로그에 끼어들면 안 된다 |
| 촬영시각 없는 사진 | 섞여 들어감 | 날짜별 "저장한 이미지 n장" 접힌 카드 하나로 |
| 제목 | `M월 D일 · 태그1 n장, 태그2 n장` | `M월 D일 · 강릉시 · 풍경 12, 음식 4` |
| 커버 | 선명한 3장 | 선명한 3장 **중 서로 cos < 0.9** (같은 사진 셋 방지), 인물·풍경 우선 |

Pass 1 끝에 이벤트를 만들고(제목은 날짜·장소만), Pass 2 끝에 태그로 제목을 갱신한다. 로그는 10초 안에 뜬다.

### 11.2 장소명 — 오프라인

- 국내: **시군구 중심점 표**(~250행, 수 KB, 공공누리 1유형 확인) → 25km 내 최근접 → "강릉시". 이벤트 GPS 평균 1점만 조회.
- 해외: GeoNames `cities15000`(CC-BY 4.0, ~25k행, ~2MB) → "Tokyo". 2단계.
- 시스템 `Geocoder`는 앱 권한 없이 호출되지만 기기·연결에 따라 비어 온다 → 있으면 읍면동 수준으로 **보정**, 없어도 동작.

### 11.3 화면 구조

일 → 이벤트 카드(제목·장소·커버 3장·태그 칩) → 탭하면 그리드. 월 헤더. 상단에 **"1년 전 오늘"** (전년도 ±1일 이벤트).
인덱싱 중이면 맨 위에 진행 카드 — Pass 1/2 진행률, "지금까지 태그 n장".

### 11.4 3단계 (선택): 한 줄 요약

템플릿 입력(태그·장소·시간대·장수)을 온디바이스 LLM(ML Kit GenAI / Gemini Nano, 지원 기기만)에 넣어 "강릉 바다에서 회 먹고 노을 본 날" 한 줄.
사진은 넣지 않는다 — 태그만. 미지원 기기는 템플릿 제목 그대로.

---

## 12. 중복 · 정리 · 휴지통

### 12.1 완전중복 — 전부 해시하지 않는다

데스크톱은 400장 전부 SHA-256을 했다. 폰 1만 장 × 3MB = **30GB 읽기** — 안 된다.
MediaStore가 `SIZE·WIDTH·HEIGHT`를 공짜로 준다 → 셋이 같은 후보군만 해시. 보통 전체의 1~2%.
남길 것은 데스크톱 `origin_rank`(복사본·(1)·Copy 접미사) 그대로, `DISPLAY_NAME` 기준.

### 12.2 연사 — 시간 조건에 출처를 건다

```
cos ≥ dupe_similarity(0.95)  AND  |Δt| ≤ burst_gap_sec(30)  AND  둘 다 taken_at_source = 'exif'
```

마지막 조건이 데스크톱에 없었다(§18). 400장 중 398장이 mtime이라 시간 조건이 한 번도 작동하지 않았고, 폰에서는
카톡으로 한 번에 받은 비슷한 스샷 두 장이 오탐 연사가 된다. 남길 것 = 가장 선명한 것(데스크톱 동일).

### 12.3 2단계: 유사중복 (`similar`)

카톡 재저장·리사이즈 사본은 바이트가 달라 exact가 못 잡고 시각이 없어 burst도 못 잡는다.
후보: `cos ≥ 0.98 AND 화면비 ±1%`, 시간 무관. **기본 체크 해제**, 오탐률을 본인 갤러리로 먼저 잰 뒤 켠다.

### 12.4 휴지통

```kotlin
MediaStore.createTrashRequest(resolver, uris, /*trashed=*/true)   // → PendingIntent, 시스템 확인 대화상자
```

시스템 휴지통 30일 보관 — README의 "삭제하지 않는다" 원칙을 플랫폼이 강제한다.
**그룹당 1장 보존**은 요청을 만들기 전 ViewModel에서 데스크톱 `_keep_one_per_group`과 같은 로직으로.
휴지통에 간 항목은 다음 동기화에서 `IS_TRASHED=1`로 걸러져 Room에서 빠진다.

흐림·저품질은 데스크톱 그대로(`blur_threshold` 기본 0 = 제안 없음, 분포 슬라이더).

---

## 13. UI (Compose)

하단 탭 4개. 설정은 우상단.

| 화면 | 내용 | 빈 상태 / 예외 |
|---|---|---|
| **로그** (기본) | §11.3 | 권한 없음 → 요청 카드 / 일부 선택 → 배너 / 인덱싱 중 → 진행 카드 |
| **앨범** | 태그별 칩(장수) → 그리드. 출처 태그(카메라·스크린샷·메신저)도 앨범 | 태그 없음 → "AI 태그 계산 중 n/N" |
| **검색** | 입력 → 해석된 칩(시간·장소·태그) → 결과 그리드(점수) | 모델 미준비 → 안내 |
| **정리** | 중복 그룹(남길 것 표시) · 흐림(슬라이더+분포) · 저품질 → "휴지통으로" | 없음 → "정리할 것 없음" |
| 사진 | 원본(Coil) · 태그(출처 배지·점수) · EXIF · "태그 수정" | |
| 설정 | 모델 정보·버전 · 인덱싱 조건(충전 중만) · 진단 내보내기(JSON) · 데이터 초기화 · 라이선스 · (debug) 임계값 | |

- 그리드는 Room + Paging 3 → `LazyVerticalGrid`. 썸네일은 Coil이 content URI로.
- **접근성**: 태그가 곧 `contentDescription`이다 — "음식, 실내일상, 카메라촬영 사진". 공짜 alt text.
- 진행 상태는 Room 카운트 Flow로 — 워커가 UI를 몰라도 된다.

---

## 14. 모델 배포 · 버전

- Asset pack `models`(install-time): `vision_model_ko_int8.onnx`, `text_model_ko_int8.onnx`, `tokenizer_ko.onnx`, `manifest.json`(sha256, `model_id`).
- 첫 실행에 `noBackupFilesDir`로 1회 복사(~2초) — ORT는 경로가 필요하고, 백업에서 빼야 한다.
- `model_id = "ko-b32-int8@<sha256 앞 8자>"`. `meta.model_id`와 다르면 → `embedding=NULL, state=META`(Pass 1 결과는 유지, Pass 2만 다시). 데스크톱 `check_variant`의 정확한 대응.
- `config.json`은 `assets/`, 사용자 조정값(흐림 임계 등)은 DataStore에 덧씌운다.
- 모델이 안 바뀐 앱 업데이트는 Play 델타가 160MB를 다시 내리지 않는다.

---

## 15. 테스트 · 패리티 · 평가

### 15.1 계약 fixture — 데스크톱이 만든다 (`eval/make_fixtures.py`, 신규)

| 파일 | 내용 | 폰에서의 판정 |
|---|---|---|
| `tokens_ko.json` | 한국어 305문장 → BPE 원본 id 열 (패딩 전) | **정확히 일치** |
| `images/` 64장 + `images_ko.npy` | CC0 사진 → 이미지 임베딩 + 1순위 라벨 | cos ≥ 0.99 · **라벨 일치 ≥ 62/64** |
| `texts_ko.json` + `texts_ko.npy` | 프롬프트·검색어·엣지케이스 305문장 → 텍스트 임베딩 | cos ≥ 0.99 |
| `manifest.json` | 모델 sha · 전처리 파라미터 · 패딩 규칙 · 판정 기준 | 모델이 바뀌면 계약을 다시 만든다 |

**코사인 기준 0.96은 Phase 0 실측으로 정했다** (2026-09-02, Android 15/API 35).
계약은 전처리와 모델을 **갈라서** 잰다 — 안 그러면 안 맞을 때 어디가 문제인지 모른다:

| 무엇을 재나 | 결과 |
|---|---|
| 전처리만 — 기기 전처리 vs 데스크톱 텐서(`prep_ko.bin`) | **cos 1.000000**, 최대 픽셀차 0.015 |
| 모델만 — 데스크톱 텐서 → 기기 ONNX | **cos 1.000000** |
| 종단간 — 사진 파일 → 임베딩 | 중앙 0.992, 최소 0.970 |
| **1순위 라벨** | **64/64 일치** |

앞의 두 줄이 1.000000이므로 **우리 코드에는 차이가 없다.** 종단간에 남는 0.97은
**JPEG 디코더 차이**다 — 가장 안 맞는 사진은 4:2:0 크로마 서브샘플링이고, libjpeg(PIL)의
fancy upsampling과 Skia(안드로이드)의 업샘플링이 다르다. 우리가 고칠 수 있는 코드가
아니며, int8 동적 양자화가 그 작은 입력 차이를 증폭한다.

그래서 코사인 기준은 실측 최소(0.970) 아래로 여유를 두어 **0.96**, **판정은 라벨 일치에
맡긴다.** 코사인 0.99와 0.97의 차이는 사용자가 볼 수 없지만 라벨이 뒤집히면 보인다.

`--verify`는 데스크톱 자신을 계약과 대조한다. 실제로 잡는다 — 보간을 BICUBIC으로 되돌려
보면 이미지 코사인이 0.972로 떨어지며 위반이 뜬다.

### 15.2 폰 테스트

- **Instrumented (Phase 0 관문)**: 위 세 가지 + 장당 ms·RSS를 표로 출력. 이게 통과하면 데스크톱 숫자가 폰 숫자다.
- **JVM 단위**: `test_pipeline.py` 1~9번을 그대로 Kotlin으로 — 규칙태그, 완전중복, 연사(exif 가드 포함), 이벤트 분리, 그룹당 1장, 질의 파서. ORT 없이 돈다.
- **성능 게이트**: 1만 장 첫 인덱싱 ≤ 20분(중급기), 배터리 ≤ 8%, 검색 1만 장 ≤ 300ms.

### 15.3 본인 갤러리 평가 — Commons 대신

메모리에 적힌 대로 Commons 평가셋은 아카이브 스캔본으로 오염돼 있고, 폰 갤러리의 분포(스크린샷 30~40%, 카톡, 셀카, 영수증)와 다르다.
설정 → (debug) **라벨링 모드**: 사진 300장에 정답 카테고리를 찍는다 → 기기에서 정확도 계산 → CSV 내보내기 → 데스크톱 `eval/own_labels.csv`.
1단계 완료 기준: 1순위 정확도 ≥ 0.85 **또는** 태그 포함 ≥ 0.92 (사진당 태그 ≤ 1.6개 조건).

---

## 16. 프라이버시 · 스토어

```xml
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES"/>
<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED"/>
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32"/>
<uses-permission android:name="android.permission.ACCESS_MEDIA_LOCATION"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<!-- INTERNET 없음. 이것이 약속이다. -->
```

- **Play 사진·동영상 권한 정책**: `READ_MEDIA_IMAGES`는 갤러리 관리가 핵심 기능인 앱만 허용 — 이 앱이 정확히 그것. 콘솔 선언서와 시연 영상 준비.
- 데이터 안전: 수집 없음, 공유 없음. 매니페스트가 증거다.
- 얼굴: 2단계 검출은 **개수만** 저장(생체 정보 아님). 3단계 신원 클러스터링은 별도 동의 화면 · 온디바이스 · "얼굴 데이터 모두 삭제" 버튼.
- 라이선스 화면: Bingsu CLIP(MIT) · ONNX Runtime(MIT) · GeoNames(CC-BY 4.0) · 행정구역(공공누리) · ML Kit(Google 약관). MobileCLIP은 연구용 라이선스라 제외(기존 결정 유지).

---

## 17. 단계별 계획

| 단계 | 기간 | 내용 | 완료 기준 |
|---|---|---|---|
| ~~**0. 패리티**~~ | — | ORT로 두 모델 로드 · Kotlin BPE(§7.3) · fixture 대조 · 장당 ms/RSS | **✔ 통과** (에뮬레이터 API 35 x86_64) — 아래 표. 실기기 ARM 재측정만 남음 |
| **1. MVP** | 3~4주 | §4~§14 (2단계 표시 제외). 로그·앨범·검색·정리·휴지통 | 본인 갤러리 1만 장 ≤ 20분 · 정확도 ≥ 0.85 · INTERNET 없음 |
| **2. 로그 품질** | 3주 | 장소명 · OCR FTS · 얼굴 수 규칙 · 개인화 · `similar` 측정 · 속성 임계 재조정 | 검색 P@10 본인 갤러리 ≥ 0.85 · 인물 F1 ≥ 0.8 |
| **3. 선택** | — | LLM 한 줄 요약 · 얼굴 신원(동의) · 동영상 첫 프레임 · 클라우드 flavor · 위젯/알림 | 각각 독립 |

**Phase 0이 모든 것을 결정한다.** 목표 기기에서 int8이 200ms/장을 넘으면 1단계에 들어가기 전에
정적 양자화·QNN·더 작은 비전 타워(허용 라이선스만)를 다시 본다.

### Phase 0 결과 (2026-09-02 · Android 15 / API 35 / x86_64 에뮬레이터)

```
토큰       311/311 일치
텍스트 임베딩  중앙 1.000000  최소 0.999999   (12.3 ms/문장)
이미지 임베딩  중앙 0.992044  최소 0.970200
1순위 라벨    64/64 일치
속도         46.5 ms/장
메모리        PSS 78 → 294 MB
결정성        같은 사진 재인코딩 cos 1.000000
[분리] 전처리만 cos 1.000000 · 최대 픽셀차 0.015
[분리] 모델만   cos 1.000000
```

**읽는 법 — 이 숫자들은 아직 제품 숫자가 아니다:**

- **정확성은 결론이 났다.** 라벨 64/64, 전처리·모델 각각 1.000000. 데스크톱에서 튜닝한
  임계값과 프롬프트가 폰에서 그대로 유효하다는 뜻이다.
- **속도 46.5ms는 두 가지 이유로 제품 값이 아니다.** ① x86_64 에뮬레이터는 호스트 CPU에서
  도는데 int8 커널이 x86(AVX2/VNNI)과 ARM(NEON/dotprod)에서 완전히 다르다. ② 이 측정은
  960px 원본을 디코드해 리샘플링한다 — 제품은 `loadThumbnail(320)`을 쓰므로(§6.1)
  리샘플 비용이 훨씬 적다. **실기기 ARM 측정이 Phase 0을 닫는 마지막 한 줄이다.**
- **메모리 294MB는 예산(§7.1의 +200MB)을 넘는다.** 세션 수명 관리(vision/text를 동시에
  안 여는 것)가 실제로 지켜지는지 실기기에서 다시 볼 것.

### 리스크

| 리스크 | 징후 | 대응 |
|---|---|---|
| ~~토크나이저 불일치~~ | **이미 발생** — ORT op이 다자리 숫자에서 깨짐 | **해결됨**: Kotlin BPE 확정(§7.3), 계약 fixture 준비 완료 |
| ARM에서 int8 느림 | Phase 0 표 | 정적 양자화(QLinearMatMul) → QNN |
| `loadThumbnail` 편차 (OEM별 크기·품질·실패) | 패리티 cos 하락 | `ImageDecoder` 폴백 경로도 fixture로 검증 |
| Play 권한 심사 | 거절 | 정책 선언서 + 시연 영상, 핵심 기능이 갤러리 관리임을 명시 |
| 설치 160MB | 이탈 | PAD로 분리 배포는 이미 함. 장기: 허용 라이선스의 작은 비전 타워 탐색 |

---

## 18. 데스크톱 저장소에서 지금 바꿀 것

폰 설계가 데스크톱에 요구하는 것. 전부 작고, 전부 "데스크톱 = 폰"을 위한 것이다.

| # | 변경 | 상태 | 근거 |
|---|---|---|---|
| 1 | `_group_bursts` 쿼리에 `AND taken_at_source='exif'` | **✔ 완료** | §12.2 |
| 2 | `attr_tag_threshold_ko` 분리 (0.225) | **✔ 완료** | §8.3. 스윕 실측 후 결정 |
| 3 | `encoder._prep` BICUBIC → BILINEAR | **✔ 완료** | §6.2. 정확도 0.86 유지, 검색 P@10 0.85→0.87 |
| 4 | `tokenizer_ko.onnx`(ORT-extensions)를 쓸 수 있는가 | **✔ 검증 → 기각** | §7.3. 다자리 숫자에서 불일치, 재export 불가 |
| 5 | `logit_scale: 100`을 `config.json`으로 | **✔ 완료** | §8.1 |
| 6 | `eval/make_fixtures.py` — 패리티 계약 | **✔ 완료** | §15.1. 305문장 + 64장, `--verify`가 회귀를 잡는다 |
| — | **`infer_batch: 1`** — 배치 추론 제거 | **✔ 완료 (설계 중 발견)** | §7.5. 배치가 임베딩을 바꾸고 있었다 |
| 7 | `/api/search`도 임베딩 행렬을 메모리에 | 남음 | §10.1. 폰과 같은 구조 |
| 8 | `provenance_rules`를 `config.json`에 | 남음 | §4.4 |

**1~6은 끝났고 그 과정에서 7번째가 나왔다.** 배치 추론이 임베딩을 흔들고 있었고
(§7.5), ORT-extensions 토크나이저는 쓸 수 없다는 것이 확인됐다(§7.3) — 둘 다
폰에서 처음 만났다면 원인을 찾기 훨씬 어려웠을 종류다. 데스크톱 테스트베드가
값을 한 셈이다.

남은 7·8은 폰 코드를 쓰기 전에 하면 되고, Phase 0은 지금 시작할 수 있다.
