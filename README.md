# noscat android

Android client for [noscat](https://github.com/Letdown2491/noscat), the Nostr-native
mesh VPN. Native Kotlin (Compose) over a Rust UniFFI bridge; the protocol lives in the
published `noscat-client` crates, so this repo contains no protocol code.

Status: phase 1 in progress - a remote-reach client (dial `forward` tunnels to peers,
send and receive files). Roadmap and agent guidance in [AGENTS.md](AGENTS.md).

## Building

Rust parts build in the podman container (host has no toolchain); see AGENTS.md for
the exact commands. The app itself needs an Android SDK (compileSdk 35).
