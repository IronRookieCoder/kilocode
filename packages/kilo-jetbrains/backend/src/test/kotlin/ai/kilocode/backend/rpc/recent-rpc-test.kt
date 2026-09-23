@file:Suppress("UnstableApiUsage") // Public Experimental Fleet descriptors; no Internal transport APIs.

package ai.kilocode.backend.rpc

import ai.kilocode.backend.app.KiloAppState
import ai.kilocode.backend.app.KiloBackendAppService
import ai.kilocode.backend.testing.FakeCliServer
import ai.kilocode.backend.testing.MockCliServer
import ai.kilocode.backend.testing.TestLog
import ai.kilocode.rpc.KiloSessionRpcApi
import ai.kilocode.stability.DiagnosticContextElement
import ai.kilocode.stability.Fixture
import ai.kilocode.stability.rpc
import fleet.rpc.remoteApiDescriptor
import fleet.rpc.serializer
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class RecentRpcTest {
    companion object {
        private const val TIMEOUT = 10_000
    }

    @Test
    fun `generated RPC transports the operation through bytes to a context isolated backend`() = runBlocking {
        Fixture().use { backend ->
            Fixture().use { frontend ->
                backend.enableDiagnostics()
                frontend.enableDiagnostics()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val daemon = MockCliServer()
                val app = KiloBackendAppService.create(scope, FakeCliServer(daemon), TestLog(), operations = backend.operations)
                try {
                    app.connect()
                    withTimeout(TIMEOUT.toLong()) { app.appState.first { it is KiloAppState.Ready } }
                    daemon.recentSessionsStatus = 404
                    daemon.recentSessions = """{"message":"remote recent missing"}"""
                    val descriptor = remoteApiDescriptor<KiloSessionRpcApi>()
                    val implementation = KiloSessionRpcApiImpl(app)
                    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                        server.soTimeout = TIMEOUT
                        val worker = scope.async {
                            server.accept().use { socket ->
                                socket.soTimeout = TIMEOUT
                                assertNull(DiagnosticContextElement.operation(), "Caller ThreadLocal reached server")
                                val envelope = Json.parseToJsonElement(DataInputStream(socket.getInputStream()).readUTF()).jsonObject
                                val method = envelope.getValue("method").jsonPrimitive.content
                                val signature = descriptor.getSignature(method)
                                val values = envelope.getValue("args").jsonArray
                                val args = signature.parameters.mapIndexed { index, parameter ->
                                    Json.decodeFromJsonElement(parameter.parameterKind.serializer(method), values[index])
                                }.toTypedArray()
                                val response = runCatching { descriptor.call(implementation, method, args) }.fold(
                                    onSuccess = { result -> buildJsonObject {
                                        put("result", Json.encodeToJsonElement(signature.returnType.serializer(method), result))
                                    } },
                                    onFailure = { error -> buildJsonObject { put("error", error.javaClass.name) } },
                                )
                                DataOutputStream(socket.getOutputStream()).writeUTF(response.toString())
                            }
                        }
                        val proxy = descriptor.clientStub { method, args ->
                            withContext(Dispatchers.IO) {
                                val signature = descriptor.getSignature(method)
                                val envelope = buildJsonObject {
                                    put("method", method)
                                    put("args", JsonArray(signature.parameters.mapIndexed { index, parameter ->
                                        Json.encodeToJsonElement(parameter.parameterKind.serializer(method), args[index])
                                    }))
                                }
                                Socket("127.0.0.1", server.localPort).use { socket ->
                                    socket.soTimeout = TIMEOUT
                                    DataOutputStream(socket.getOutputStream()).writeUTF(envelope.toString())
                                    val response = Json.parseToJsonElement(DataInputStream(socket.getInputStream()).readUTF()).jsonObject
                                    response["error"]?.let { error("Remote error: $it") }
                                    Json.decodeFromJsonElement(signature.returnType.serializer(method), response.getValue("result"))
                                }
                            }
                        }
                        assertFailsWith<IllegalStateException> {
                            frontend.operations.rpc("session") {
                                proxy.recent("/repo", 5, DiagnosticContextElement.operation())
                            }
                        }
                        withTimeout(TIMEOUT.toLong()) { worker.await() }
                    }
                    backend.flush()
                    frontend.flush()
                    val incident = backend.facts().single { it.name == "diagnostic.reported" }
                    val end = frontend.facts().single { it.name == "rpc" && it.data["phase"] == JsonPrimitive("end") }
                    assertEquals(end.context.getValue("operation_id"), incident.context.getValue("operation_id"))
                    assertEquals("404", incident.data.getValue("http_status").jsonPrimitive.content)
                    assertTrue(backend.payload("response").contains("remote recent missing"))
                    assertTrue(backend.payload("stack").contains("KiloBackendSessionManager"))
                } finally {
                    app.dispose()
                    scope.cancel()
                    daemon.close()
                }
            }
        }
    }
}
