package ai.kilocode.client.app

import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Extends BasePlatformTestCase so the IntelliJ Application exists; assertions run against the
// real PropertiesComponent store and the key is cleaned up in teardown.
class CommitModelStoreTest : BasePlatformTestCase() {
    private val props: PropertiesComponent get() = PropertiesComponent.getInstance()

    override fun tearDown() {
        props.unsetValue("costrict.commitMessage.model")
        super.tearDown()
    }

    fun `test unset selection is null`() {
        props.unsetValue("costrict.commitMessage.model")
        assertNull(CommitModelStore.selection(props))
    }

    fun `test stores and reads provider model pair`() {
        CommitModelStore.store(props, "anthropic", "claude-sonnet")
        assertEquals("anthropic" to "claude-sonnet", CommitModelStore.selection(props))
    }

    fun `test clearing selection unsets the key`() {
        CommitModelStore.store(props, "anthropic", "claude-sonnet")
        CommitModelStore.store(props, null, null)
        assertNull(CommitModelStore.selection(props))
    }

    fun `test blank parts are ignored`() {
        props.setValue("costrict.commitMessage.model", "/claude")
        assertNull(CommitModelStore.selection(props))
        props.setValue("costrict.commitMessage.model", "anthropic/")
        assertNull(CommitModelStore.selection(props))
    }
}
