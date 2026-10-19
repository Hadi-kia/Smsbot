package com.hadi.smsbot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchPlannerTest {
    @Test fun emptyRecipientsGiveNoBatches() {
        assertTrue(BatchPlanner.groupIndices(0, 20).isEmpty())
        assertTrue(BatchPlanner.groupIndices(-1, 20).isEmpty())
    }
    @Test fun exactBoundaryMakesOneBatch() {
        val groups = BatchPlanner.groupIndices(20, 20)
        assertEquals(1, groups.size)
        assertEquals((0..19).toList(), groups.single())
    }
    @Test fun partialLastBatch() {
        val groups = BatchPlanner.groupIndices(57, 20)
        assertEquals(listOf(20, 20, 17), groups.map { it.size })
    }
    @Test fun largerThanLocalMaximumIsCapped() {
        val groups = BatchPlanner.groupIndices(520, 99999)
        assertEquals(listOf(256, 256, 8), groups.map { it.size })
    }
    @Test fun zeroOrNegativeGroupSizeClampsToOne() {
        assertEquals(3, BatchPlanner.groupIndices(3, 0).size)
        assertEquals(3, BatchPlanner.groupIndices(3, -100).size)
    }
    @Test fun allIndicesAreUniqueAndCovered() {
        val groups = BatchPlanner.groupIndices(101, 17).flatten()
        assertEquals(101, groups.size)
        assertEquals(101, groups.toSet().size)
        assertEquals((0 until 101).toList(), groups)
    }
    @Test fun localGroupMaxDoesNotClaimPlatformLimits() {
        assertFalse(BatchPlanner.groupIndices(300, 100).isEmpty())
        assertEquals(3, BatchPlanner.groupIndices(300, 100).size)
    }
    @Test fun oneRecipient() {
        assertEquals(listOf(listOf(0)), BatchPlanner.groupIndices(1, 20))
    }
}
