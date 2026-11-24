# noscat android

Android client for [noscat](https://github.com/Letdown2491/noscat), a private
device mesh on Nostr: membership is a Marmot (MLS) group on Nostr relays, and data
goes device to device over FIPS. Native Kotlin (Compose) over a Rust UniFFI bridge;
the protocol lives in the noscat workspace (`noscat-core`/`noscat-client`/
`noscat-ffi`), so this repo contains no protocol code.

Status: phase 1 in progress - chat, file send/receive, port forwards to a member's
services, and joining a mesh by scanning a member's QR. It is on noscat 0.5.0,
which is not released yet: the native bridge is built from a local noscat checkout,
and the app cannot talk to 0.4 devices. Roadmap and agent guidance in
[AGENTS.md](AGENTS.md).

## Joining a mesh

The app cannot create a mesh or invite devices yet; a device running the noscat
CLI does that.

1. On a computer in the mesh, run `noscat up` (with `--accept-joins` while you
   add devices, if you want joins approved automatically). It prints a QR.
2. In the app, tap **Scan a mesh device's QR** (or paste that device's npub).
   The phone sends it a join request and waits up to three minutes.
3. On the CLI, type the `invite npub1...` line it prints (not needed with
   `--accept-joins`). The phone joins and shows the device list.

Every mesh needs one member reachable from the internet; on one Wi-Fi network,
devices find each other directly.

**Disconnect** (connection chip or Settings) takes the phone offline but keeps
it in the mesh, even across restarts; **Reconnect** brings it back as the same
device. **Forget mesh** (Settings) asks the other members to remove the phone,
then deletes its identity and mesh data (received files stay); to come back,
join again as a new device. If no member is online, the removal completes when
one next comes online.

## Building

Two steps: the native bridge, then the app.

1. Native bridge (cross-compiles the `noscat-ffi` crate in the podman
   container, mounts the host Android NDK, strips the .so into
   `app/src/main/jniLibs/`, and regenerates the Kotlin bindings):

   ```
   ./scripts/build-native.sh local      # build from the ~/Documents/noscat checkout
   ./scripts/build-native.sh <version>  # or a published noscat-ffi (0.5.0 once released)
   ```

   Either way the Rust library is a debug build (stripped, not optimized), so
   transfers are slower than a release build would be.

2. The app itself (needs the JDK 21 tarball in `~/opt`):

   ```
   JAVA_HOME=$(echo ~/opt/jdk-21*) ./gradlew :app:assembleDebug --no-daemon
   ```

The APK lands in `app/build/outputs/apk/debug/`. Install it over USB or wireless
debugging with `~/Android/Sdk/platform-tools/adb install -r
app/build/outputs/apk/debug/app-debug.apk`.
