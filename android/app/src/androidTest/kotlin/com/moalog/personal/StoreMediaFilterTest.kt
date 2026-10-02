package com.moalog.personal

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moalog.personal.core.Events
import com.moalog.personal.core.Tag
import com.moalog.personal.core.TimeSource
import com.moalog.personal.data.MediaRow
import com.moalog.personal.data.MetaResult
import com.moalog.personal.data.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 앨범을 사진·영상으로 가르는 조회. "동영상도 따로 분류"가 기대는 바닥이다 —
 * 태그·폴더·내 앨범·즐겨찾기·기록이 모두 같은 규칙(영상 id는 음수)으로 갈라져야
 * 앨범 목록의 개수, 표지, 열었을 때의 내용이 서로 어긋나지 않는다.
 */
@RunWith(AndroidJUnit4::class)
class StoreMediaFilterTest {

    // 사진 1(카메라·바다)·2(핸드폰·음식)·3(카메라), 영상 -1(카메라·바다)·-2(카메라·바다·여행)
    // 시각: 1이 가장 오래됐고 -2가 가장 최근이다.
    private fun withStore(block: (Store) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val dbFile = File(target.cacheDir, "media-filter-test.db")
        SQLiteDatabase.deleteDatabase(dbFile)
        val ctx = object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = dbFile
        }
        val store = Store(ctx)
        try {
            val rows = listOf(
                Triple(1L, "Camera", 1_000L), Triple(2L, "Phone", 2_000L), Triple(3L, "Camera", 3_000L),
                Triple(-1L, "Camera", 4_000L), Triple(-2L, "Camera", 5_000L),
            )
            store.upsertDiscovered(rows.map { (id, bucket, _) ->
                MediaRow(id, "content://test/$id", "f$id", bucket, "DCIM/$bucket/", null,
                    if (id < 0) "video/mp4" else "image/jpeg", 100, 10, 10, 0, 1, 0,
                    durationMs = if (id < 0) 12_000L else null)
            })
            val tags = mapOf(
                1L to listOf("바다"), 2L to listOf("음식"), -1L to listOf("바다"), -2L to listOf("바다", "여행"),
            )
            for ((id, _, t) in rows) store.saveMeta(
                id, MetaResult(t, TimeSource.EXIF, null, null, null),
                tags[id].orEmpty().map { Tag(it, 0.9f, "clip") },
            )
            block(store)
        } finally {
            store.close()
            SQLiteDatabase.deleteDatabase(dbFile)
        }
    }

    private fun Store.ids(label: String, kind: String) = photosByTag(label, 10, 0, kind).map { it.id }

    @Test fun 태그는_종류별로_따로_센다() = withStore { store ->
        val all = store.tagCounts("all").associateBy { it.label }
        assertEquals(3, all.getValue("바다").count)
        assertEquals("전체 보기에서도 영상이 몇 개인지 안다", 2, all.getValue("바다").videos)
        assertEquals(0, all.getValue("음식").videos)

        val video = store.tagCounts("video").associateBy { it.label }
        assertEquals(setOf("바다", "여행"), video.keys)      // 사진뿐인 '음식'은 영상 분류에 없다
        assertEquals(2, video.getValue("바다").count)
        assertEquals(1, video.getValue("여행").count)

        val image = store.tagCounts("image").associateBy { it.label }
        assertEquals(setOf("바다", "음식"), image.keys)      // 영상에만 있는 '여행'은 사진 분류에 없다
        assertEquals(1, image.getValue("바다").count)
        assertEquals(0, image.getValue("바다").videos)
    }

    @Test fun 태그_앨범을_열면_그_종류만_최신순으로_나온다() = withStore { store ->
        assertEquals(listOf(-2L, -1L, 1L), store.ids("바다", "all"))
        assertEquals(listOf(-2L, -1L), store.ids("바다", "video"))
        assertEquals(listOf(1L), store.ids("바다", "image"))
        assertTrue(store.ids("음식", "video").isEmpty())
    }

    @Test fun 기기_폴더도_종류별로_갈린다() = withStore { store ->
        val all = store.buckets(1, "all").associateBy { it.name }
        assertEquals(4, all.getValue("Camera").count)
        assertEquals(2, all.getValue("Camera").videos)
        assertEquals(1, all.getValue("Phone").count)

        val video = store.buckets(1, "video")
        assertEquals(listOf("Camera"), video.map { it.name })
        assertEquals(2, video.single().count)
        assertEquals("표지도 영상에서 뽑는다", listOf(-2L, -1L), video.single().coverIds)

        assertEquals(listOf(3L, 1L), store.photosByBucket("Camera", 10, 0, "image").map { it.id })
        assertEquals(listOf(-2L, -1L), store.photosByBucket("Camera", 10, 0, "video").map { it.id })
    }

    @Test fun 내_앨범은_종류별_개수와_내용을_준다() = withStore { store ->
        val mixed = store.createUserAlbum("섞인 앨범")
        val onlyVideo = store.createUserAlbum("영상뿐")
        store.addToUserAlbum(mixed, listOf(1L, -1L))
        store.addToUserAlbum(onlyVideo, listOf(-2L))

        val all = store.userAlbums("all").associateBy { it.name }
        assertEquals(2, all.getValue("섞인 앨범").count)
        assertEquals(1, all.getValue("섞인 앨범").videos)

        val image = store.userAlbums("image").associateBy { it.name }
        assertEquals(1, image.getValue("섞인 앨범").count)
        // 사진이 없어도 행은 남는다 — 걸러낼지는 화면이 정한다
        assertEquals(0, image.getValue("영상뿐").count)

        val video = store.userAlbums("video").associateBy { it.name }
        assertEquals(1, video.getValue("섞인 앨범").count)
        assertEquals(listOf(-1L), video.getValue("섞인 앨범").coverIds)

        assertEquals(listOf(1L), store.userAlbumPhotos(mixed, 10, 0, "image").map { it.id })
        assertEquals(listOf(-1L), store.userAlbumPhotos(mixed, 10, 0, "video").map { it.id })
    }

    @Test fun 즐겨찾기도_종류별로_센다() = withStore { store ->
        for (id in listOf(1L, 2L, -1L)) store.setFavorite(id, true)
        assertEquals(3, store.favoriteCounts("all").count)
        assertEquals(1, store.favoriteCounts("all").videos)
        assertEquals(1, store.favoriteCounts("video").count)
        assertEquals(2, store.favoriteCounts("image").count)
        assertEquals(listOf(-1L), store.favorites(10, 0, "video").map { it.id })
        assertEquals(listOf(2L, 1L), store.favorites(10, 0, "image").map { it.id })
    }

    @Test fun 기록은_그_종류가_있는_것만_나온다() = withStore { store ->
        store.replaceEvents(listOf(
            Events.Event(listOf(1L, -1L), 1_000, 4_000, null, null, listOf(1L), "강릉", "바다 여행"),
            Events.Event(listOf(2L), 2_000, 2_000, null, null, listOf(2L), null, "점심"),
        ))
        val all = store.events(10, "all").associateBy { it.title }
        assertEquals(setOf("바다 여행", "점심"), all.keys)
        assertEquals(2, all.getValue("바다 여행").count)
        assertEquals(1, all.getValue("바다 여행").videos)
        assertEquals(0, all.getValue("점심").videos)

        val video = store.events(10, "video")
        assertEquals("영상이 없는 기록은 영상 분류에 없다", listOf("바다 여행"), video.map { it.title })
        assertEquals(1, video.single().count)
        assertEquals(listOf(-1L), video.single().coverIds)

        val eventId = video.single().id
        assertEquals(listOf(-1L), store.eventPhotos(eventId, "video").map { it.id })
        assertEquals(listOf(1L), store.eventPhotos(eventId, "image").map { it.id })
        assertEquals(2, store.events(10, "image").size)
    }

    @Test fun 보이는_항목이_없는_기록은_내놓지_않는다() = withStore { store ->
        store.replaceEvents(listOf(Events.Event(listOf(1L), 1_000, 1_000, null, null, listOf(1L), null, "옛 기록")))
        assertEquals(1, store.events(10).size)
        store.setAccessible(emptySet(), video = false)          // 접근이 줄어 사진 1이 숨겨진 경우
        assertTrue(store.events(10).isEmpty())
    }
}
