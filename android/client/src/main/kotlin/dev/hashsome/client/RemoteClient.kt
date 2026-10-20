package dev.hashsome.client

import org.json.JSONObject

/**
 * The [Client] that talks to a runtime over [transport]. Every callback is delivered through [scheduler].post, so
 * callers run on the main thread whatever thread the socket reads on.
 *
 * Rules the implementation keeps (see `ProtocolRules`): ping every 5 s; dead after 15 s of silence
 * once a pong was seen; reconnect with backoff 500/1000/2000/4000 ms; resubscribe on open; requests
 * in flight when the link drops fail with "Connection lost".
 *
 * As packages/core/src/client.ts does: a command made while the link is not open fails at once ("Not connected to
 * runtime"), since doing it late would surprise whoever saw it fail; a query waits up to [QUERY_WAIT_MS] for the link
 * to open, because a page asks for things the moment it appears.
 *
 * Everything below runs on the scheduler's thread. The transport's thread only decodes a frame and posts it.
 */
class RemoteClient(
    server: ServerAddress,
    private val transport: Transport.Factory = WsClient.Factory,
    private val scheduler: Scheduler,
) : Client {
    /** Where the runtime is; [reconnect] can change it. Read it on the scheduler's thread. */
    var server: ServerAddress = server
        private set

    /** Where problems worth a line go (an entity body that could not be read): `Log.e`, the one level the Shelly X1 shows. May be called from the transport's thread. */
    @Volatile var log: ((String) -> Unit)? = { android.util.Log.e("hashsome", it) }

    internal var parseEntityBody: (EntityRef, JSONObject) -> Entity = ::parseEntity

    override val link: Store<LinkStatus> = Store(LinkStatus.Connecting)

    override val integrations: Store<Map<String, ConnectionStatus>> = Store(emptyMap())

    private class Watcher(val fn: (Snapshot) -> Unit) {
        var cancelled = false
        var last: Snapshot? = null

        fun emit(snapshot: Snapshot) {
            if (cancelled || snapshot == last) return
            last = snapshot
            fn(snapshot)
        }
    }

    /** A request sent and not answered yet. */
    private class Pending(val onData: (JSONObject?) -> Unit, val onError: (String) -> Unit) {
        var cancelled = false
    }

    /** A query made before the link opened: [start] sends it, [fail] gives up. */
    private class Waiter(val start: () -> Unit, val fail: (String) -> Unit) {
        var timer: Cancel = {}
    }

    private var current: Transport? = null
    private var isOpen = false
    private var stopped = true
    private var retry = 0
    private var retryTimer: Cancel = {}
    private var beat: Cancel = {}
    private var heard = 0L
    private var answers = false
    private var nextId = 1
    private val pending = HashMap<Int, Pending>()
    private val waiting = ArrayList<Waiter>()
    private val watchers = LinkedHashMap<EntityRef, MutableList<Watcher>>()
    private val known = HashMap<EntityRef, Snapshot>()
    private val status = LinkedHashMap<String, ConnectionStatus>()

    override fun connect() {
        if (!stopped) return
        stopped = false
        retry = 0
        open()
    }

    override fun close() {
        stopped = true
        retryTimer()
        retryTimer = {}
        val t = current
        if (t != null) lost(t) else link.set(LinkStatus.Closed)
        giveUpWaiting("Connection closed")
    }

    override fun watch(ref: EntityRef, fn: (Snapshot) -> Unit): Cancel {
        val watcher = Watcher(fn)
        val list = watchers.getOrPut(ref) {
            send(ClientMessage.Subscribe(ref))
            ArrayList()
        }
        list.add(watcher)
        // Delivered on the scheduler like everything else, with whatever is known by then.
        scheduler.post { watcher.emit(known[ref] ?: Snapshot.Loading) }
        return {
            if (!watcher.cancelled) {
                watcher.cancelled = true
                list.remove(watcher)
                if (list.isEmpty() && watchers[ref] === list) {
                    watchers.remove(ref)
                    send(ClientMessage.Unsubscribe(ref))
                }
            }
        }
    }

    override fun command(ref: EntityRef, name: String, args: JSONObject?, done: ((Outcome) -> Unit)?): Cancel {
        // A number that is not finite cannot be written as JSON; the typed builders leave a JSON null in its place.
        val invalid = args?.keys()?.asSequence()?.firstOrNull { args.isNull(it) }
        if (invalid != null) {
            val request = Pending({}, {})
            scheduler.post { if (!request.cancelled) done?.invoke(Outcome.Failed("Invalid argument \"$invalid\" for $name")) }
            return { request.cancelled = true }
        }
        if (!isOpen) {
            val request = Pending({}, {})
            scheduler.post { if (!request.cancelled) done?.invoke(Outcome.Failed(NOT_CONNECTED)) }
            return { request.cancelled = true }
        }
        return request(
            build = { id -> ClientMessage.Command(id, ref, name, args) },
            onData = { done?.invoke(Outcome.Ok) },
            onError = { done?.invoke(Outcome.Failed(it)) },
        )
    }

    override fun <T> query(ref: EntityRef, query: Query<T>, done: (Result<T>) -> Unit): Cancel =
        whenOpen(
            build = { id -> ClientMessage.Query(id, ref, query.name, query.args()) },
            answer = { data -> done(runCatching { query.parse(data) }) },
            fail = { error -> done(Result.failure(IllegalStateException(error))) },
        )

    override fun raw(integration: String, request: JSONObject, done: ((Result<JSONObject>) -> Unit)?): Cancel =
        whenOpen(
            build = { id -> ClientMessage.Raw(id, integration, request) },
            answer = { data -> done?.invoke(Result.success(data ?: JSONObject())) },
            fail = { error -> done?.invoke(Result.failure(IllegalStateException(error))) },
        )

    /** Sends a request now when the link is open, else when it opens, giving up with "Not connected to runtime" after [QUERY_WAIT_MS] (at once when the client is closed). */
    private fun whenOpen(build: (Int) -> ClientMessage, answer: (JSONObject?) -> Unit, fail: (String) -> Unit): Cancel {
        val send = { request(build, answer, fail) }
        if (isOpen) return send()
        if (stopped) {
            val request = Pending({}, {})
            scheduler.post { if (!request.cancelled) fail(NOT_CONNECTED) }
            return { request.cancelled = true }
        }

        // Not open yet (opening, or back after a drop): it goes out when the link opens, or fails after a while.
        var cancelRequest: Cancel = {}
        var cancelled = false
        val waiter = Waiter(
            start = { if (!cancelled) cancelRequest = send() },
            fail = { error -> if (!cancelled) scheduler.post { if (!cancelled) fail(error) } },
        )
        waiter.timer = scheduler.after(QUERY_WAIT_MS) {
            waiting.remove(waiter)
            waiter.fail(NOT_CONNECTED)
        }
        waiting.add(waiter)
        return {
            cancelled = true
            waiting.remove(waiter)
            waiter.timer()
            cancelRequest()
        }
    }

    /**
     * Closes the current socket and opens a new one at once, to [server] when it is given, with the backoff reset. Watchers, subscriptions
     * and stores stay: the subscriptions are sent again when the new socket opens. Requests in flight fail with "Connection lost"; queries
     * not yet sent wait for the new socket. The link goes `Connecting` (not `Closed`). A client that was closed starts again.
     * When the address changes, the known snapshots and integration statuses of the old runtime are forgotten (watchers see `Loading`).
     * Like every method here, call it on the scheduler's thread.
     */
    override fun reconnect(server: ServerAddress?) {
        if (server != null && server != this.server) {
            // Nothing the old runtime said holds for the new one: watchers see Loading until it answers.
            this.server = server
            known.clear()
            status.clear()
            integrations.set(emptyMap())
            for (list in watchers.values) for (watcher in list.toList()) watcher.emit(Snapshot.Loading)
        } else if (server != null) {
            this.server = server
        }
        retryTimer()
        retryTimer = {}
        retry = 0
        stopped = false
        val t = current
        if (t != null) lost(t, restart = true) else open()
    }

    // --- The link ----------------------------------------------------------------------------------------------

    private fun open() {
        link.set(LinkStatus.Connecting)
        val t = transport.create(server, ProtocolRules.WS_PATH)
        current = t
        // The transport reports from its own thread; each event is decoded there and handled on the scheduler's.
        t.connect(object : Transport.Listener {
            override fun onOpen() {
                scheduler.post { if (current === t) opened(t) }
            }

            override fun onMessage(text: String) {
                val message = ServerMessage.decode(text, parseEntityBody, log) ?: return
                scheduler.post { if (current === t) handle(message) }
            }

            override fun onClosed(reason: String?) {
                scheduler.post { lost(t) }
            }
        })
    }

    private fun opened(t: Transport) {
        retry = 0
        isOpen = true
        link.set(LinkStatus.Open)
        for (waiter in waiting.toList()) {
            waiter.timer()
            waiter.start()
        }
        waiting.clear()
        for (ref in watchers.keys.toList()) send(ClientMessage.Subscribe(ref))
        startBeat(t)
    }

    /**
     * Asks the runtime now and then whether it is there, and gives the connection up when it has gone quiet. The silence
     * only counts once the runtime has answered a ping: an older one drops it, and a quiet link to that is not a dead one.
     */
    private fun startBeat(t: Transport) {
        beat()
        heard = scheduler.now()
        answers = false
        beat = scheduler.every(ProtocolRules.PING_MS) {
            if (current === t) {
                if (answers && scheduler.now() - heard > ProtocolRules.DEAD_AFTER_MS) {
                    lost(t)
                } else {
                    send(ClientMessage.Ping)
                }
            }
        }
    }

    /** The connection is gone, closed or found dead: its requests fail and it is opened again after a pause. */
    private fun lost(t: Transport, restart: Boolean = false) {
        if (current !== t) return
        current = null
        isOpen = false
        beat()
        beat = {}
        try {
            t.close()
        } catch (_: Exception) {
            // Already closed, or never open: nothing to give up.
        }
        val failed = pending.values.toList()
        pending.clear()
        scheduler.post {
            for (request in failed) if (!request.cancelled) request.onError("Connection lost")
        }
        if (restart) return open()
        link.set(LinkStatus.Closed)
        if (stopped) return
        val wait = ProtocolRules.BACKOFF_MS[minOf(retry++, ProtocolRules.BACKOFF_MS.size - 1)]
        retryTimer = scheduler.after(wait) { if (!stopped && current == null) open() }
    }

    private fun giveUpWaiting(error: String) {
        val all = waiting.toList()
        waiting.clear()
        for (waiter in all) {
            waiter.timer()
            waiter.fail(error)
        }
    }

    private fun send(message: ClientMessage) {
        if (isOpen) current?.send(message.encode())
    }

    private fun request(build: (Int) -> ClientMessage, onData: (JSONObject?) -> Unit, onError: (String) -> Unit): Cancel {
        val id = nextId++
        val request = Pending(onData, onError)
        pending[id] = request
        send(build(id))
        return {
            request.cancelled = true
            pending.remove(id)
        }
    }

    // --- Messages ----------------------------------------------------------------------------------------------

    private fun handle(message: ServerMessage) {
        // Anything the runtime says shows it is there.
        heard = scheduler.now()
        when (message) {
            ServerMessage.Pong -> answers = true
            is ServerMessage.EntityUpdate -> {
                val snapshot: Snapshot = message.entity?.let { Snapshot.Present(it) } ?: Snapshot.Missing
                known[message.ref] = snapshot
                for (watcher in watchers[message.ref]?.toList().orEmpty()) watcher.emit(snapshot)
            }
            is ServerMessage.Status -> {
                status[message.integration] = message.status
                integrations.set(LinkedHashMap(status))
            }
            is ServerMessage.Result -> {
                val request = pending.remove(message.id) ?: return
                if (request.cancelled) return
                if (message.ok) request.onData(message.data) else request.onError(message.error ?: "")
            }
        }
    }

    companion object {
        /** How long a query waits for the link to open before it fails. */
        const val QUERY_WAIT_MS = 10_000L

        private const val NOT_CONNECTED = "Not connected to runtime"
    }
}
