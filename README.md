# Homedeck

A personal Android launcher for self-hosted services. Tiles open your
dashboards in an in-app browser whose cookies, localStorage and cache live
inside the app — sign in once and the session survives restarts.

## Features

- Tile dashboard: name, address, a large emoji icon picked from a 90+ icon
  grid (or the site's own favicon), accent color, drag to reorder,
  duplicate, edit, remove.
- In-app browser (not your phone's browser): keeps you inside the app,
  with a compact header that scrolls away while reading, pull-to-refresh,
  loading progress, per-site back navigation, file uploads, HTTP Basic
  auth, desktop-site toggle, share/copy link.
- Sessions that stick: cookies, localStorage, IndexedDB and cache are
  persisted per app and flushed on exit. Settings has an explicit
  "clear cookies & site data" button — nothing is ever cleared silently.
- Live status dots: each tile shows Online / Reachable / Offline based on
  a lightweight probe of your own service (toggleable in Settings).
- LAN-first: plain http:// addresses work, and self-signed certificates
  show a one-time "Trust anyway" dialog remembered per host.
- Material 3 with four themes: System, Dark, Light, and AMOLED (pure black
  background and surfaces for battery saving on OLED screens).
- Zero telemetry. No analytics SDKs, no CDN fonts or icons, no external
  calls of any kind except to the services you add yourself.

## Terminals and Docker

Anything with a web UI works: Portainer, Dockge, Yacht, Proxmox, Grafana,
Home Assistant, Uptime Kuma, Speedtest Tracker, and so on.

For shell access, point a tile at a web terminal — ttyd, wetty or
Guacamole — which speaks WebSockets and works inside the app:

    ttyd -W -p 7681 -c user:pass bash

For real SSH apps (Termux, ConnectBot), links with ssh:// open externally.

## Build

The toolchain (Temurin JDK 17, Android SDK 34, Gradle 8.8) installs into
~/tools:

    bash tools/setup-android-toolchain.sh

Then build and sign a release APK:

    export JAVA_HOME=~/tools/jdk17
    export ANDROID_HOME=~/tools/android-sdk
    export PATH="$JAVA_HOME/bin:~/tools/gradle-8.8/bin:$PATH"
    gradle :app:assembleRelease

Output: `app/build/outputs/apk/release/app-release.apk`

Unit tests (tile storage, URL normalization, status classification):

    gradle :app:test

## Install

    adb install -r app/build/outputs/apk/release/app-release.apk

or copy the APK to the phone and open it (allow "install unknown apps"
for your file manager when prompted).

## Debug mode & diagnostics

Early development phase default: the app starts in **debug mode**.

- **Toggle**: Settings -> Debug mode. On = status dots show the real reason
  ("HTTP 200, 340ms", "connection refused", "TLS certificate rejected"),
  the dashboard subtitle is prefixed DEBUG, the in-app browser shows the
  current URL, and logging runs. Off = clean everyday UI.
- **Self-diagnosis**: the ⓘ button on the dashboard toolbar or
  Settings -> Open diagnostics. Runs local checks (storage, tile data,
  cookie/session store, WebView engine, every added service, crash history)
  with pass/warn/fail detail and timing, plus a log tail. Copy or share the
  report — it never leaves the device on its own.
- **Logging + crash capture**: crashes are written to the app's private
  storage and shown in Diagnostics, so a broken build explains itself on the
  next launch. In debug mode a startup failure keeps the app open showing the
  stack trace instead of crash-looping.
- **Privacy unchanged**: no analytics, no telemetry; the only network
  traffic is probing the services you added yourself.

## Updates

The app can check this repo's public GitHub releases for a newer version —
the only network request it makes that is not one of your own services. It
reads public release metadata and sends nothing else (documented here as the
deliberate exception to the zero-external-calls promise).

- Settings -> "Check for updates": one-off check whenever you want.
- Settings -> "Check automatically": OFF by default (opt-in, per the no
  phone-home rule); when on, checks at most once a day while the dashboard
  is open and shows a Download snackbar when something newer exists.
- Downloading opens the release APK in the browser; install over the old
  version as usual.

## Signing

Release builds are signed with the keystore described in
`keystore.properties` (git-ignored). Keep the keystore and its password
safe — updating the app later over the installed one requires the same
signing key. Without `keystore.properties` the build falls back to the
debug key.

## First run

The dashboard starts with example tiles (Uptime Kuma, Speedtest Tracker,
Terminal, Portainer, Grafana, Home Assistant) using `.lan` hostnames.
Edit or remove them to match your network — long-press drag to reorder,
the ⋮ button on each tile edits/duplicates/removes.
