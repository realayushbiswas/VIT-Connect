package com.offgridmesh.vitconnect.mesh.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRegistryTest {

    private fun gw(id: String, hops: Int, lastSeen: Long, kinds: Set<QueryKind> = QueryKind.entries.toSet()) =
        GatewayInfo(id, "name-$id", hops, kinds, lastSeen)

    @Test
    fun nearestGatewayComesFirst() {
        val r = GatewayRegistry()
        r.update(gw("far", hops = 4, lastSeen = 1000))
        r.update(gw("near", hops = 1, lastSeen = 1000))
        r.update(gw("mid", hops = 2, lastSeen = 1000))
        assertEquals(listOf("near", "mid", "far"), r.candidates(QueryKind.SEARCH, now = 1000).map { it.deviceId })
    }

    @Test
    fun sameDistanceFavoursMostRecentlySeen() {
        val r = GatewayRegistry()
        r.update(gw("old", hops = 2, lastSeen = 1000))
        r.update(gw("fresh", hops = 2, lastSeen = 5000))
        assertEquals("fresh", r.candidates(QueryKind.SEARCH, now = 5000).first().deviceId)
    }

    @Test
    fun silentGatewaysExpire() {
        val r = GatewayRegistry(ttlMs = 100_000)
        r.update(gw("a", hops = 1, lastSeen = 0))
        assertEquals(1, r.active(now = 100_000).size)
        assertTrue(r.active(now = 100_001).isEmpty())
    }

    @Test
    fun onlyGatewaysOfferingTheKindAreCandidates() {
        val r = GatewayRegistry()
        r.update(gw("searchOnly", hops = 1, lastSeen = 0, kinds = setOf(QueryKind.SEARCH)))
        r.update(gw("aiOnly", hops = 2, lastSeen = 0, kinds = setOf(QueryKind.ASK_AI)))
        assertEquals(listOf("aiOnly"), r.candidates(QueryKind.ASK_AI, now = 0).map { it.deviceId })
    }

    @Test
    fun excludedGatewaysAreSkipped() {
        val r = GatewayRegistry()
        r.update(gw("a", hops = 1, lastSeen = 0))
        r.update(gw("b", hops = 2, lastSeen = 0))
        assertEquals(listOf("b"), r.candidates(QueryKind.SEARCH, now = 0, exclude = setOf("a")).map { it.deviceId })
    }

    @Test
    fun announcingNoKindsRemovesTheGateway() {
        val r = GatewayRegistry()
        r.update(gw("a", hops = 1, lastSeen = 0))
        r.update(gw("a", hops = 1, lastSeen = 1, kinds = emptySet()))
        assertTrue(r.active(now = 1).isEmpty())
    }

    @Test
    fun rateLimiterAllowsUpToTheLimitThenBlocks() {
        val limiter = RateLimiter(maxEvents = 3, windowMs = 1000)
        assertTrue(limiter.tryAcquire("x", 0))
        assertTrue(limiter.tryAcquire("x", 1))
        assertTrue(limiter.tryAcquire("x", 2))
        assertFalse(limiter.tryAcquire("x", 3))
    }

    @Test
    fun rateLimiterIsPerKeyAndRecoversAfterTheWindow() {
        val limiter = RateLimiter(maxEvents = 1, windowMs = 1000)
        assertTrue(limiter.tryAcquire("a", 0))
        assertFalse(limiter.tryAcquire("a", 500))
        assertTrue(limiter.tryAcquire("b", 500))
        assertTrue(limiter.tryAcquire("a", 1000))
    }
}
