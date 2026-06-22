# Android Light-Client Build Spike — Design

**Date:** 2026-06-22
**Status:** Approved (design); pending implementation plan
**Branch:** fresh feature branch (e.g. `spike/light-client-android-build`)

## Purpose

De-risk the last open MVP-2 item — an embedded CKB light client — by settling its
single biggest unknown **before** any app integration: can `ckb-light-client`
(SQLite backend) be built for Android and run on-device as a subprocess that
serves the indexer RPC the wallet already consumes?

This is a **feasibility spike**, not the feature. It produces a build recipe, an
on-device runtime proof, and a documented go/no-go on the subprocess-binary
shape. It deliberately writes **no** `LightClientProvider`, foreground service,
or Settings UI — those belong to a follow-up integration checkpoint.

## Key Findings (from context exploration)

- **App plug-in point exists.** Chain access is behind the `ChainProvider`
  interface (`wallet-core/.../chain/ChainProvider.kt`); `ChainManager` runs a
  provider pool with failover. The app depends on **indexer-style** RPC:
  `get_cells`, `get_cells_capacity`, `get_transactions` (plus node RPC). A local
  light client is just another `ChainProvider` pointed at `127.0.0.1:PORT` — but
  it requires a `set_scripts` registration step the current code lacks (deferred
  to integration).
- **`ckb-light-client-lite` is a build wrapper, not source.** The Rust lives
  upstream at `nervosnetwork/ckb-light-client`. SQLite support landed in
  **v0.5.5-rc1** (the pin we need; v0.5.4 predates it).
- **No openssl on the SQLite path.** `openssl` is optional, gated behind the
  `vendored-openssl` feature only — absent from `default`/`sqlite`. SQLite is
  `rusqlite` with `bundled` (compiles SQLite C via NDK clang). rocksdb is
  optional and excluded. RPC server is `jsonrpc-http-server 18.0` (plain HTTP,
  localhost — no TLS). This collapses the NDK risk dramatically.
- **Toolchain is ready.** `aarch64-linux-android` Rust target installed,
  `cargo-ndk 4.1.2`, NDK 27.1/27.2 present. `rust-core` already cross-compiles to
  `aarch64-linux-android` (including the C dep `secp256k1-sys`), proving the
  C-via-NDK path for this project.

## Scope

**In:** building the binary for Android; producing a runtime proof that the
on-device light client answers `get_cells` for the smoke-test wallet's locks; a
committed build script; an evidence-backed go/no-go.

**Out (deferred to the integration checkpoint):** `LightClientProvider`,
`set_scripts` wiring, foreground service, Settings start/stop + sync UI,
`RpcHealthChecker` tip-lag thresholding, packaging the `.so` into the APK.

## Approach

### Source vendoring

The build script clones upstream `nervosnetwork/ckb-light-client` at the pinned
tag **`v0.5.5-rc1`** into a **gitignored** build dir (e.g.
`build/ckb-light-client-src/`) if absent — not a git submodule, keeping the
wallet repo clean. Mirrors how `ckb-light-client-lite`'s CI sources upstream.
(If `v0.5.5-rc1` proves problematic, fall back to `develop`, which carries the
same `sqlite` feature.)

### Milestone 1 — Build (no device required)

Build via cargo-ndk for both ABIs (arm64 = device, x86_64 = fast emulator):

```
cargo ndk -t arm64-v8a -t x86_64 build --release \
  --bin ckb-light-client --no-default-features --features sqlite
```

**Success criteria:**
- Both ELFs are produced.
- `readelf -d` / `file` confirms Android Bionic linkage (NDK sysroot), arm64 +
  x86_64 respectively.
- Dependency audit confirms **no openssl, no rocksdb** linked (`cargo tree
  --no-default-features --features sqlite` + symbol/`ldd`-style check).
- Recipe captured: NDK version, env, exact invocation, binary sizes.

Deliverable: `scripts/build-light-client-android.sh` (clones pinned source if
needed, runs cargo-ndk, prints artifact facts), committed to the wallet repo.

### Milestone 2 — Runtime proof (requires an Android target)

`adb devices` is currently empty. **Default:** use Phill's physical arm64 device
(connect at M2); **fallback:** stand up an arm64 Android emulator. M1 does not
block on this.

Steps:
1. Stage the arm64 ELF as `libckblightclient.so` and push it to a test app's
   `nativeLibraryDir` (the read-only, exec-permitted dir — the established
   Tor/IPFS Android pattern), or for the spike, push to a device location and
   exec from an allowed path to confirm the mechanism.
2. Generate a testnet `config.toml`: SQLite store dir in app-private storage,
   RPC `listen_address = 127.0.0.1:9000`, testnet network + bootnodes.
3. Launch the binary; over `adb` + localhost, drive:
   - `get_tip_header` → confirms it boots + begins header sync.
   - `set_scripts` → register the smoke-test wallet's lock script(s).
   - poll `get_cells_capacity` / `get_cells` → until the wallet's known testnet
     cells appear.

**Success criterion:** the on-device light client returns the smoke-test
wallet's real testnet cells via `get_cells`, proving (a) the Android ELF runs,
(b) exec-from-nativeLibDir works at the target API level, (c) the indexer RPC the
wallet needs is served locally.

## Risks

| Risk | Likelihood | Mitigation |
|------|-----------|------------|
| `rusqlite` bundled `cc` build fails under NDK | Low | cargo-ndk wires the NDK clang sysroot; `secp256k1-sys` already builds this way in `rust-core` |
| Hidden transitive C dep needs NDK config | Low | dep audit in M1; `secp256k1` precedent |
| exec-from-`nativeLibDir` blocked at the device's API level | Medium | the one genuine unknown M2 settles; if blocked, this is the go/no-go signal to switch to the JNI-lib shape |
| `v0.5.5-rc1` tag won't build cleanly | Low | fall back to `develop` (same `sqlite` feature) |
| Light-client sync too slow to observe in the spike | Low | testnet syncs fast; `set_scripts` with a recent start block bounds the scan |

## Definition of Done

- `scripts/build-light-client-android.sh` committed; M1 success criteria met and
  recorded.
- M2 runtime proof captured (or, if exec-from-nativeLibDir is blocked, a
  documented go/no-go recommending the JNI-lib shape with evidence).
- Spec updated with the outcome; follow-up integration checkpoint scoped.
