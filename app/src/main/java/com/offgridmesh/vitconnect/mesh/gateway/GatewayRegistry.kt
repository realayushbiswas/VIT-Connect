package com.offgridmesh.vitconnect.mesh.gateway

/** A device in the mesh that has announced it can reach the internet for us. */
data class GatewayInfo(
    val deviceId: String,
    val name: String,
    /** Radio hops between us and the gateway (1 = directly connected). */
    val hops: Int,
    val kinds: Set<QueryKind>,
    val lastSeen: Long,
)

/**
 * Tracks which gateways are currently reachable and picks the nearest one for a query.
 *
 * Gateways re-announce on a heartbeat; one we haven't heard from within [ttlMs] is assumed gone
 * (its phone left the mesh or lost internet) and is no longer offered.
 */
class GatewayRegistry(private val ttlMs: Long = DEFAULT_TTL_MS) {

    private val gateways = HashMap<String, GatewayInfo>()

    fun update(info: GatewayInfo) {
        if (info.kinds.isEmpty()) gateways.remove(info.deviceId) else gateways[info.deviceId] = info
    }

    fun remove(deviceId: String) {
        gateways.remove(deviceId)
    }

    fun clear() = gateways.clear()

    /** Drops expired entries and returns the live ones, nearest first. */
    fun active(now: Long): List<GatewayInfo> {
        gateways.values.removeAll { now - it.lastSeen > ttlMs }
        return gateways.values.sortedWith(compareBy<GatewayInfo> { it.hops }.thenByDescending { it.lastSeen })
    }

    /** Live gateways that can serve [kind], nearest first, skipping any in [exclude]. */
    fun candidates(kind: QueryKind, now: Long, exclude: Set<String> = emptySet()): List<GatewayInfo> =
        active(now).filter { kind in it.kinds && it.deviceId !in exclude }

    companion object {
        const val DEFAULT_TTL_MS = 150_000L
    }
}

/** Sliding-window limiter: at most [maxEvents] per [windowMs] for each key. */
class RateLimiter(private val maxEvents: Int, private val windowMs: Long) {

    private val events = HashMap<String, ArrayDeque<Long>>()

    /** Records an event for [key] and returns true if it is within the limit. */
    fun tryAcquire(key: String, now: Long): Boolean {
        val window = events.getOrPut(key) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() >= windowMs) window.removeFirst()
        if (window.size >= maxEvents) return false
        window.addLast(now)
        return true
    }
}
