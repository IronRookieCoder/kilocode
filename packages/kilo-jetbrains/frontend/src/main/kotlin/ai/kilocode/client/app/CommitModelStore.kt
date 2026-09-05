package ai.kilocode.client.app

import com.intellij.ide.util.PropertiesComponent

/**
 * Persisted provider/model choice for AI commit-message generation.
 * Stored as "providerID/modelID" in application properties; unset means the
 * CLI's default (small) model is used.
 */
object CommitModelStore {
    private const val KEY = "costrict.commitMessage.model"

    fun selection(props: PropertiesComponent = PropertiesComponent.getInstance()): Pair<String, String>? {
        val raw = props.getValue(KEY) ?: return null
        val provider = raw.substringBefore('/', "")
        val model = raw.substringAfter('/', "")
        return if (provider.isBlank() || model.isBlank()) null else provider to model
    }

    fun store(props: PropertiesComponent, provider: String?, model: String?) {
        if (provider == null || model == null || provider.isBlank() || model.isBlank()) {
            props.unsetValue(KEY)
            return
        }
        props.setValue(KEY, "$provider/$model")
    }
}
