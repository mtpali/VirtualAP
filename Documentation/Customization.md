# VPN963 customization

## Traffic and device names

The first card lists each authorized station as `name : 25 MB` or `name : 2 GB`.
The value is RX + TX from the access point's driver counters, preferring hostapd's
64-bit counters and falling back to `iw station dump`. It is Wi-Fi traffic during
the current association, including local transfers, rather than carrier billing.
The counters reset when that client reconnects, the hotspot restarts or the driver
resets. No background packet capture, persistent device history or extra firewall
rules are installed. Bytes are formatted in powers of 1024 using compact B/KB/MB/GB
labels. Devices with the same advertised name remain separate entries by MAC.

Only connected stations are shown; a stale DHCP lease never creates a device.
Routed mode reads VirtualAP's dnsmasq leases. Bridged mode asks the active
Droidspaces container for the conventional OpenWrt/dnsmasq lease locations.
The label is the WPS model/device name, or the DHCP hostname. These are supplied
by the peer, not a guaranteed hardware identification. Unknown names fall back to
`Device` plus the last part of the MAC address. There is no OUI database, network
upload or guess from a private MAC. If neither station API supplies usable byte
counters, usage is explicitly `Unavailable`, never a fabricated zero.

The screen refreshes every three seconds while the hotspot is running and every
ten seconds while stopped. Polling is cancelled when the main screen is no longer
started. Counter-only changes do not refresh the notification or Quick Settings tile.

## Build and APKs

Build both static backend architectures with `scripts/build-static.sh`, then run
`cd Android && ./build.sh release` with JDK 17 and Android SDK 34. Release mode
shrinks code and resources and packages only English translations, including those
from dependencies. Runtime libraries remain the upstream ones; JUnit is test-only.

The CI artifact `virtualap-apk` contains a universal APK and
`VirtualAP-arm64-v8a.apk`. Use the ARM64 APK for a 64-bit ARM phone such as a
POCO X3 NFC; use the universal APK for a 32-bit ARM system. ARM64-only packaging
is also available with `-PbackendArches=aarch64`. The default still bundles both
architectures. All backend binaries are built from the pinned source submodules.

Development CI builds use the upstream fallback signing behavior. For distributable
releases, configure your own persistent RELEASE_KEYSTORE, KEYSTORE_PASSWORD,
KEY_ALIAS and KEY_PASSWORD GitHub secrets. Your fork cannot update an installed
upstream APK signed with the upstream author's key.

## Validation

Automated checks cover shell syntax, routed/bridged/stopped status output, byte
counter parsing, 64-bit counters, driver fallback, missing metrics, untrusted
hostnames, duplicate names, disconnects and compact units. CI also runs Android
unit tests, release lint and optimized release builds.

Hardware validation still requires a rooted Android phone:

1. Start the hotspot, connect two clients, and verify separate rows at the top.
2. Transfer a known-size file in both directions. Confirm each client's counter
   increases independently. Allow for local protocol traffic and driver accounting.
3. Reconnect one client, restart the hotspot and change its private MAC. Verify the
   per-connection reset and that disconnected clients disappear.
4. Test IPv4 and, in managed mode with OpenWrt, IPv6. Verify DHCP names when supplied.
5. If a driver supplies neither counter API, confirm `Unavailable` instead of zero.
6. Open settings and the Telegram channel with and without Telegram installed.
7. Check round, rounded-square and themed launcher icons on a real launcher.
8. Change the system to light mode. The app and its startup window must stay black.
9. Background the app or open settings. Repeated root status calls should stop.
