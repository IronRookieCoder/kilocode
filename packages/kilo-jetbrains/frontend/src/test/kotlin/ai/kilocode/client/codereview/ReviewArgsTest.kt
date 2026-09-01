// kilocode_change - new file
package ai.kilocode.client.codereview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReviewArgsTest {
    @Test
    fun `args mirror costrict reviewContext syntax`() {
        assertEquals("", ReviewTarget.args(ReviewTarget.Changes))
        assertEquals("@/src/app.ts", ReviewTarget.args(ReviewTarget.File("src/app.ts")))
        assertEquals("@/packages/api", ReviewTarget.args(ReviewTarget.Directory("packages/api")))
        assertEquals("@/src/app.ts:15-18", ReviewTarget.args(ReviewTarget.Selection("src/app.ts", 15, 18)))
    }

    @Test
    fun `relative handles windows separators and rejects outside paths`() {
        assertEquals("src/Main.kt", ReviewTarget.relative("""F:\repo\src\Main.kt""", """F:\repo"""))
        assertEquals("src/Main.kt", ReviewTarget.relative("F:/repo/src/Main.kt", "F:/repo/"))
        assertNull(ReviewTarget.relative("F:/other/Main.kt", "F:/repo"))
        assertNull(ReviewTarget.relative("F:/repo", "F:/repo"))
    }

    @Test
    fun `editor target prefers selection and falls back to file`() {
        assertEquals(
            ReviewTarget.Selection("src/app.ts", 15, 18),
            ReviewTarget.fromEditor("F:/repo/src/app.ts", "F:/repo", 15..18),
        )
        assertEquals(
            ReviewTarget.File("src/app.ts"),
            ReviewTarget.fromEditor("F:/repo/src/app.ts", "F:/repo", null),
        )
        assertNull(ReviewTarget.fromEditor("F:/other/app.ts", "F:/repo", null))
    }
}
