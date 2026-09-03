plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "kr.slz.photolog"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.slz.photolog"
        minSdk = 30                     // createTrashRequest · GENERATION_* · setRequireOriginal
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 직접 설치용 임시 키. Play 업로드 키로 쓰면 안 된다 — 이유는 signing.md 참조.
        create("test") {
            storeFile = rootProject.file("testkey.jks")
            storePassword = "photolog"
            keyAlias = "photolog"
            keyPassword = "photolog"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("test")
            // R8을 끈다. ORT의 JNI 진입점과 Compose를 지키려면 규칙을 써야 하는데,
            // 첫 배포본에서 조용히 깨지는 것보다 APK가 조금 큰 편이 낫다.
            // 크기가 문제가 되면 그때 규칙을 쓰고 켠다 — 모델이 155MB라 코드 축소 이득은 작다.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("androidTest").java.srcDirs("src/androidTest/kotlin")
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // .onnx를 압축하지 않는다. ORT는 파일 경로로 mmap하듯 읽고, 압축해 두면
        // 설치 시 풀리지 않아 우리가 assets에서 복사할 때 두 번 손해다.
        // int8 모델은 이미 엔트로피가 높아 압축 이득도 거의 없다.
        androidResources.noCompress += listOf("onnx")
    }
}

dependencies {
    implementation(project(":core"))

    // Compose. BOM으로 버전을 한 곳에 묶는다.
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    // 백그라운드 인덱싱. Android 14 포그라운드 타입과 15 시간제한을 흡수한다(§2)
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    // EXIF 헤더만 읽는다. 전체 디코드 없이 ~1ms (§4.5)
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // content URI 썸네일 로딩 + 메모리/디스크 캐시. 직접 만들면 이보다 코드가 많다
    implementation("io.coil-kt:coil-compose:2.6.0")
    // 데스크톱과 같은 1.20.x. 같은 .onnx를 같은 런타임 계열로 돌려야 패리티가 의미를 갖는다.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
