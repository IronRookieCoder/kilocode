package ai.kilocode.client.session.views.question

import ai.kilocode.client.plugin.KiloBundle
import ai.kilocode.client.session.model.Question
import ai.kilocode.client.session.model.QuestionItem
import ai.kilocode.client.session.model.QuestionOption
import ai.kilocode.rpc.dto.QuestionReplyDto
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBRadioButton
import java.awt.Container
import javax.swing.JButton

/**
 * Covers [QuestionView.answerWithText] — typed input from the prompt editor is converted into
 * the custom answer of the pending question instead of being sent as a new prompt (which the
 * backend rejects with 409 CONFLICT while the question is pending).
 */
class QuestionViewAnswerTest : BasePlatformTestCase() {

    private val replies = mutableListOf<Pair<String, List<List<String>>>>()

    private lateinit var view: QuestionView

    override fun setUp() {
        super.setUp()
        replies.clear()
        view = QuestionView(
            project = project,
            reply = { id, dto, _ -> replies.add(id to dto.answers) },
            reject = {},
        )
    }

    fun `test typed text answers single question`() {
        edt {
            view.show(Question(id = "q1", items = listOf(item())))
            assertTrue(view.answerWithText("按打开用户资料处理"))
        }

        assertEquals(listOf("q1" to listOf(listOf("按打开用户资料处理"))), replies)
        assertFalse(view.isVisible)
    }

    fun `test typed text advances multi question flow and submits from review`() {
        edt {
            view.show(Question(id = "q2", items = listOf(item("一"), item("二"))))
            assertTrue(view.answerWithText("答案一"))
        }
        // First answer only advances to the next page.
        assertTrue(replies.isEmpty())
        assertTrue(view.isVisible)

        edt {
            assertTrue(view.answerWithText("答案二"))
        }
        // Last item answered — the flow lands on the review page without submitting.
        assertTrue(replies.isEmpty())
        assertTrue(view.isVisible)

        // Review page: typed text replaces the last item's answer and submits the flow.
        edt {
            assertTrue(view.answerWithText("答案三"))
        }

        assertEquals(listOf("q2" to listOf(listOf("答案一"), listOf("答案三"))), replies)
        assertFalse(view.isVisible)
    }

    fun `test typed text keeps previously selected multi select options`() {
        edt {
            view.show(Question(id = "q3", items = listOf(item(multiple = true))))
            findAll<JBCheckBox>(view).first().doClick()
            assertTrue(view.answerWithText("补充说明"))
        }
        // A multi-select question follows the dialog flow: the typed answer advances to the
        // review page and the submit button completes it.
        assertTrue(replies.isEmpty())
        assertTrue(view.isVisible)

        edt {
            submitButton().doClick()
        }

        assertEquals(listOf("q3" to listOf(listOf("选项A", "补充说明"))), replies)
    }

    fun `test single select drops chosen option in favor of typed text`() {
        edt {
            view.show(Question(id = "q4", items = listOf(item())))
            clickRadio(view, "选项A")
            assertTrue(view.answerWithText("改用自定义答案"))
        }

        assertEquals(listOf("q4" to listOf(listOf("改用自定义答案"))), replies)
    }

    fun `test blank text is ignored`() {
        edt {
            view.show(Question(id = "q5", items = listOf(item())))
            assertFalse(view.answerWithText("   "))
        }

        assertTrue(replies.isEmpty())
        assertTrue(view.isVisible)
    }

    fun `test no pending question returns false`() {
        edt {
            assertFalse(view.answerWithText("anything"))
        }

        assertTrue(replies.isEmpty())
    }

    // ------ helpers ------

    private fun item(suffix: String = "", multiple: Boolean = false) = QuestionItem(
        question = "问题$suffix",
        header = "标题",
        options = listOf(QuestionOption("选项A", ""), QuestionOption("选项B", "")),
        multiple = multiple,
        custom = false,
    )

    private fun clickRadio(root: Container, label: String) {
        findAll<JBRadioButton>(root).firstOrNull { it.actionCommand == label }?.doClick()
            ?: error("radio $label not found")
    }

    private fun submitButton(): JButton =
        findAll<JButton>(view).first { it.text == KiloBundle.message("session.question.submit") }

    private fun <T> edt(block: () -> T): T {
        var result: T? = null
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun <T : Any> findAll(root: Container, cls: Class<T>): List<T> {
        val result = mutableListOf<T>()
        if (cls.isInstance(root)) result.add(cls.cast(root))
        for (child in root.components) {
            if (cls.isInstance(child)) result.add(cls.cast(child))
            if (child is Container) result.addAll(findAll(child, cls))
        }
        return result
    }

    private inline fun <reified T : Any> findAll(root: Container): List<T> = findAll(root, T::class.java)
}
