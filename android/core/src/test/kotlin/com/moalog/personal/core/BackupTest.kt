package com.moalog.personal.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupTest {

    private val doc = BackupDoc(
        createdAt = 1_760_000_000L,
        media = listOf(
            BackupMedia(0, "IMG_1.jpg", 1234, 1_690_000_000L, "DCIM/Camera/"),
            BackupMedia(1, "clip.mp4", 99_000, null, null),
        ),
        favorites = listOf(BackupFavorite(0, 123L)),
        albums = listOf(BackupAlbum("내 여행", 100L, listOf(BackupAlbumItem(0, 101L, null), BackupAlbumItem(1, 102L, 4_500L)))),
        feedback = listOf(BackupFeedback(1, "바다", 1, 103L)),
    )

    // ---------- 평문 형식

    @Test fun `JSON으로 쓰고 다시 읽으면 같다`() {
        assertEquals(doc, BackupCodec.decode(BackupCodec.encode(doc)))
    }

    @Test fun `촬영 시각·폴더가 없는 사진과 빈 칸은 빠진 채로 읽힌다`() {
        val back = BackupCodec.decode(BackupCodec.encode(doc))
        assertEquals("clip.mp4", back.media[1].name)
        assertEquals(null, back.media[1].takenAt)
        assertEquals(null, back.media[1].path)
        assertEquals(null, back.albums[0].items[0].sceneMs)
    }

    @Test fun `다른 형식의 파일은 거절한다`() {
        val other = """{"format":"something-else","version":1}""".toByteArray()
        assertFailsWith<BackupException> { BackupCodec.decode(other) }
    }

    @Test fun `더 새로운 버전의 백업은 거절한다`() {
        val newer = """{"format":"moalog-backup","version":2}""".toByteArray()
        assertFailsWith<BackupException> { BackupCodec.decode(newer) }
    }

    @Test fun `JSON이 아니면 읽을 수 없다고 알린다`() {
        assertFailsWith<BackupException> { BackupCodec.decode("그냥 글자".toByteArray()) }
    }

    // ---------- 사진 짝짓기

    private fun cand(id: Long, name: String?, bytes: Long, taken: Long?, path: String? = "DCIM/Camera/") =
        BackupMatcher.Candidate(id, name, bytes, taken, path)

    @Test fun `이름·크기·촬영 시각이 같으면 이어 준다`() {
        val map = BackupMatcher.resolve(doc.media, listOf(cand(900, "IMG_1.jpg", 1234, 1_690_000_000L)))
        assertEquals(mapOf(0 to 900L), map)
    }

    @Test fun `하나로 좁혀지지 않는 같은 지문은 잇지 않는다`() {
        val media = listOf(BackupMedia(0, "IMG.jpg", 10, 5L, "A/"))
        val two = listOf(cand(1, "IMG.jpg", 10, 5L, "B/"), cand(2, "IMG.jpg", 10, 5L, "C/"))
        assertTrue(BackupMatcher.resolve(media, two).isEmpty(), "짝을 모르면 엉뚱한 사진에 붙이면 안 된다")
    }

    @Test fun `같은 지문이 여럿이어도 폴더가 같은 하나면 그것을 잇는다`() {
        val media = listOf(BackupMedia(0, "IMG.jpg", 10, 5L, "A/"))
        val two = listOf(cand(1, "IMG.jpg", 10, 5L, "B/"), cand(2, "IMG.jpg", 10, 5L, "A/"))
        assertEquals(mapOf(0 to 2L), BackupMatcher.resolve(media, two))
    }

    @Test fun `이름이 없는 사진은 잇지 않는다`() {
        val media = listOf(BackupMedia(0, null, 10, 5L, "A/"))
        assertTrue(BackupMatcher.resolve(media, listOf(cand(1, null, 10, 5L, "A/"))).isEmpty())
    }

    @Test fun `기기에 없는 사진은 빠진다`() {
        assertTrue(BackupMatcher.resolve(doc.media, listOf(cand(900, "other.jpg", 1, 1L))).isEmpty())
    }
}
