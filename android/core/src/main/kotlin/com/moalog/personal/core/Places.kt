package com.moalog.personal.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 장소 한 곳. [aliases]는 다른 부르는 말("강릉"의 "강릉시"). [radiusKm] 안에서 찍은 사진을 그 장소로 본다.
 */
data class Place(
    val name: String,
    val aliases: List<String>,
    val lat: Double,
    val lon: Double,
    val radiusKm: Double,
)

/**
 * 장소 표. GPS 좌표를 "강릉시" 같은 이름으로 바꾸고, 검색어의 장소 이름을 좌표 조건으로 바꾼다.
 *
 * 표는 앱에 든 `assets/places.tsv`에서 읽는다. 파일이 없으면 빈 표이고, 그때는 장소 검색과 장소 이름이
 * 꺼진 것과 같다 — 기존 동작이 그대로다. 네트워크로 역지오코딩하지 않는다(온디바이스 약속).
 *
 * 형식(탭으로 구분, `#`은 주석):
 *   이름 \t 다른 이름(|로 구분, 없으면 빈칸) \t 위도 \t 경도 \t 반경(km)
 */
class PlaceIndex(private val places: List<Place>) {

    /** 검색어에서 장소로 알아볼 수 있는 말 전부(이름과 다른 이름). */
    val names: Set<String> = places.flatMapTo(HashSet()) { listOf(it.name) + it.aliases }

    fun find(word: String): Place? = places.firstOrNull { it.name == word || word in it.aliases }

    /** 좌표가 [place] 반경 안에 드는가. */
    fun within(place: Place, lat: Double, lon: Double): Boolean = distanceKm(lat, lon, place.lat, place.lon) <= place.radiusKm

    /** 좌표가 든 장소 중 가장 가까운 것. 어디에도 안 들면 null. */
    fun nearest(lat: Double, lon: Double): Place? =
        places.filter { within(it, lat, lon) }.minByOrNull { distanceKm(lat, lon, it.lat, it.lon) }

    companion object {
        val EMPTY = PlaceIndex(emptyList())

        fun parse(tsv: String): PlaceIndex = PlaceIndex(
            tsv.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val c = line.split('\t')
                    if (c.size < 5) return@mapNotNull null
                    val lat = c[2].trim().toDoubleOrNull() ?: return@mapNotNull null
                    val lon = c[3].trim().toDoubleOrNull() ?: return@mapNotNull null
                    val radius = c[4].trim().toDoubleOrNull() ?: return@mapNotNull null
                    Place(
                        name = c[0].trim(),
                        aliases = c[1].split('|').map { it.trim() }.filter { it.isNotEmpty() },
                        lat = lat, lon = lon, radiusKm = radius,
                    )
                }
                .filter { it.name.isNotEmpty() }
                .toList()
        )

        /** 두 좌표 사이의 거리(km). 지구를 구로 본 하버사인 공식. */
        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(a))
        }
    }
}
