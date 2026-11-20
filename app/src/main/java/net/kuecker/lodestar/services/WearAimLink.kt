package net.kuecker.lodestar.services

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import net.kuecker.lodestar.kit.pointing.Guidance
import net.kuecker.lodestar.kit.wrist.AimWristMessage
import java.time.Instant

/**
 * Mirrors the aiming guidance to the Wear OS app's Aim page: a tiny message (numbers, a state word and the target's name,
 * see [AimWristMessage]) about twice a second while Aim leads to something and a nearby watch has Lodestar installed.
 * Nothing is sent otherwise, and the watch drops what is stale: it shows "Waiting for phone" after three seconds without a
 * message.
 *
 * The watch app declares the capability [AimWristMessage.CAPABILITY]; the phone looks for the watches that have it every
 * few seconds, so a watch that has the app installed gets the messages and one without it costs nothing. Without Google
 * Play services (or a Wear OS watch) the calls fail quietly and are tried again only after [FAILURE_BACKOFF_MILLIS].
 * Call the public functions from the main thread.
 */
class WearAimLink(context: Context) {
    companion object {
        /** Milliseconds between messages (2 Hz). */
        const val INTERVAL_MILLIS = 500L

        /** Milliseconds between looks for the watches that have the app. */
        const val NODE_REFRESH_MILLIS = 5_000L

        /** Milliseconds before the watches are looked for again after a failure. */
        const val FAILURE_BACKOFF_MILLIS = 30_000L
    }

    private val appContext = context.applicationContext
    private val messages: MessageClient by lazy { Wearable.getMessageClient(appContext) }
    private val capabilities: CapabilityClient by lazy { Wearable.getCapabilityClient(appContext) }

    private var nodeIDs: Set<String> = emptySet()
    private var nextNodeRefresh = 0L
    private var isRefreshing = false
    private var lastSent = Long.MIN_VALUE / 2

    /**
     * Sends [guidance] unless a message went out less than [INTERVAL_MILLIS] ago or no watch app is listening. Call as
     * often as you like, once per frame for example.
     */
    fun send(guidance: Guidance, targetName: String, isNight: Boolean, now: Instant = Instant.now()) {
        val clock = System.nanoTime() / 1_000_000
        refreshNodesIfDue(clock)
        if (nodeIDs.isEmpty() || clock - lastSent < INTERVAL_MILLIS) return
        lastSent = clock
        val payload = AimWristMessage.of(guidance, targetName, isNight, now).encode()
        for (id in nodeIDs) {
            messages.sendMessage(id, AimWristMessage.PATH, payload)
        }
    }

    /** Forgets the watches found, so the next [send] looks again; call when the Aim screen goes away. */
    fun reset() {
        nodeIDs = emptySet()
        nextNodeRefresh = 0L
    }

    private fun refreshNodesIfDue(clock: Long) {
        if (isRefreshing || clock < nextNodeRefresh) return
        isRefreshing = true
        nextNodeRefresh = clock + NODE_REFRESH_MILLIS
        capabilities.getCapability(AimWristMessage.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info ->
                nodeIDs = info.nodes.filter { it.isNearby }.mapTo(mutableSetOf()) { it.id }
                isRefreshing = false
            }
            .addOnFailureListener {
                nodeIDs = emptySet()
                nextNodeRefresh = System.nanoTime() / 1_000_000 + FAILURE_BACKOFF_MILLIS
                isRefreshing = false
            }
    }
}
