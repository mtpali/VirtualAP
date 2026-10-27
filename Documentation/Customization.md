# VPN963 customization

## Traffic and device names

The first card lists each authorized station as `name : 25 MB` or `name : 2 GB`.
In routed mode the value comes from per-MAC mangle-table accounting for forwarded
IPv4 traffic, independent of hostapd/iw's potentially frozen byte counters. Each
MAC has an upload and download chain. The chains only count and RETURN; they do
not accept/drop packets or change routing, NAT or TTL. Static IP clients are
registered from the neighbor table when the screen is open. dnsmasq's lease hook
registers DHCP clients before they start transferring, even with the app closed.
These counters survive client reconnects and reset with the hotspot session.
An IP reassigned to a new MAC does not carry over the previous owner's usage.
An APK update with a running hotspot begins accounting on the first refresh;
restart the hotspot once after this update for a complete session measurement.

Managed/bridged mode retains hostapd/iw radio accounting, including IPv6 where
the driver reports it. This fallback follows the association/driver reset boundary
and can include local traffic. Neither source is a carrier billing meter. No
packet-capture service or usage-history database is installed. Bytes use powers
of 1024 with compact B/KB/MB/GB labels. Equal advertised names remain separate
entries identified by MAC.

Only associated, authorized unicast stations from hostapd are shown. The AP's own
MAC, all-zero placeholders, multicast/broadcast addresses, driver-only records
and old DHCP leases never create a connected row. The label still comes from WPS
or DHCP exactly as before; it is not a guaranteed hardware identification.
Unknown names fall back to `Device` plus the MAC suffix. There is no OUI lookup or
external upload. Missing usable counters show `Unavailable`.

The screen refreshes every three seconds while the hotspot is running and every
ten seconds while stopped. Polling is cancelled when the main screen is no longer
started. Counter-only changes do not refresh the notification or Quick Settings tile.

## Build and APKs

Build both static backend architectures with `scripts/build-static.sh`, then run
`cd Android && ./build.sh release` with JDK 17 and Android SDK 34. Release mode
shrinks code and resources and packages only English translations, including those
from dependencies. Runtime libraries remain the upstream ones; JUnit is test-only.

The CI artifact `virtualap-apk` contains only `VirtualAP-v2.0.5-arm64-v8a.apk`.
The visible version and VERSION file remain 2.0.5 as requested. CI builds only the
aarch64 backend and passes `-PbackendArches=aarch64` to every Android build.
This APK is for 64-bit ARM systems. Backend binaries still come from the pinned
source submodules. Local universal builds remain available through the existing
backendArches option.

Development CI builds use the upstream fallback signing behavior. For distributable
releases, configure your own persistent RELEASE_KEYSTORE, KEYSTORE_PASSWORD,
KEY_ALIAS and KEY_PASSWORD GitHub secrets. Your fork cannot update an installed
upstream APK signed with the upstream author's key.

## Validation

Automated checks cover shell syntax, empty/routed/bridged/stopped status output,
authoritative station lists, invalid and synthetic MACs, frozen driver counters,
network-counter priority, untrusted hostnames, duplicate names and compact units.
CI also performs real TCP transfers in isolated Linux network namespaces using
BusyBox flock. That regression checks both directions, two independent users,
repeated registration, DHCP IP reuse, stale deletion, input validation and scoped
cleanup that preserves other apps' chains. Android unit tests, release lint and
the optimized ARM64 APK build must pass.

Hardware validation still requires a rooted Android phone:

1. Start the hotspot, connect two clients, and verify separate rows at the top.
2. Transfer a known-size file in both directions. Confirm each client's counter
   increases independently. Allow for local protocol traffic and driver accounting.
3. Reconnect one client, restart the hotspot and change its private MAC. Verify the
   session reset and that disconnected clients disappear. Reconnecting in routed
   mode should retain the current session total for the same MAC.
4. Test IPv4 and, in managed mode with OpenWrt, IPv6. Verify DHCP names when supplied.
5. In routed mode confirm byte counts still increase if radio counters are frozen.
   In managed mode without usable radio counters, confirm `Unavailable`.
6. Open settings and the Telegram channel with and without Telegram installed.
7. Check round, rounded-square and themed launcher icons on a real launcher.
8. Change the system to light mode. The app and its startup window must stay black.
9. Background the app or open settings. Repeated root status calls should stop.
