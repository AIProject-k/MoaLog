package kr.slz.photolog.core

import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 규칙 태그 · 출처 판정. `test_pipeline.py` 3번을 옮기고 안드로이드 출처 규칙을 더했다.
 * 전부 순수 함수라 기기가 필요 없다.
 */
class RuleTagsTest {

    private val config = JSONObject(File("../../config.json").canonicalFile.readText())
    private val seoul = ZoneId.of("Asia/Seoul")
    private val tagger = RuleTagger.from(config, seoul)

    private fun labels(m: PhotoMeta) = tagger.tags(m).map { it.label }

    // ---------- 데스크톱에서 가져온 판정 ----------

    @Test
    fun `카메라 EXIF가 있으면 카메라촬영`() {
        assertEquals("카메라촬영", tagger.provenanceTag(
            PhotoMeta(width = 4032, height = 3024, cameraModel = "Apple iPhone 15")))
    }

    @Test
    fun `카메라가 없고 화면비가 폰 화면이면 스크린샷`() {
        assertEquals("스크린샷", tagger.provenanceTag(PhotoMeta(width = 1080, height = 1920)))
    }

    @Test
    fun `촬영시각을 모르면 야간을 붙이지 않는다`() {
        val night = 1786000000L        // 어떤 값이든, 출처가 파일 시각이면 판정하지 않는다
        assertFalse("야간" in labels(PhotoMeta(
            width = 1080, height = 1920, takenAt = night, takenAtSource = TimeSource.FILE)),
            "파일 시각으로 야간을 판정하면 새벽에 내려받은 낮 사진이 야간이 된다")
    }

    @Test
    fun `EXIF 촬영시각이 밤이면 야간을 붙인다`() {
        // 2026-08-01 23:00 KST
        val ts = java.time.LocalDateTime.of(2026, 8, 1, 23, 0).atZone(seoul).toEpochSecond()
        assertTrue("야간" in labels(PhotoMeta(
            width = 4032, height = 3024, cameraModel = "Pixel 8",
            takenAt = ts, takenAtSource = TimeSource.EXIF)))
    }

    @Test
    fun `GPS가 있으면 위치있음`() {
        assertTrue("위치있음" in labels(PhotoMeta(width = 100, height = 100, hasGps = true)))
    }

    // ---------- 안드로이드에서 새로 생긴 판정 (ANDROID.md §4.4) ----------

    @Test
    fun `스크린샷 폴더가 화면비보다 우선한다`() {
        // 정사각형이라 화면비 휴리스틱으로는 절대 안 잡히는 스크린샷
        assertEquals("스크린샷", tagger.provenanceTag(
            PhotoMeta(width = 1000, height = 1000, relativePath = "Pictures/Screenshots/")))
    }

    @Test
    fun `카톡 저장 사진은 메신저저장이다`() {
        assertEquals("메신저저장", tagger.provenanceTag(
            PhotoMeta(width = 1280, height = 960, relativePath = "Pictures/KakaoTalk/")))
        assertEquals("메신저저장", tagger.provenanceTag(
            PhotoMeta(width = 1280, height = 960, ownerPackage = "com.kakao.talk")))
    }

    @Test
    fun `DCIM 아래 카톡 폴더를 카메라촬영으로 착각하지 않는다`() {
        assertEquals("메신저저장", tagger.provenanceTag(
            PhotoMeta(width = 1280, height = 960, relativePath = "DCIM/KakaoTalk/")),
            "'dcim/'로 뭉뚱그리면 카메라촬영이 되어 버린다")
    }

    @Test
    fun `DCIM 카메라 폴더는 EXIF가 없어도 카메라촬영`() {
        assertEquals("카메라촬영", tagger.provenanceTag(
            PhotoMeta(width = 4032, height = 3024, relativePath = "DCIM/Camera/")))
    }

    @Test
    fun `출처를 모르면 저장 다운로드로 떨어진다`() {
        assertEquals("저장/다운로드", tagger.provenanceTag(PhotoMeta(width = 800, height = 600)))
    }

    @Test
    fun `규칙은 config에서 온다 — 코드에 박혀 있지 않다`() {
        val empty = RuleTagger(emptyList(), 0.02, emptyList(), seoul)
        assertEquals("저장/다운로드", empty.provenanceTag(
            PhotoMeta(width = 1080, height = 1920, relativePath = "Pictures/Screenshots/")),
            "규칙을 비웠는데도 판정이 나오면 코드에 박혀 있는 것이다")
        assertTrue(config.has("provenance_rules"), "config.json에 provenance_rules가 없다")
    }

    @Test
    fun `사진 한 장에 출처 태그는 정확히 하나다`() {
        val cases = listOf(
            PhotoMeta(width = 1080, height = 1920, relativePath = "Pictures/Screenshots/"),
            PhotoMeta(width = 4032, height = 3024, cameraModel = "Pixel 8"),
            PhotoMeta(width = 1280, height = 960, ownerPackage = "com.kakao.talk"),
            PhotoMeta(width = 800, height = 600),
        )
        val provenance = setOf("카메라촬영", "스크린샷", "메신저저장", "다운로드", "저장/다운로드")
        for (m in cases) {
            assertEquals(1, labels(m).count { it in provenance },
                "출처 태그가 여러 개면 앨범이 겹친다: ${labels(m)}")
        }
    }
}
