package com.shieldrj.civic5mt.core

/**
 * A transport that answers from a script instead of a car.
 *
 * The point of keeping the protocol client free of Android: the handshake, the command
 * queue and the poll loop can all be driven from a unit test, including the two failure
 * modes that are invisible on the car - a desynced command stream and a bus that never
 * came up.
 */
class FakeObdTransport : ObdTransport {
    override val kind = TransportKind.FAKE
    override val label = "Scripted"

    private var dataHandler: ((String) -> Unit)? = null
    private var disconnectHandler: (() -> Unit)? = null

    /** Every command written to the wire, in order, with the trailing CR stripped. */
    val written = mutableListOf<String>()

    /** Return the reply for a command, or null to answer nothing at all (a timeout). */
    var autoRespond: ((String) -> String?)? = null

    var connectCalls = 0
    var disconnectCalls = 0

    override fun setDataHandler(handler: (String) -> Unit) {
        dataHandler = handler
    }

    override fun setDisconnectHandler(handler: () -> Unit) {
        disconnectHandler = handler
    }

    override suspend fun connect() {
        connectCalls++
    }

    override suspend fun write(text: String) {
        val cmd = text.trim()
        written += cmd
        autoRespond?.invoke(cmd)?.let { emit(it) }
    }

    override suspend fun disconnect() {
        disconnectCalls++
    }

    /** Push bytes as though the adapter had sent them. */
    fun emit(text: String) {
        dataHandler?.invoke(text)
    }

    /** Simulate the link dropping on its own. */
    fun dropLink() {
        disconnectHandler?.invoke()
    }
}
