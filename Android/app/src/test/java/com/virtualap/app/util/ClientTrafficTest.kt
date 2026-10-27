package com.virtualap.app.util

import org.junit.Assert.*
import org.junit.Test

class ClientTrafficTest {
    private fun snapshot(stations: String = "", driver: String = "", leases: String = "", connected: String? = null, accounting: String = "") =
        "running=1\n[stations]\n$stations\n" +
            (connected?.let { "[connected]\n$it\n" } ?: "") +
            "[driver]\n$driver\n[leases]\n$leases\n[accounting]\n$accounting\n[end_clients]\n"

    @Test fun combinesBothDirectionsAndUsesDhcpName() {
        val clients = ClientTraffic.parse(snapshot(
            "AA:BB:CC:DD:EE:01\nflags=[AUTH][ASSOC][AUTHORIZED]\nrx_bytes=1048576\ntx_bytes=25165824",
            leases = "0 aa:bb:cc:dd:ee:01 192.168.42.2 samsung *"
        ))
        assertEquals(1, clients.size)
        assertEquals("samsung", clients.single().name)
        assertEquals("192.168.42.2", clients.single().ipAddress)
        assertEquals("25 MB", ClientTraffic.formatBytes(clients.single().totalBytes!!))
    }

    @Test fun prefers64BitCountersOverWrapped32BitCounters() {
        val peer = ClientTraffic.parse(snapshot("aa:bb:cc:dd:ee:01\nflags=[ASSOC][AUTHORIZED]\nrx_bytes=1\ntx_bytes=2\nrx_bytes_64=4294967297\ntx_bytes_64=4294967298")).single()
        assertEquals(8589934595L, peer.totalBytes)
    }

    @Test fun fallsBackToDriverCountersWhenHostapdCannotReadThem() {
        val peer = ClientTraffic.parse(snapshot("aa:bb:cc:dd:ee:01\nflags=[ASSOC][AUTHORIZED]",
            "Station aa:bb:cc:dd:ee:01 (on ap0)\n\trx bytes: 100\n\ttx bytes: 200")).single()
        assertEquals(300L, peer.totalBytes)
    }

    @Test fun driverCannotInventConnectedDevicesWhenHostapdIsEmpty() {
        val driver = "Station 00:00:00:00:00:00 (on ap0)\nrx bytes: 0\ntx bytes: 0\nStation aa:bb:cc:dd:ee:01 (on ap0)\nrx bytes: 50\ntx bytes: 100"
        assertTrue(ClientTraffic.parse(snapshot("FAIL", driver)).isEmpty())
        assertTrue(ClientTraffic.parse(snapshot("", driver, connected = "")).isEmpty())
    }

    @Test fun missingOrInvalidCountersAreUnavailableInsteadOfZero() {
        val peers = ClientTraffic.parse(snapshot("aa:bb:cc:dd:ee:01\nflags=[ASSOC][AUTHORIZED]\nrx_bytes=-1\ntx_bytes=garbage"))
        assertNull(peers.single().totalBytes)
        assertNull(ClientTraffic.parse(snapshot("aa:bb:cc:dd:ee:01\nflags=[ASSOC][AUTHORIZED]\nrx_bytes=10")).single().totalBytes)
    }

    @Test fun filtersUnauthenticatedPeersAndStaleDhcpLeases() {
        val peers = ClientTraffic.parse(snapshot(
            "aa:bb:cc:dd:ee:01\nflags=[AUTH]\naa:bb:cc:dd:ee:02\nflags=[ASSOC][AUTHORIZED]",
            "Station aa:bb:cc:dd:ee:01 (on ap0)",
            "0 aa:bb:cc:dd:ee:03 192.168.42.3 stale *"
        ))
        assertEquals(listOf("aa:bb:cc:dd:ee:02"), peers.map { it.macAddress })
    }

    @Test fun namesRemainDataAndUnknownNamesStayUnknown() {
        val peer = ClientTraffic.parse(snapshot("aa:bb:cc:dd:ee:01\nflags=[ASSOC][AUTHORIZED]",
            leases = "0 aa:bb:cc:dd:ee:01 192.168.42.2 \$(touch_pwned) *")).single()
        assertEquals("\$(touch_pwned)", peer.name)
        assertNull(ClientTraffic.parse(snapshot("aa:bb:cc:dd:ee:01\nflags=[ASSOC][AUTHORIZED]", leases = "0 aa:bb:cc:dd:ee:01 invalid * *")).single().name)
        assertNull(ClientTraffic.parse(snapshot("aa:bb:cc:dd:ee:01\nflags=[ASSOC][AUTHORIZED]", leases = "0 aa:bb:cc:dd:ee:01 999.1.1.1 iPhone *")).single().ipAddress)
    }

    @Test fun keepsSameNamedClientsSeparateAndDoesNotCarryCountersAcrossSnapshots() {
        val payload = snapshot("aa:bb:cc:dd:ee:01\nflags=[ASSOC][AUTHORIZED]\nrx_bytes=100\ntx_bytes=50\naa:bb:cc:dd:ee:02\nflags=[ASSOC][AUTHORIZED]\nrx_bytes=5\ntx_bytes=10",
            leases = "0 aa:bb:cc:dd:ee:01 192.168.42.2 iPhone *\n0 aa:bb:cc:dd:ee:02 192.168.42.3 iPhone *")
        assertEquals(listOf(150L, 15L), ClientTraffic.parse(payload).map { it.totalBytes })
        assertEquals(3L, ClientTraffic.parse(snapshot("aa:bb:cc:dd:ee:01\nflags=[ASSOC][AUTHORIZED]\nrx_bytes=1\ntx_bytes=2")).single().totalBytes)
        assertTrue(ClientTraffic.parse("running=0\nclients=0").isEmpty())
    }

    @Test fun liveNetworkAccountingOverridesFrozenRadioCountersAndKeepsTheName() {
        val station = "aa:bb:cc:dd:ee:01\nflags=[ASSOC][AUTHORIZED]\nrx_bytes=0\ntx_bytes=0"
        val lease = "0 aa:bb:cc:dd:ee:01 192.168.42.2 samsung *"
        val first = ClientTraffic.parse(snapshot(station, leases = lease, connected = "aa:bb:cc:dd:ee:01",
            accounting = "rx aabbccddee01 1048576\ntx aabbccddee01 1024")).single()
        val second = ClientTraffic.parse(snapshot(station, leases = lease, connected = "aa:bb:cc:dd:ee:01",
            accounting = "rx aabbccddee01 5242880\ntx aabbccddee01 2048")).single()
        assertEquals("samsung", second.name)
        assertTrue(second.totalBytes!! > first.totalBytes!!)
        assertEquals(5244928L, second.totalBytes)
    }

    @Test fun accountingAndLeasesCannotCreateDisconnectedRows() {
        val stale = "aa:bb:cc:dd:ee:01\nflags=[ASSOC][AUTHORIZED]"
        assertTrue(ClientTraffic.parse(snapshot(stale, connected = "",
            leases = "0 aa:bb:cc:dd:ee:01 192.168.42.2 iPhone *",
            accounting = "rx aabbccddee01 100\ntx aabbccddee01 100")).isEmpty())
    }

    @Test fun rejectsZeroBroadcastMulticastAndTheAccessPointsOwnMac() {
        val addresses = "00:00:00:00:00:00\nff:ff:ff:ff:ff:ff\n01:00:5e:00:00:01\naa:bb:cc:dd:ee:01"
        assertTrue(ClientTraffic.parse("ap_mac=aa:bb:cc:dd:ee:01\n" + snapshot(connected = addresses)).isEmpty())
        assertTrue(ClientTraffic.parse(snapshot("aa:bb:cc:dd:ee:01")).isEmpty())
    }

    @Test fun validZeroNetworkCountersStayZeroAndPartialSnapshotsFallBackSafely() {
        val station = "aa:bb:cc:dd:ee:01\nflags=[ASSOC][AUTHORIZED]\nrx_bytes=3\ntx_bytes=4"
        assertEquals(0L, ClientTraffic.parse(snapshot(station, accounting = "rx aabbccddee01 0\ntx aabbccddee01 0")).single().totalBytes)
        assertEquals(7L, ClientTraffic.parse(snapshot(station, accounting = "rx aabbccddee01 100")).single().totalBytes)
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
