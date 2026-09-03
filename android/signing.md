# testkey.jks — 직접 설치용 임시 서명 키

이 키는 **APK를 폰에 바로 설치할 수 있게 하려는 용도만**이다.
비밀번호가 `build.gradle.kts`에 적혀 있으므로 Play Store 업로드 키로 쓰면 안 된다.

Play에 올릴 때는:
1. 새 키스토어를 만들어 **저장소 밖**에 둔다 (`~/.android/photolog-upload.jks` 등)
2. 비밀번호는 `~/.gradle/gradle.properties`나 환경변수로 넘긴다
3. Play App Signing에 업로드 키를 등록한다

이 키를 잃어버려도 문제가 없다 — 다시 만들면 된다.
단 이 키로 설치한 앱 위에 다른 키로 만든 APK는 덮어 설치되지 않으므로
(서명 불일치) 그때는 먼저 지워야 한다.
