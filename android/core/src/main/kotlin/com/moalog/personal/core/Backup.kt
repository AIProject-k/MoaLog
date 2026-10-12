package com.moalog.personal.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 백업 (계획서 MLG-105). 사용자가 넣은 정보만 담는다 — 앨범·즐겨찾기·분류 교정.
 * 사진 원본과 임베딩은 넣지 않는다. 임베딩은 다시 분석하면 되고, 원본은 기기에 그대로 있다.
 *
 * **사진 번호(MediaStore id)는 기기마다 다르다.** 그래서 백업에는 번호 대신 사진의 지문(이름·크기·촬영 시각·
 * 폴더)을 쓰고, 복원할 때 지금 기기의 사진과 짝을 맞춘다. 짝이 불확실한 것은 잇지 않는다.
 */

class BackupException(message: String) : Exception(message)

/** 백업 한 장의 지문. `ref`는 이 파일 안에서만 쓰는 번호다. */
data class BackupMedia(val ref: Int, val name: String?, val bytes: Long, val takenAt: Long?, val path: String?)

data class BackupFavorite(val ref: Int, val ts: Long)

data class BackupAlbumItem(val ref: Int, val added: Long, val sceneMs: Long?)

data class BackupAlbum(val name: String, val created: Long, val items: List<BackupAlbumItem>)

data class BackupFeedback(val ref: Int, val label: String, val verdict: Int, val ts: Long)

data class BackupDoc(
    val createdAt: Long,
    val media: List<BackupMedia>,
    val favorites: List<BackupFavorite>,
    val albums: List<BackupAlbum>,
    val feedback: List<BackupFeedback>,
)

/** 평문 JSON ↔ [BackupDoc]. 형식 이름과 버전을 확인해서 다른 파일을 잘못 읽지 않게 한다. */
object BackupCodec {
    const val FORMAT = "moalog-backup"
    const val VERSION = 1

    fun encode(doc: BackupDoc): ByteArray {
        val media = JSONArray()
        for (m in doc.media) media.put(JSONObject().put("ref", m.ref).put("bytes", m.bytes).apply {
            m.name?.let { put("name", it) }
            m.takenAt?.let { put("taken", it) }
            m.path?.let { put("path", it) }
        })
        val favorites = JSONArray(doc.favorites.map { JSONObject().put("ref", it.ref).put("ts", it.ts) })
        val albums = JSONArray(doc.albums.map { a ->
            JSONObject().put("name", a.name).put("created", a.created).put("items", JSONArray(a.items.map { i ->
                JSONObject().put("ref", i.ref).put("added", i.added).apply { i.sceneMs?.let { put("scene", it) } }
            }))
        })
        val feedback = JSONArray(doc.feedback.map {
            JSONObject().put("ref", it.ref).put("label", it.label).put("verdict", it.verdict).put("ts", it.ts)
        })
        return JSONObject()
            .put("format", FORMAT).put("version", VERSION).put("created_at", doc.createdAt)
            .put("media", media).put("favorites", favorites).put("albums", albums).put("feedback", feedback)
            .toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): BackupDoc {
        val root = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) {
            throw BackupException("백업 파일을 읽을 수 없습니다")
        }
        if (root.optString("format") != FORMAT) throw BackupException("MoaLog 백업 파일이 아닙니다")
        val version = root.optInt("version", -1)
        if (version < 1) throw BackupException("백업 버전을 알 수 없습니다")
        if (version > VERSION) throw BackupException("이 백업은 더 새로운 앱에서 만들었습니다. 앱을 업데이트해 주세요")

        val media = root.getJSONArray("media").objects { o ->
            BackupMedia(
                ref = o.getInt("ref"), name = o.optStringOrNull("name"), bytes = o.optLong("bytes", 0),
                takenAt = if (o.has("taken")) o.getLong("taken") else null, path = o.optStringOrNull("path"),
            )
        }
        val favorites = root.getJSONArray("favorites").objects { o -> BackupFavorite(o.getInt("ref"), o.getLong("ts")) }
        val albums = root.getJSONArray("albums").objects { o ->
            BackupAlbum(
                name = o.getString("name"), created = o.getLong("created"),
                items = o.getJSONArray("items").objects { i ->
                    BackupAlbumItem(i.getInt("ref"), i.getLong("added"), if (i.has("scene")) i.getLong("scene") else null)
                },
            )
        }
        val feedback = root.getJSONArray("feedback").objects { o ->
            BackupFeedback(o.getInt("ref"), o.getString("label"), o.getInt("verdict"), o.getLong("ts"))
        }
        return BackupDoc(root.optLong("created_at", 0), media, favorites, albums, feedback)
    }

    private inline fun <T> JSONArray.objects(map: (JSONObject) -> T): List<T> =
        List(length()) { map(getJSONObject(it)) }

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key)) getString(key) else null
}

/**
 * 암호화. 비밀번호에서 PBKDF2로 키를 만들고 AES-256-GCM으로 감싼다. GCM이라 틀린 암호와 변조를 같은 방식으로 잡는다.
 * 파일 모양: `MAGIC(8) · salt(16) · iv(12) · 암호문+태그`.
 */
object BackupCrypto {
    private val MAGIC = "MOALOGB1".toByteArray(Charsets.US_ASCII)
    private const val SALT = 16
    private const val IV = 12
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    fun seal(plain: ByteArray, password: CharArray): ByteArray {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT).also(rnd::nextBytes)
        val iv = ByteArray(IV).also(rnd::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(password, salt), "AES"), GCMParameterSpec(TAG_BITS, iv))
        }
        return MAGIC + salt + iv + cipher.doFinal(plain)
    }

    /** 비밀번호가 틀리거나 파일이 바뀌었으면 [BackupException]을 던진다. */
    fun open(sealed: ByteArray, password: CharArray): ByteArray {
        val header = MAGIC.size + SALT + IV
        if (sealed.size <= header || !sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupException("암호화된 MoaLog 백업 파일이 아닙니다")
        }
        val salt = sealed.copyOfRange(MAGIC.size, MAGIC.size + SALT)
        val iv = sealed.copyOfRange(MAGIC.size + SALT, header)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key(password, salt), "AES"), GCMParameterSpec(TAG_BITS, iv))
        }
        return try { cipher.doFinal(sealed, header, sealed.size - header) } catch (e: AEADBadTagException) {
            throw BackupException("암호가 맞지 않거나 백업 파일이 손상되었습니다")
        }
    }

    private fun key(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}

/**
 * 백업의 사진을 지금 기기의 사진과 짝짓는다. 이름·크기·촬영 시각이 모두 같아야 잇는다.
 * 같은 지문이 둘 이상이면 폴더까지 같은 것만 남긴다. 그래도 하나로 좁혀지지 않으면 **잇지 않는다** —
 * 엉뚱한 사진에 즐겨찾기나 앨범이 붙는 것이 빠지는 것보다 나쁘다.
 */
object BackupMatcher {

    data class Candidate(val mediaId: Long, val name: String?, val bytes: Long, val takenAt: Long?, val path: String?)

    /** 백업의 ref → 지금 기기의 사진 id. 짝이 없는 ref는 빠진다. */
    fun resolve(media: List<BackupMedia>, candidates: List<Candidate>): Map<Int, Long> {
        val byFingerprint = candidates.filter { it.name != null }.groupBy { fingerprint(it.name, it.bytes, it.takenAt) }
        val out = HashMap<Int, Long>()
        for (m in media) {
            if (m.name == null) continue
            val same = byFingerprint[fingerprint(m.name, m.bytes, m.takenAt)].orEmpty()
            val pick = if (same.size == 1) same.single() else same.filter { it.path == m.path }.singleOrNull()
            if (pick != null) out[m.ref] = pick.mediaId
        }
        return out
    }

    private fun fingerprint(name: String?, bytes: Long, takenAt: Long?) = "$name|$bytes|$takenAt"
}
