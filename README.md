# SafeBrowse — Android

DNS-level site blocking with a Romans 6 gate in front of every way out.

Blocking a site takes one tap. Unblocking takes all 23 verses at reading pace,
plus a word check from one of them. Stopping protection entirely takes the same
chapter — otherwise the dashboard toggle would be a one-tap way around the app.

## How the blocking actually works

`SafeVpnService` is a local `VpnService`. It is not a VPN in the usual sense:
nothing is tunnelled to a remote server, and no traffic leaves the device
except ordinary DNS to whichever resolver you pick — or, on networks that
block outside resolvers (common on campus and hotel Wi-Fi), to the network's
own resolver. Filtering happens before forwarding either way.

```
app requests a domain
      ↓
TUN interface (DNS server 10.0.0.1, route 10.0.0.1/32 only)
      ↓
PacketUtils — is this IPv4 UDP to port 53?
      ↓
DnsParser — pull the domain out of the question section
      ↓
DomainMatcher — suffix match against the in-memory blocklist
      ↓
 blocked → synthesize NXDOMAIN, write straight back to the TUN
 allowed → DnsForwarder on a protected socket → 1.1.1.1 → write the answer back
           (no answer from 1.1.1.1 → the network's own resolver;
            no answer at all → SERVFAIL, never silence)
```

Because this sits below the browser, **incognito mode changes nothing** and
needs no special handling.

Battery cost is low by construction: `setBlocking(true)` means the read parks
the thread until a packet arrives. There is no polling loop, no
AccessibilityService, no screen monitoring.

Blocklist changes propagate live — `SafeVpnService` collects a Flow from Room,
so unblocking a domain takes effect immediately rather than at the next restart.

## Build and deploy

Requires JDK 17+ and the Android SDK (platform 35). Builds with AGP 9.2 and
Gradle 9.4.1 through the wrapper.

```bash
cd SafeBrowse
./gradlew test                    # unit tests: matcher, normalizer, gate
./gradlew assembleDebug           # → app/build/outputs/apk/debug/app-debug.apk
```

Install on a device:

```bash
./gradlew installDebug
# or
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Debug builds are signed with the standard debug keystore, so the APK installs
directly. Release builds are signed from `keystore.properties` at the project
root (git-ignored). Without that file, `assembleRelease` produces
`app-release-unsigned.apk`.

```bash
keytool -genkeypair -v -keystore safebrowse-release.jks -keyalg RSA \
        -keysize 2048 -validity 10000 -alias safebrowse
```

`keystore.properties`:

```properties
storeFile=safebrowse-release.jks
storePassword=...
keyAlias=safebrowse
keyPassword=...
```

```bash
./gradlew assembleRelease   # → app/build/outputs/apk/release/app-release.apk
./gradlew bundleRelease     # → app/build/outputs/bundle/release/app-release.aab (Play)
```

### First run

1. Open the app. It seeds the starter blocklist from
   `assets/default_blocklist.txt` on first launch.
2. Tap the power button. Android shows its own VPN consent dialog — this is the
   system asking, not the app, and it cannot be skipped.
3. Grant the notification permission on Android 13+ so the foreground service
   notification can appear.

### Always-on VPN (recommended)

Without this, the filter stops when the app is killed.

**Settings → Network & internet → VPN → SafeBrowse (gear icon) → Always-on VPN**,
and also enable **Block connections without VPN**.

Turning Always-on off requires going into system settings, which is friction on
top of the gate — that is the point.

## What this does not do

- **It blocks domains, not pages.** DNS filtering cannot see HTTPS paths.
  `example.com/specific-page` is not a thing this can express.
- **Apps with their own DoH client bypass it.** The starter list blocks the
  well-known DoH endpoints to force a fallback to normal DNS, but a hardcoded
  resolver inside an app will still get through. There is no MITM interception
  here and there will not be.
- **IPv6 is not filtered yet.** The TUN has only an IPv4 address and /32
  routes to its own DNS address and a few public resolvers. The system sends
  its DNS to the tunnel, but an app that queries an IPv6 resolver directly
  bypasses the filter.
- **It is uninstallable.** Deliberately. A blocker you cannot remove is
  malware, not self-control. The friction is the reading.

## Project layout

```
app/src/main/kotlin/com/safebrowse/
├── dns/          DnsParser, DnsPacketBuilder, DnsForwarder — wire format
├── vpn/          SafeVpnService, PacketUtils — the TUN loop
├── gate/         Romans6, Romans6GateScreen — the chapter and its UI
├── database/     Room entity + DAO
├── repository/   BlocklistRepository — seeding, CRUD, import
├── settings/     AppPreferences (DataStore)
├── ui/           MainActivity, navigation, Compose screens
├── util/         DomainNormalizer, DomainMatcher, NotificationHelper
└── receivers/    BootReceiver
```

## Tests

```bash
./gradlew test
```

Covers domain normalization, suffix matching (including the case that matters —
`notexample.com` must *not* match `example.com`), thread-safe concurrent access
to the matcher, and the gate: every verse present, dwell floor enforced, the
whole chapter costing at least two minutes, and the word check always pointing
at a real word in the verse it names.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Nothing is blocked | VPN consent not granted | Tap the power button, accept the system dialog |
| Filtering stops when app is killed | Always-on VPN off | Enable it in system VPN settings |
| A site still resolves | Browser using DoH | Disable Secure DNS in the browser, or keep the DoH endpoints blocked |
| Subdomain slips through | Not covered by suffix match | Add the parent domain, not the subdomain |
| Blocklist changes not applied | Should not happen | The service collects a Room Flow; check Logcat for `Blocklist refreshed` |
