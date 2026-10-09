package dev.lyricsfloat.lyrics.translation

import dev.lyricsfloat.platform.AppState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import java.io.File
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A model T3 Code can run, with its reasoning control when it has one. */
data class T3Model(
    val slug: String,
    val name: String,
    val effortOptionId: String?,
    /** (id, label) pairs. */
    val efforts: List<Pair<String, String>>,
    val defaultEffort: String?,
)

/** One enabled provider instance from T3 Code's live catalog (server.getConfig). */
data class T3Provider(
    val instanceId: String,
    val name: String,
    val ready: Boolean,
    val models: List<T3Model>,
)

/**
 * Talks to the local T3 Code server the way its own clients do: a scoped
 * bearer session over HTTP, plus the Effect RPC WebSocket for the calls that
 * have no HTTP route (catalog, thread launch, thread delete).
 *
 * T3 Code has no ephemeral-thread flag. A translation launches an ordinary
 * thread in the environment's "No project" scratch folder, polls its
 * snapshot until the run ends, reads the reply, and always deletes the
 * thread afterwards (stopping it first when it did not finish), so nothing
 * is left in the sidebar.
 */
object T3CodeClient {
    private const val TOKEN_KEY = "translate.t3.token"
    private const val PROTOCOL = "2"
    private const val RUN_TIMEOUT_MS = 300_000L
    private val terminalRunStates = setOf("completed", "failed", "cancelled", "interrupted", "rolled_back")
    private val reasoningOptionIds = setOf("effort", "reasoningEffort", "variant")

    private val http by lazy {
        HttpClient(CIO) {
            install(WebSockets)
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 5_000
            }
            expectSuccess = false
        }
    }

    private class AuthException : Exception("T3 Code rejected the saved session")

    private val connectLock = Mutex()

    @Volatile
    private var scratchProject: Pair<String, String>? = null

    val isConnected: Boolean get() = !AppState.readRaw(TOKEN_KEY).isNullOrBlank()

    fun disconnect() {
        AppState.writeRaw(TOKEN_KEY, "")
        scratchProject = null
    }

    /**
     * The running server's origin from `$T3CODE_HOME/userdata/server-runtime.json`
     * (the desktop app binds 0.0.0.0 but advertises a loopback origin there).
     */
    fun origin(): String {
        val runtime = File(t3Home(), "userdata/server-runtime.json")
        val origin = runCatching {
            translationJson.parseToJsonElement(runtime.readText()).jsonObject["origin"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return origin?.trimEnd('/') ?: "http://127.0.0.1:3773"
    }

    private fun t3Home(): File =
        System.getenv("T3CODE_HOME")?.takeIf { it.isNotBlank() }?.let(::File)
            ?: File(System.getProperty("user.home"), ".t3")

    /** The `t3` launcher the desktop app installs; null when T3 Code is not set up. */
    private fun cliPath(): File? {
        val home = System.getProperty("user.home")
        val candidates = listOfNotNull(
            System.getenv("T3CODE_CLI_PATH")?.takeIf { it.isNotBlank() },
            File(t3Home(), "bin/t3").path,
            "$home/.local/bin/t3",
        ) + System.getenv("PATH").orEmpty().split(':').filter { it.isNotBlank() }.map { "$it/t3" }
        return candidates.map(::File).firstOrNull { it.canExecute() }
    }

    /**
     * Mints a session limited to orchestration read/operate through
     * `t3 auth session issue` and stores it. Runs again on its own when the
     * server later rejects the session (expiry is 30 days by default).
     */
    private suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val cli = cliPath() ?: throw TranslationException("Could not find the t3 command. Open T3 Code once to install it.")
            val outputFile = File.createTempFile("lyricsfloat-t3", ".out")
                try {
                val process = ProcessBuilder(
                    cli.path, "auth", "session", "issue",
                    "--scope", "orchestration:read",
                    "--scope", "orchestration:operate",
                    "--label", "Lyrics Float",
                    "--token-only",
                ).apply {
                    // AppImage launchers (this app's and T3 Code's) inject their
                    // own mount paths; inheriting them breaks the t3 launcher,
                    // which mounts T3 Code's AppImage itself.
                    listOf("LD_LIBRARY_PATH", "APPDIR", "APPIMAGE", "ARGV0", "OWD").forEach(environment()::remove)
                    redirectError(ProcessBuilder.Redirect.DISCARD)
                    // A file instead of a pipe, so a hung t3 cannot block the
                    // read past the timeout below.
                    redirectOutput(outputFile)
                }.start()
                if (!process.waitFor(60, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    throw TranslationException("t3 auth session issue timed out")
                }
                val output = outputFile.readText()
                val token = output.lines().map(String::trim).lastOrNull(String::isNotBlank)
                if (process.exitValue() != 0 || token.isNullOrBlank() || token.contains(' ')) {
                    throw TranslationException("t3 could not issue a session (exit ${process.exitValue()})")
                }
                AppState.writeRaw(TOKEN_KEY, token)
                scratchProject = null
            } finally {
                outputFile.delete()
            }
        }
    }

    /** Enabled providers with at least one model, in T3 Code's order. */
    suspend fun catalog(): List<T3Provider> = withSession { token ->
        withRpc(token) { rpc -> parseCatalog(rpc.call("server.getConfig", JsonObject(emptyMap()))) }
    }

    suspend fun translate(lines: List<String>, config: TranslationConfig): List<String> =
        complete(TranslationPrompt.forTranslation(lines, config), config)

    /** Runs any line prompt (translation or romaji) in a one-shot thread. */
    suspend fun complete(prompt: LinePrompt, config: TranslationConfig): List<String> {
        val selection = config.t3 ?: throw TranslationException("Pick a T3 Code model in Settings")
        val text = buildString {
            append(prompt.system)
            append("\n\n")
            append(prompt.user)
            append("\n\nReply with the JSON object only. Do not use tools, run commands, or read files.")
        }
        val reply = withSession { token -> runOneShotThread(token, text, selection) }
        return TranslationPrompt.parseLines(reply, prompt.lineCount).getOrElse { throw TranslationException(it.message ?: "Bad reply") }
    }

    /** Runs [block] with the saved token, minting a fresh one once if the server rejects it. */
    private suspend fun <T> withSession(block: suspend (String) -> T): T {
        val saved = AppState.readRaw(TOKEN_KEY)?.takeIf(String::isNotBlank)
        if (saved != null) {
            try {
                return block(saved)
            } catch (_: AuthException) {
                // Fall through and reconnect.
            }
        }
        val fresh = freshToken(rejected = saved)
        return try {
            block(fresh)
        } catch (_: AuthException) {
            throw TranslationException("T3 Code rejected a fresh session")
        }
    }

    /**
     * One sign-in at a time. The settings catalog and an automatic translation
     * can both find no usable token; without the lock each would mint a
     * session and one would be orphaned. A caller that waited reuses the
     * token the other one just saved.
     */
    private suspend fun freshToken(rejected: String?): String = connectLock.withLock {
        AppState.readRaw(TOKEN_KEY)?.takeIf { it.isNotBlank() && it != rejected }?.let { return@withLock it }
        connect().getOrElse { throw TranslationException(it.message ?: "Could not connect to T3 Code") }
        AppState.readRaw(TOKEN_KEY)?.takeIf(String::isNotBlank)
            ?: throw TranslationException("Could not connect to T3 Code")
    }

    private suspend fun runOneShotThread(token: String, prompt: String, selection: T3Selection): String =
        withRpc(token) { rpc ->
            val projectId = scratchProjectId(token, rpc)
            val threadId = UUID.randomUUID().toString()
            // The cleanup covers the launch too: a launch cancelled or timed
            // out after the request went out may still create the thread.
            var launched = false
            var finished = false
            try {
                try {
                    launchThread(rpc, threadId, projectId, prompt, selection)
                } catch (e: TranslationException) {
                    // A deleted or recreated scratch project leaves a stale id.
                    scratchProject = null
                    throw e
                }
                launched = true
                withTimeoutOrNull(RUN_TIMEOUT_MS) { awaitReply(token, threadId) { finished = true } }
                    ?: throw TranslationException("T3 Code did not answer within ${RUN_TIMEOUT_MS / 60_000} minutes")
            } finally {
                // Separate deadlines: a slow stop must not use up the time
                // the delete needs.
                withContext(NonCancellable) {
                    if (!finished) {
                        withTimeoutOrNull(10_000) {
                            runCatching {
                                rpc.call("orchestration.dispatchCommand", threadCommand("thread.stop", threadId))
                            }
                        }
                    }
                    val deleted = withTimeoutOrNull(15_000) {
                        runCatching {
                            rpc.call("orchestration.dispatchCommand", threadCommand("thread.delete", threadId))
                        }
                    }
                    if (launched && deleted?.isSuccess != true) {
                        val reason = deleted?.exceptionOrNull()?.message ?: "timed out"
                        println("Lyrics Float: could not delete T3 thread $threadId: $reason")
                    }
                }
            }
        }

    private suspend fun launchThread(
        rpc: RpcSession,
        threadId: String,
        projectId: String,
        prompt: String,
        selection: T3Selection,
    ) {
        rpc.call(
            "orchestration.launchThread",
            buildJsonObject {
                put("commandId", UUID.randomUUID().toString())
                put("threadId", threadId)
                put("projectId", projectId)
                put("title", "Lyrics Float translation")
                put("generateTitle", false)
                putJsonObject("modelSelection") {
                    put("instanceId", selection.instanceId)
                    put("model", selection.model)
                    if (selection.effortOptionId != null && selection.effort != null) {
                        putJsonArray("options") {
                            addJsonObject {
                                put("id", selection.effortOptionId)
                                put("value", selection.effort)
                            }
                        }
                    }
                }
                // Translation needs no tools; anything that asks for
                // approval stops instead of acting.
                put("runtimeMode", "approval-required")
                put("interactionMode", "default")
                putJsonObject("workspaceStrategy") { put("type", "root") }
                putJsonObject("initialMessage") {
                    put("text", prompt)
                    putJsonArray("attachments") {}
                }
            },
        )
    }

    /**
     * Polls the thread snapshot until the agent's turn is over. A run parks at
     * "waiting" once the turn has ended but background work is draining, so
     * a reply there counts; only a terminal run sets [onFinished] (the
     * cleanup stops anything that is not). A pending runtime request is an
     * approval or question this app cannot answer.
     */
    private suspend fun awaitReply(token: String, threadId: String, onFinished: () -> Unit): String {
        while (true) {
            delay(750)
            val projection = threadProjection(token, threadId) ?: continue
            if (projection["runtimeRequests"]?.jsonArray.orEmpty()
                    .any { it.jsonObject["status"]?.jsonPrimitive?.contentOrNull == "pending" }
            ) {
                throw TranslationException("The T3 Code agent asked for approval or input")
            }
            val runs = projection["runs"]?.jsonArray.orEmpty().map {
                it.jsonObject["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            val last = runs.lastOrNull() ?: continue
            val text = lastAssistantText(projection)
            if (runs.all { it in terminalRunStates }) {
                onFinished()
                if (last != "completed") {
                    throw TranslationException("T3 Code run $last${text?.let { ": ${it.take(160)}" }.orEmpty()}")
                }
                return text ?: throw TranslationException("T3 Code returned no reply")
            }
            if (last == "waiting" && text != null) return text
        }
    }

    private fun threadCommand(type: String, threadId: String) = buildJsonObject {
        put("type", type)
        put("commandId", UUID.randomUUID().toString())
        put("threadId", threadId)
    }

    private fun lastAssistantText(projection: JsonObject): String? =
        projection["messages"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .lastOrNull { it["role"]?.jsonPrimitive?.contentOrNull == "assistant" && !it.text().isNullOrBlank() }
            ?.text()

    private fun JsonObject.text(): String? = (this["text"] as? JsonPrimitive)?.contentOrNull

    private suspend fun threadProjection(token: String, threadId: String): JsonObject? {
        val response = http.get("${origin()}/api/orchestration/threads/$threadId") { authorize(token) }
        if (response.status.value == 401) throw AuthException()
        if (response.status.value == 404) return null
        val body = response.checked()
        return translationJson.parseToJsonElement(body).jsonObject["projection"] as? JsonObject
    }

    /** The environment's "No project" folder (ServerConfig.scratchWorkspaceRoot), cached per origin. */
    private suspend fun scratchProjectId(token: String, rpc: RpcSession): String {
        val origin = origin()
        scratchProject?.takeIf { it.first == origin }?.let { return it.second }
        val config = rpc.call("server.getConfig", JsonObject(emptyMap())).jsonObject
        val root = config["scratchWorkspaceRoot"]?.jsonPrimitive?.contentOrNull
            ?: throw TranslationException("This T3 Code server has no scratch folder")
        val response = http.get("$origin/api/projects") { authorize(token) }
        if (response.status.value == 401) throw AuthException()
        val projects = translationJson.parseToJsonElement(response.checked()).jsonObject["projects"]?.jsonArray.orEmpty()
        val id = projects.map { it.jsonObject }
            .firstOrNull {
                it["workspaceRoot"]?.jsonPrimitive?.contentOrNull?.trimEnd('/') == root.trimEnd('/') &&
                    (it["deletedAt"] as? JsonPrimitive)?.contentOrNull == null
            }
            ?.get("id")?.jsonPrimitive?.contentOrNull
            ?: throw TranslationException("Open \"No project\" in T3 Code once, then try again")
        scratchProject = origin to id
        return id
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authorize(token: String) {
        header("Authorization", "Bearer $token")
        header("x-t3-orchestration-protocol", PROTOCOL)
    }

    private suspend fun HttpResponse.checked(): String {
        val body = bodyAsText()
        if (status.value !in 200..299) {
            val message = runCatching {
                translationJson.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            throw TranslationException("T3 Code: ${message ?: "HTTP ${status.value}"}")
        }
        return body
    }

    /** Opens the RPC socket with a one-time ticket, runs [block], and closes it. */
    private suspend fun <T> withRpc(token: String, block: suspend (RpcSession) -> T): T {
        val origin = origin()
        val ticketResponse = try {
            http.post("$origin/api/auth/websocket-ticket") { authorize(token) }
        } catch (e: java.io.IOException) {
            throw TranslationException("T3 Code is not running at $origin")
        }
        if (ticketResponse.status.value == 401) throw AuthException()
        val ticket = translationJson.parseToJsonElement(ticketResponse.checked()).jsonObject["ticket"]
            ?.jsonPrimitive?.contentOrNull ?: throw TranslationException("T3 Code returned no WebSocket ticket")
        val wsUrl = origin.replaceFirst("http", "ws") +
            "/ws?orchestrationProtocol=$PROTOCOL&wsTicket=${URLEncoder.encode(ticket, Charsets.UTF_8)}"
        val session = http.webSocketSession(wsUrl)
        val rpc = RpcSession(session)
        try {
            return block(rpc)
        } finally {
            rpc.stop()
            withContext(NonCancellable) { runCatching { session.close() } }
        }
    }

    internal fun parseCatalog(config: JsonElement): List<T3Provider> =
        config.jsonObject["providers"]?.jsonArray.orEmpty().mapNotNull { element ->
            val provider = element.jsonObject
            if (provider["enabled"]?.jsonPrimitive?.booleanOrNull == false) return@mapNotNull null
            val models = provider["models"]?.jsonArray.orEmpty().mapNotNull { modelElement ->
                val model = modelElement.jsonObject
                val slug = model["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val descriptors = model["capabilities"]?.jsonObject?.get("optionDescriptors")?.jsonArray.orEmpty()
                    .map { it.jsonObject }
                    .filter { it["type"]?.jsonPrimitive?.contentOrNull == "select" }
                val reasoning = descriptors.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull in reasoningOptionIds }
                    ?: descriptors.firstOrNull { it["label"]?.jsonPrimitive?.contentOrNull == "Reasoning" }
                // Prompt-injected values (ultrathink) work by the client
                // rewriting the prompt text, which this app does not do.
                val injected = reasoning?.get("promptInjectedValues")?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                val options = reasoning?.get("options")?.jsonArray.orEmpty().map { it.jsonObject }
                    .filter { it["id"]?.jsonPrimitive?.contentOrNull !in injected }
                val efforts = options.mapNotNull { option ->
                    val id = option["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    id to (option["label"]?.jsonPrimitive?.contentOrNull ?: id)
                }
                val default = reasoning?.get("currentValue")?.jsonPrimitive?.contentOrNull?.takeIf { value -> efforts.any { it.first == value } }
                    ?: options.firstOrNull { it["isDefault"]?.jsonPrimitive?.booleanOrNull == true }?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: efforts.firstOrNull()?.first
                T3Model(
                    slug = slug,
                    name = model["name"]?.jsonPrimitive?.contentOrNull ?: slug,
                    effortOptionId = reasoning?.get("id")?.jsonPrimitive?.contentOrNull?.takeIf { efforts.isNotEmpty() },
                    efforts = efforts,
                    defaultEffort = default,
                )
            }
            if (models.isEmpty()) return@mapNotNull null
            val instanceId = provider["instanceId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            T3Provider(
                instanceId = instanceId,
                name = provider["displayName"]?.jsonPrimitive?.contentOrNull ?: instanceId,
                ready = provider["status"]?.jsonPrimitive?.contentOrNull == "ready",
                models = models,
            )
        }

    /**
     * Minimal Effect RPC client over the JSON WebSocket protocol: requests
     * carry a string id, the server answers each with an `Exit` (or `Chunk`s
     * for streams, which this client never opens) and pings to keep the
     * socket alive.
     */
    private class RpcSession(private val socket: DefaultClientWebSocketSession) {
        private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
        private val nextId = AtomicLong(1)
        private val reader = socket.launch {
            try {
                for (frame in socket.incoming) {
                    if (frame !is Frame.Text) continue
                    val messages = when (val parsed = translationJson.parseToJsonElement(frame.readText())) {
                        is JsonArray -> parsed.map { it.jsonObject }
                        else -> listOf(parsed.jsonObject)
                    }
                    messages.forEach(::handle)
                }
            } finally {
                val closed = TranslationException("T3 Code closed the connection")
                pending.values.forEach { it.completeExceptionally(closed) }
            }
        }

        private fun handle(message: JsonObject) {
            when (message["_tag"]?.jsonPrimitive?.contentOrNull) {
                "Ping" -> socket.launch { socket.send(Frame.Text("""{"_tag":"Pong"}""")) }
                "Exit" -> message["requestId"]?.jsonPrimitive?.contentOrNull
                    ?.let(pending::remove)
                    ?.complete(message["exit"]?.jsonObject ?: JsonObject(emptyMap()))
                "Defect" -> {
                    val error = TranslationException("T3 Code RPC defect: ${message["defect"]}")
                    pending.values.forEach { it.completeExceptionally(error) }
                }
            }
        }

        suspend fun call(tag: String, payload: JsonElement): JsonElement {
            val id = nextId.getAndIncrement().toString()
            val result = CompletableDeferred<JsonObject>()
            pending[id] = result
            val request = buildJsonObject {
                put("_tag", "Request")
                put("id", id)
                put("tag", tag)
                put("payload", payload)
                putJsonArray("headers") {}
            }
            socket.send(Frame.Text(request.toString()))
            // withTimeout would throw a CancellationException, which callers
            // treat as "cancelled" and swallow; time out as a failure instead.
            val exit = withTimeoutOrNull(30_000) { result.await() }
                ?: run {
                    pending.remove(id)
                    throw TranslationException("T3 Code did not answer $tag in time")
                }
            if (exit["_tag"]?.jsonPrimitive?.contentOrNull == "Success") return exit["value"] ?: JsonObject(emptyMap())
            throw TranslationException("T3 Code $tag failed: ${failureMessage(exit)}")
        }

        private fun failureMessage(exit: JsonObject): String {
            val cause = exit["cause"]?.jsonArray?.firstOrNull()?.jsonObject ?: return exit.toString().take(200)
            val error = (cause["error"] ?: cause["defect"]) as? JsonObject
            return error?.get("message")?.jsonPrimitive?.contentOrNull
                ?: error?.get("_tag")?.jsonPrimitive?.contentOrNull
                ?: cause.toString().take(200)
        }

        fun stop() {
            reader.cancel()
        }
    }
}
