package com.moalog.personal.core

/**
 * 영상에서 어느 시점의 프레임을 볼지. 저장하는 시각은 **요청한 재생 위치**다 —
 * 실제로 디코드된 프레임은 키프레임 사정으로 조금 어긋날 수 있어 화면에는 "약"을 붙인다.
 *
 * 예전엔 "10초마다, 최대 24장"이었다. 그러면 1시간짜리 영상은 2분 30초마다 한 장이라
 * 그 사이에 잠깐 나온 장면은 아예 못 찾았다. 간격을 5초로 좁히고 상한을 96장으로 올린다:
 *  - 8분 이하 영상은 5초마다 본다
 *  - 그보다 긴 영상은 96장을 고르게 나눈다 (1시간이면 약 38초 간격)
 * 한 장에 추출+인코딩 ≈ 0.1~0.2초라, 가장 긴 경우도 영상 하나에 20초 안쪽이다.
 */
object VideoSampling {
    const val STEP_MS = 5_000L
    const val MAX_FRAMES = 96

    /**
     * 프레임을 어떻게 뽑는가: 요청 시각 **직전의 키프레임**(`OPTION_PREVIOUS_SYNC`).
     *
     * 정확한 프레임(`OPTION_CLOSEST`)은 앞 키프레임부터 그 자리까지 전부 디코드해서 느리다.
     * Galaxy S23에서 영상 12개(720p~4K) 45프레임을 재니 평균 300ms인데 직전 키프레임은 101ms였다.
     * 키프레임 간격이 긴 영상(화면 녹화 등)에서는 정확한 프레임의 비용이 끝없이 커진다.
     * 대가로 CLIP이 보는 프레임이 정확한 프레임과 조금 다르다(임베딩 코사인 평균 0.97, 중앙값 0.98).
     * 이득도 있다: 재생은 `seekTo`가 **직전 키프레임**에서 시작하므로 색인한 프레임, 결과 카드의
     * 썸네일, 재생이 시작되는 장면이 모두 같은 프레임이 된다.
     */
    const val FRAME_MODE = "prev-sync"

    /**
     * 샘플링 방식의 도장. 간격·상한·프레임 방식 중 하나만 바뀌어도 값이 달라져서, 예전 방식으로
     * 분석된 영상을 워커가 한 번 다시 돌리게 한다 — 도장을 손으로 올리는 걸 잊어도 된다.
     */
    const val STAMP = "${STEP_MS}x$MAX_FRAMES-$FRAME_MODE"

    fun positions(durationMs: Long): List<Long> {
        if (durationMs <= 0) return emptyList()
        val count = ((durationMs / STEP_MS) + 1).coerceIn(1, MAX_FRAMES.toLong()).toInt()
        return if (count == 1) listOf(durationMs / 2)
        else (0 until count).map { (durationMs - 1) * it / (count - 1) }
    }

    fun time(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        else "%d:%02d".format(s / 60, s % 60)
    }

    /**
     * 한 영상에서 잘 맞은 장면 몇 개. 점수 순으로 고르되 **서로 [minGapMs] 이상 떨어진 것만**
     * 남긴다 — 5초 간격 샘플이라 같은 장면이 0:10·0:15·0:20으로 세 번 나오면 쓸모가 없다.
     */
    fun topScenes(hits: List<Pair<Long, Float>>, limit: Int, minGapMs: Long = 10_000): List<Pair<Long, Float>> {
        val out = ArrayList<Pair<Long, Float>>(limit)
        for (h in hits.sortedByDescending { it.second }) {
            if (out.size >= limit) break
            if (out.none { kotlin.math.abs(it.first - h.first) < minGapMs }) out.add(h)
        }
        return out
    }
}

/**
 * 영상 컨테이너의 촬영 정보. 사진의 EXIF에 해당한다.
 *
 * 이게 없으면 영상은 전부 '파일 시각'이 되어 "작년 여름 여행 영상"이 안 잡히고,
 * 추억·기록 묶기에서도 빠진다. `MediaMetadataRetriever`가 주는 문자열을 여기서 푼다 —
 * 안드로이드 없이 검증하려고 `:core`에 둔다.
 */
object VideoMeta {
    private val DATE = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})""")
    private val ISO6709 = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""")

    /**
     * `20260925T123456.000Z` 형식. 컨테이너 시각은 UTC다.
     * 촬영 정보가 없는 영상은 흔히 `19040101T000000.000Z`(MP4 기준 시각)나 0을 채워 두므로
     * 2000년 이전 값은 모르는 것으로 본다.
     */
    fun parseDate(raw: String?): Long? {
        val m = DATE.find(raw?.trim() ?: return null) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        val epoch = runCatching {
            java.time.LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
                .toEpochSecond(java.time.ZoneOffset.UTC)
        }.getOrNull() ?: return null
        return epoch.takeIf { y.toInt() >= 2000 }
    }

    /** ISO 6709 `+37.5665+126.9780/`. (0,0)은 GPS를 못 잡은 쓰레기값이라 버린다. */
    fun parseLocation(raw: String?): Pair<Double, Double>? {
        val m = ISO6709.find(raw?.trim() ?: return null) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        if (lat == 0.0 && lon == 0.0) return null
        return lat to lon
    }
}
