# MoaLog 저장소 규칙

## Git 커밋 규칙

[Conventional Commits](https://www.conventionalcommits.org/ko/v1.0.0/) 형식을 쓴다. 지금까지의 커밋(`feat:` `fix:` `docs:` `release:`)과 같은 모양이다.

```
<type>(<scope>): <제목>

<본문 — 왜 바꿨는지. 필요할 때만>

<꼬리말 — 필요할 때만>
```

### type

| type | 언제 | 예 |
|---|---|---|
| `feat` | 사용자가 느끼는 새 기능 | `feat: add video scene search` |
| `fix` | 버그 수정 (`bug`가 아니라 `fix`) | `fix: keep gallery rows when media access shrinks` |
| `docs` | 문서·README·디자인 요청서·캡처만 바뀜 | `docs: add design request for Claude Design` |
| `refactor` | 동작은 그대로, 구조만 정리 | `refactor: share media filter row across tabs` |
| `perf` | 속도·메모리 개선 | `perf: retag videos from stored frame vectors` |
| `test` | 테스트만 추가·수정 | `test: cover album media filter in Store` |
| `build` | Gradle·의존성·버전 설정 | `build: bump onnxruntime to 1.22.0 for 16 KB alignment` |
| `chore` | 위에 안 맞는 잡일 | `chore: remove unused resources` |
| `style` | 공백·포맷만 (의미 변화 없음) | `style: format Gallery.kt` |
| `revert` | 이전 커밋 되돌리기 | `revert: feat: add video scene search` |
| `release` | 버전을 올리고 릴리스 준비 | `release: prepare MoaLog v0.3.1` |

애매하면: 사용자가 달라진 걸 느끼면 `feat`/`fix`, 못 느끼면 나머지.

### 제목

- 영어, 명령형(`add` `fix` `remove`), 소문자로 시작, 마침표 없음, 72자 이내.
- 무엇이 달라지는지가 보이게 쓴다. `fix: search bug` ✗ → `fix: ask for review before saving search results` ✓

### scope (선택)

기존 커밋은 쓰지 않았다. 바뀐 곳이 제목만으로 안 보일 때만 붙인다: `android` · `desktop`(Python 프로토타입) · `eval` · `models`. 예: `fix(android): ...`

### 본문 · 꼬리말

- 본문은 "왜"를 적는다(한글 가능). "무엇"은 diff가 말해 주니, 제목으로 충분하면 생략한다.
- 앱 ID·DB 스키마처럼 이미 설치된 앱에 영향이 큰 변경은 제목에 `!`, 꼬리말에 `BREAKING CHANGE: ...`. 예: `feat!: change application id to com.moalog.personal`

### 나누는 기준 — 한 커밋 = 한 목적

- 기능·수정 / 문서 / 디자인 자료 / 의존성·빌드는 따로 커밋한다.
- 파일·패키지 이동은 내용 수정과 분리한다. 이동만 한 커밋이어야 diff가 읽힌다.
- 빌드와 단위 테스트가 통과하는 상태로 커밋한다: `./gradlew :core:test :app:testDebugUnitTest` (`android/`에서)
- 스테이징은 파일을 지정해서 한다. `git add -A`는 쓰지 않는다(zip·로컬 DB·캡처 같은 잔여 파일이 섞인다).

### 커밋하지 않는 것

개인 사진, 기기에서 복사한 DB(`photolog.db*`), 서명 키(`*.jks`)·`local.properties`, APK. `.gitignore`에 없어도 직접 `git add`로 넣지 않는다.

### Claude가 커밋할 때

- 사용자가 요청할 때만 커밋한다. 요청이 없으면 커밋하지 않고 변경 상태만 보고한다.
- `git push`는 하지 않는다. 사용자가 push하라고 직접 말한 경우만 예외.
- 메시지 마지막에 시스템이 정해 준 `Co-Authored-By:` 꼬리말을 그대로 붙인다.
