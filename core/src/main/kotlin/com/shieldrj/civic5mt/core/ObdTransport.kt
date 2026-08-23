package com.shieldrj.civic5mt.core

/**
 * How the bytes get to the adapter.
 *
 * Split out so that everything above it - the AT handshake, PID polling, every parser - is
 * plain Kotlin with no Android in it and can be tested against a fake. That layer is where
 * the subtle failures live, and in the TypeScript build none of it had a single test,
 * because reaching it meant reaching a Bluetooth stack.
 */
interface ObdTransport {
    val kind: TransportKind
    val label: String

    /** Raw bytes as they arrive, in whatever chunks the link delivers them. */
    fun setDataHandler(handler: (String) -> Unit)

    /** Called when the link drops on its own, as opposed to being closed deliberately. */
    fun setDisconnectHandler(handler: () -> Unit)

    suspend fun connect()
    suspend fun write(text: String)
    suspend fun disconnect()
}

enum class TransportKind {
    /**
     * Bluetooth Classic RFCOMM / SPP. The only way to reach an OBDLink MX+: it advertises
     * Classic and not LE, and Web Bluetooth is LE-only by specification, so no browser can
     * see this adapter at all.
     */
    SPP,

    /** A scripted transport, for tests and the simulator. */
    FAKE,
}

class ObdTransportError(message: String, cause: Throwable? = null) : Exception(message, cause)
