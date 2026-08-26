package ai.kilocode.cscloud

import java.util.concurrent.CopyOnWriteArrayList

/** Listener for connection status changes, consumed by the frontend for state display. */
fun interface CsCloudStatusListener {
    fun onStatusChanged(status: CsCloudConnectionStatus)
}

/** Thread-safe registry that fans out status changes to all registered listeners. */
class CsCloudStatusNotifier {
    private val listeners = CopyOnWriteArrayList<CsCloudStatusListener>()

    fun add(listener: CsCloudStatusListener) {
        listeners.addIfAbsent(listener)
    }

    fun remove(listener: CsCloudStatusListener) {
        listeners.remove(listener)
    }

    fun notify(status: CsCloudConnectionStatus) {
        listeners.forEach { it.onStatusChanged(status) }
    }
}
