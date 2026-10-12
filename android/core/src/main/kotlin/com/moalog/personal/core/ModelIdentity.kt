package com.moalog.personal.core

import java.security.MessageDigest

/**
 * 모델 신원 (계획서 MLG-103 · 외부 검토 문제 3).
 *
 * 신원이 같으면 기존 임베딩을 그대로 쓰고, 다르면 전부 다시 분석한다(`Store.checkModel`).
 * 그래서 신원은 **모델 내용**에서 나와야 한다. 파일 크기만 보면 같은 크기의 다른 모델을 같은 모델로 본다.
 *
 * 다만 지금 배포본은 예전 식(파일 길이)으로 이미 임베딩을 만들어 둔 사용자가 많다. 식을 바꾸면 그 사용자 전원이
 * 1만 장을 다시 분석한다. 그래서 **배포본과 같은 manifest면 예전 신원을 그대로 쓰고**, 다른 모델부터 해시로 가른다.
 */
object ModelIdentity {

    /**
     * @param legacy 예전 식으로 만든 신원(배포본용)
     * @param files manifest의 파일 이름 → SHA-256
     * @param shipped 지금 배포본의 manifest. 같으면 [legacy]를 돌려준다.
     */
    fun of(legacy: String, files: Map<String, String>, shipped: Map<String, String>): String {
        if (files == shipped) return legacy
        val digest = MessageDigest.getInstance("SHA-256")
        for ((name, sha) in files.toSortedMap()) digest.update("$name=${sha.lowercase()}\n".toByteArray(Charsets.UTF_8))
        return "sha@" + digest.digest().take(6).joinToString("") { "%02x".format(it) }
    }
}
