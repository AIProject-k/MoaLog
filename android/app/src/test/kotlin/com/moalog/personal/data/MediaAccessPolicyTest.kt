package com.moalog.personal.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaAccessPolicyTest {
    @Test
    fun permissionChangesAreDetectedAfterAnInitialSnapshot() {
        assertTrue(MediaAccessPolicy.permissionsChanged(
            previous = listOf(true, true, false), current = listOf(true, false, true),
        ))
        assertFalse(MediaAccessPolicy.permissionsChanged(previous = null, current = listOf(false, false, false)))
    }

    @Test
    fun accessScopeUsesFullGrantBeforeSelectedGrant() {
        assertEquals(MediaAccessPolicy.Scope.FULL, MediaAccessPolicy.scope(35, full = true, selected = true))
    }

    @Test
    fun selectedMediaGrantIsPartialOnlyOnAndroid14AndLater() {
        assertEquals(MediaAccessPolicy.Scope.PARTIAL, MediaAccessPolicy.scope(34, full = false, selected = true))
        assertEquals(MediaAccessPolicy.Scope.NONE, MediaAccessPolicy.scope(33, full = false, selected = true))
    }

    @Test
    fun partialAccessShowsSelectedItemsWithoutPruningHiddenRows() {
        val plan = MediaAccessPolicy.reconcile(MediaAccessPolicy.Scope.PARTIAL, setOf(-7L, -9L))

        assertEquals(setOf(-7L, -9L), plan.visibleIds)
        assertFalse(plan.deleteMissing)
    }

    @Test
    fun fullAccessPrunesItemsMissingFromMediaStore() {
        val plan = MediaAccessPolicy.reconcile(MediaAccessPolicy.Scope.FULL, setOf(3L, 5L))

        assertEquals(setOf(3L, 5L), plan.visibleIds)
        assertTrue(plan.deleteMissing)
    }

    @Test
    fun noAccessHidesKnownItemsWithoutPruningTheirAlbumLinks() {
        val plan = MediaAccessPolicy.reconcile(MediaAccessPolicy.Scope.NONE, emptySet())

        assertTrue(plan.visibleIds.isEmpty())
        assertFalse(plan.deleteMissing)
    }
}
