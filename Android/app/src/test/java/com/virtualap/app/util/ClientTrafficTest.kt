package com.virtualap.app.util

import org.junit.Assert.*
import org.junit.Test

class ClientTrafficTest {
    private fun snapshot(stations: String = "", driver: String = "", leases: String = "") =
        "running=1\n[stations]\n$stations\n[driver]\n$driver\n[leases]\n$leases\n[end_clients]\n"

    @Test fun combinesBothDirectionsAndUsesDhcpName() {
        val clients = ClientTraffic.parse(snapshot(
            "AA:BB:CC:DD:EE:01\nflags=[AUTH][ASSOC][AUTHORIZED]\nrx_bytes=1048576\ntx_bytes=25165824",
            leases = "0 aa:bb:cc:dd:ee:01 192.168.42.2 samsung *"
        ))
        assertEquals(1, clients.size)
        assertEquals("samsung", clients.single().name)
        assertEquals("192.168.42.2", clients.single().ipAddress)
        assertEquals("26 MB", ClientTraffic.formatBytes(clients.single().totalBytes!!))
    }

    @Test fun prefers64BitCountersOverWrapped32BitCounters() {
        val peer = ClientTraffic.parse(snapshot("aa:bb:cc:dd:ee:01\nrx_bytes=1\ntx_bytes=2\nrx_bytes_64=4294967297\ntx_bytes_64=4294967298")).single()
        assertEquals(8589934595L, peer.totalBytes)
    }

    @Test fun fallsBackToDriverCountersWhenHostapdCannotReadThem() {
        val peer = ClientTraffic.parse(snapshot("aa:bb:cc:dd:ee:01\nflags=[AUTHORIZED]",
            "Station aa:bb:cc:dd:ee:01 (on ap0)\n\trx bytes: 100\n\ttx bytes: 200")).single()
        assertEquals(300L, peer.totalBytes)
    }

    @Test fun driverCanSupplyTheListWhenHostapdIsUnavailable() {
        val peer = ClientTraffic.parse(snapshot("FAIL", "Station aa:bb:cc:dd:ee:01 (on ap0)\nrx bytes: 0\ntx bytes: 0")).single()
        assertEquals(0L, peer.totalBytes)
    }

    @Test fun missingOrInvalidCountersAreUnavailableInsteadOfZero() {
        val peers = ClientTraffic.parse(snapshot("aa:bb:cc:dd:ee:01\nrx_bytes=-1\ntx_bytes=garbage"))
        assertNull(peers.single().totalBytes)
        assertNull(ClientTraffic.parse(snapshot("aa:bb:cc:dd:ee:01\nrx_bytes=10")).single().totalBytes)
    }

    @Test fun filtersUnauthenticatedPeersAndStaleDhcpLeases() {
        val peers = ClientTraffic.parse(snapshot(
            "aa:bb:cc:dd:ee:01\nflags=[AUTH]\naa:bb:cc:dd:ee:02\nflags=[AUTHORIZED]",
            "Station aa:bb:cc:dd:ee:01 (on ap0)",
            "0 aa:bb:cc:dd:ee:03 192.168.42.3 stale *"
        ))
        assertEquals(listOf("aa:bb:cc:dd:ee:02"), peers.map { it.macAddress })
    }

    @Test fun namesRemainDataAndUnknownNamesStayUnknown() {
        val peer = ClientTraffic.parse(snapshot("aa:bb:cc:dd:ee:01",
            leases = "0 aa:bb:cc:dd:ee:01 192.168.42.2 \$(touch_pwned) *")).single()
        assertEquals("\$(touch_pwned)", peer.name)
        assertNull(ClientTraffic.parse(snapshot("aa:bb:cc:dd:ee:01", leases = "0 aa:bb:cc:dd:ee:01 invalid * *")).single().name)
        assertNull(ClientTraffic.parse(snapshot("aa:bb:cc:dd:ee:01", leases = "0 aa:bb:cc:dd:ee:01 999.1.1.1 iPhone *")).single().ipAddress)
    }

    @Test fun keepsSameNamedClientsSeparateAndDoesNotCarryCountersAcrossSnapshots() {
        val payload = snapshot("aa:bb:cc:dd:ee:01\nrx_bytes=100\ntx_bytes=50\naa:bb:cc:dd:ee:02\nrx_bytes=5\ntx_bytes=10",
            leases = "0 aa:bb:cc:dd:ee:01 192.168.42.2 iPhone *\n0 aa:bb:cc:dd:ee:02 192.168.42.3 iPhone *")
        assertEquals(listOf(150L, 15L), ClientTraffic.parse(payload).map { it.totalBytes })
        assertEquals(3L, ClientTraffic.parse(snapshot("aa:bb:cc:dd:ee:01\nrx_bytes=1\ntx_bytes=2")).single().totalBytes)
        assertTrue(ClientTraffic.parse("running=0\nclients=0").isEmpty())
    }

    @Test fun countersCannotOverflowTheTotal() {
        assertEquals(Long.MAX_VALUE, ConnectedClient("aa:bb:cc:dd:ee:01", receivedBytes = Long.MAX_VALUE, transmittedBytes = 1).totalBytes)
    }

    @Test fun formatsTheRequestedCompactUnitsWithAStableDecimalPoint() {
        assertEquals("0 B", ClientTraffic.formatBytes(0))
        assertEquals("25 MB", ClientTraffic.formatBytes(25L * 1024 * 1024))
        assertEquals("2 GB", ClientTraffic.formatBytes(2L * 1024 * 1024 * 1024))
        assertEquals("1.5 MB", ClientTraffic.formatBytes(1572864))
    }
}
