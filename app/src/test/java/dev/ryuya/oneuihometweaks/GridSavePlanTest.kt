package dev.ryuya.oneuihometweaks

import java.util.List
import org.junit.Assert.*
import org.junit.Test

open class GridSavePlanTest {
    private fun e(id: Int, page: Int, oldX: Int, x: Int): GridSavePlan.Entry? {
        return GridSavePlan.Entry(id, page, oldX, 61, x, 61, "1x1:scale1:rotation0")
    }

    @Test
    open fun previewIdentityDoesNotDependOnModelEnumerationOrder() {
        val a = e(1, 34, 7, 9)
        val b = e(2, 35, 9, 9)
        assertEquals(
            GridSavePlan(List.of<GridSavePlan.Entry?>(a, b)).fingerprint(),
            GridSavePlan(List.of<GridSavePlan.Entry?>(b, a)).fingerprint(),
        )
    }

    @Test
    open fun sourceOrPageChangesInvalidateUserConfirmation() {
        val before = GridSavePlan(List.of<GridSavePlan.Entry?>(e(1, 34, 7, 9))).fingerprint()
        assertNotEquals(
            before,
            GridSavePlan(List.of<GridSavePlan.Entry?>(e(1, 34, 8, 9))).fingerprint(),
        )
        assertNotEquals(
            before,
            GridSavePlan(List.of<GridSavePlan.Entry?>(e(1, 35, 7, 9))).fingerprint(),
        )
    }

    @Test
    open fun countsIncludeOnlyCoordinatesThatWillBeWritten() {
        val p =
            GridSavePlan(
                List.of<GridSavePlan.Entry?>(e(1, 34, 7, 9), e(2, 35, 9, 9), e(3, 34, 1, 9))
            )
        assertEquals(2, p.changed())
        assertEquals(1, p.pages())
    }

    @Test
    open fun duplicateIdentitiesAreRejectedBeforeNativeWrites() {
        assertThrows(
            IllegalArgumentException::class.java,
            { GridSavePlan(List.of<GridSavePlan.Entry?>(e(1, 34, 7, 9), e(1, 35, 1, 9))) },
        )
    }

    @Test
    fun savedConfirmationFingerprintMatchesTheJavaRelease() {
        val plan =
            GridSavePlan(
                java.util.List.of(
                    GridSavePlan.Entry(9, 2, 18, 61, 208, 400, "folder"),
                    GridSavePlan.Entry(3, 1, -20, 75, 9, 61, "icon"),
                )
            )
        assertEquals(
            "48767bb1c312276b6f86196d1acdaef075faf2e71e7f5a567fb660ca86d56731",
            plan.fingerprint(),
        )
    }
}
