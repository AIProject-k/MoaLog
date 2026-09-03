// 순수 Kotlin/JVM. 안드로이드 SDK도 기기도 없이 돌아간다 —
// 포팅에서 위험한 로직(토크나이저, 분류, 규칙태그, 연사 판정, 질의 파서)을
// 여기 몰아넣고 JVM 테스트로 못박는다. :app은 이 모듈을 쓰기만 한다.
plugins {
    kotlin("jvm") version "1.9.24"
}

dependencies {
    // org.json은 안드로이드 플랫폼에 내장돼 있다 → 앱에서는 의존성이 늘지 않는다.
    compileOnly("org.json:json:20240303")
    testImplementation("org.json:json:20240303")
    testImplementation(kotlin("test"))
}

kotlin { jvmToolchain(21) }

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed"); showStandardStreams = true }
}
