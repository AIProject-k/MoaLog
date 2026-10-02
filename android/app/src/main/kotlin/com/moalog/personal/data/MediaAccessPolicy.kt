package com.moalog.personal.data

/** Decides which rows remain visible and whether absence from MediaStore means deletion. */
internal object MediaAccessPolicy {
    enum class Scope { FULL, PARTIAL, NONE }

    data class Reconciliation(val visibleIds: Set<Long>, val deleteMissing: Boolean)

    fun permissionsChanged(previous: List<Boolean>?, current: List<Boolean>): Boolean =
        previous != null && previous != current

    fun scope(apiLevel: Int, full: Boolean, selected: Boolean): Scope = when {
        full -> Scope.FULL
        apiLevel >= 34 && selected -> Scope.PARTIAL
        else -> Scope.NONE
    }

    fun reconcile(scope: Scope, present: Set<Long>): Reconciliation = when (scope) {
        Scope.FULL -> Reconciliation(present, deleteMissing = true)
        Scope.PARTIAL -> Reconciliation(present, deleteMissing = false)
        Scope.NONE -> Reconciliation(emptySet(), deleteMissing = false)
    }
}
