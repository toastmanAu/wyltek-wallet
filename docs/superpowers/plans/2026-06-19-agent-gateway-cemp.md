# Agent Gateway — Plan B-CEMP: On-Chain CEMP-PQ Messaging — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the testnet-verified CEMP-PQ encrypted-messaging protocol (`~/ecms/cemp-pq`, JS) into the wallet so it can **create a profile cell** and **send a post-quantum encrypted message on-chain**, then expose it as a `CempMessagingSender` that fulfills the `MessagingSender` interface from Plan B1 (so the agent `messaging` scope works) and replaces the in-memory `MessagingService` stubs behind the unchanged UI.

**Architecture:** The novel crypto + molecule + Type-ID logic lives in a new Rust `cemp` module exposed over UniFFI (pure functions). Transaction assembly (cell selection, fee, broadcast) stays in Kotlin `WalletRepository`, **reusing the existing `build_transaction` + `sign_ckb_mldsa65` v2-lock CighashAll path** — CEMP cells are just custom outputs + output-data. ML-KEM-768 keys are derived deterministically from the wallet seed (HKDF) so they restore from the mnemonic. One protocol improvement over the JS prior art: the notification cell carries a real `MessagePointer` (tx_hash + index) instead of being empty.

**Tech Stack:** Rust 2021 + UniFFI 0.31; new crates `fips203` (ML-KEM-768) and `aes-gcm` (AES-256-GCM); existing `blake2b-ref`/`hkdf`/`sha2`/`fips204`; Kotlin (manual-DI `WalletRepository`), bindings package `com.wyltek.wallet.core.native`.

## Global Constraints

- **Use the v2 ML-DSA lock, not the JS legacy lock.** Testnet code hash `0xd70653f7fd51e173ec506b76081f37bf4acebb8a15dc79e6d4ad43ca4d3b78a4`, hash type `type`, dep tx `0x1074b1ac79213c22b5e32a0fde44a858a47f9575c9f54006a1deb80d32070cb1` index `3`, dep type `code` (`DEP_TYPE_CODE=0`). The legacy `0x8984f4…d310d` lock from the JS prior art is **deprecated — do not use**. Signing is the existing `sign_ckb_mldsa65` CighashAll digest (`blake2b("ckb-mldsa-msg", generate_ckb_tx_message_all)`), NOT the JS `blake2b("CKB-MLDSA-LOCK"‖txhash)`.
- **The profile's `ml_dsa_public_key` MUST equal the account's lock key** — derive it via the SAME `mldsa65_from_seed(seedHex)` call the signing path uses, so the profile matches the cell's lock.
- **CKB system Type ID** type script: code hash `0x00000000000000000000000000000000000000000000000000545950455f4944`, hash type `type`, args = `compute_type_id(inputs[0], output_index=0)`.
- **Crypto parameters copied verbatim from the prior art** (interop): ML-KEM-768; AES-256-GCM with a random 12-byte nonce, empty AAD; symmetric key = `blake2b(shared_secret, dkLen=32, personalization=b"CEMP-PQ-SYM-KEY_")` (16-byte personalization, no HKDF, no salt). KEM ciphertext is 1088 bytes; ML-DSA-65 pubkey 1952; ML-KEM-768 pubkey 1184.
- **Molecule tables** use the existing `molecule.rs` `serialize_table(&[field_bytes,...])` + `write_bytes`/`write_u32` helpers; `Bytes` = u32-LE length prefix + raw.
- **Amounts** shannons; reuse the existing fee handling in `WalletRepository` (the secp/PQ `minFeeEstimate` via `resolveSigningContext`). CEMP cells have data, so size capacity to fit (the existing builder reserves fee — follow the `sendCkb`/mint pattern).
- New Kotlin lives where the analogous code already is: `WalletRepository` (`app/.../data/`) and `MessagingService` (`wallet-core/.../messaging/`). UniFFI bindings + `.so` regen mirrors Plan B1 Task 1 (needs the Android NDK; operator step if unavailable).
- Commit after each task; conventional messages; no attribution footer.

## Prior-art reference (verbatim, for the port)

- Profile table fields, in order: `ml_dsa_public_key: Bytes(1952)`, `ml_kem_public_key: Bytes(1184)`, `metadata: Bytes`.
- EncryptedMessage table fields, in order: `kem_ciphertext: Bytes(1088)`, `nonce: Bytes(12)`, `ciphertext: Bytes(plaintext_len+16)`.
- MessagePointer (improvement: actually populated): `tx_hash: Bytes(32)`, `index: Uint32` (store as a 4-byte LE field).
- Encrypt: `ml_kem768.encapsulate(recipient_pub) -> (kem_ct, shared)`; `sym = blake2b(shared, 32, personal=b"CEMP-PQ-SYM-KEY_")`; `AES-256-GCM(sym, nonce12, plaintext) -> ct‖tag`.
- Recipient discovery: `findCells(lock=recipientLock, withData=true)`, take the cell whose type script is the system Type ID, parse its Profile data, read `ml_kem_public_key`.

## File Structure

| File | Responsibility |
|------|----------------|
| `rust-core/Cargo.toml` (modify) | add `fips203`, `aes-gcm` |
| `rust-core/src/cemp/mod.rs` (create) | module root, `CempError`, re-exports |
| `rust-core/src/cemp/keys.rs` (create) | `cemp_mlkem_from_seed`, `KemKeyPair` |
| `rust-core/src/cemp/crypto.rs` (create) | `cemp_encrypt`, `cemp_decrypt` |
| `rust-core/src/cemp/molecule.rs` (create) | `serialize_profile`, `parse_profile_kem`, `parse_profile_dsa`, `serialize_message_pointer`, `parse_message_pointer` |
| `rust-core/src/cemp/typeid.rs` (create) | `compute_type_id` |
| `rust-core/src/lib.rs` (modify) | `pub mod cemp;` + re-exports |
| `{wallet-core,app}/.../native/wyltekwalletcore.kt` + `.so` (regen) | bring CEMP exports into Kotlin |
| `wallet-core/.../chain/NetworkConfig.kt` (modify) | add Type-ID system constants (+ profile flag) |
| `app/.../data/WalletRepository.kt` (modify) | `createProfileCell`, `discoverProfileKem`, `sendCempMessage` |
| `wallet-core/.../messaging/Messaging.kt` (modify) | replace stubs; real encrypt/decrypt/send/discover |
| `app/.../agent/CempMessagingSender.kt` (create) | implements Plan B1 `MessagingSender` |
| `rust-core/examples/pq_testnet.rs` (modify) | `cemp-profile` + `cemp-send` harness commands |

---

### Task 1: ML-KEM-768 key derivation from seed

**Files:**
- Modify: `rust-core/Cargo.toml`
- Create: `rust-core/src/cemp/mod.rs`, `rust-core/src/cemp/keys.rs`
- Modify: `rust-core/src/lib.rs`

**Interfaces:**
- Produces: `enum CempError` (`uniffi::Error`, flat); `struct KemKeyPair { public_key_hex, secret_key_hex }`; `#[uniffi::export] fn cemp_mlkem_from_seed(seed_hex: String) -> Result<KemKeyPair, CempError>`.

- [ ] **Step 1: Add deps**

In `rust-core/Cargo.toml` under `# Crypto`:

```toml
# CEMP-PQ messaging
fips203 = { version = "0.4", features = ["default-rng"] }
aes-gcm = "0.10"
```

- [ ] **Step 2: Module root**

`rust-core/src/cemp/mod.rs`:

```rust
//! On-chain CEMP-PQ encrypted messaging: ML-KEM-768 key agreement + AES-256-GCM,
//! Profile / EncryptedMessage / MessagePointer molecule, and CKB Type ID. Pure
//! functions; tx assembly + signing reuse the existing v2 ML-DSA path in Kotlin.

pub mod keys;
pub mod crypto;
pub mod molecule;
pub mod typeid;

pub use keys::{cemp_mlkem_from_seed, KemKeyPair};
pub use crypto::{cemp_encrypt, cemp_decrypt};
pub use molecule::{serialize_profile, parse_profile_kem, parse_profile_dsa, serialize_message_pointer, parse_message_pointer};
pub use typeid::compute_type_id;

#[derive(Debug, thiserror::Error, uniffi::Error)]
#[uniffi(flat_error)]
pub enum CempError {
    #[error("CEMP crypto error: {0}")]
    Crypto(String),
    #[error("CEMP encoding error: {0}")]
    Encoding(String),
}
```

- [ ] **Step 3: Write the failing test + implement keygen**

`rust-core/src/cemp/keys.rs`:

```rust
use crate::cemp::CempError;
use fips203::ml_kem_768;
use fips203::traits::{KeyGen, SerDes};
use hkdf::Hkdf;
use sha2::Sha256;

#[derive(Clone, uniffi::Record)]
pub struct KemKeyPair {
    pub public_key_hex: String,
    pub secret_key_hex: String,
}

/// Deterministically derive an ML-KEM-768 keypair from the wallet seed via
/// HKDF-SHA256 (so it restores from the mnemonic). Independent of the ML-DSA key.
#[uniffi::export]
pub fn cemp_mlkem_from_seed(seed_hex: String) -> Result<KemKeyPair, CempError> {
    let seed = hex::decode(seed_hex.trim_start_matches("0x"))
        .map_err(|e| CempError::Encoding(e.to_string()))?;
    let hk = Hkdf::<Sha256>::new(None, &seed);
    // ML-KEM-768 keygen_from_seed wants the (d,z) seed; fips203 exposes a 64-byte form.
    let mut d = [0u8; 32];
    let mut z = [0u8; 32];
    hk.expand(b"CEMP-PQ-KEM-D", &mut d).map_err(|e| CempError::Crypto(e.to_string()))?;
    hk.expand(b"CEMP-PQ-KEM-Z", &mut z).map_err(|e| CempError::Crypto(e.to_string()))?;
    let (ek, dk) = ml_kem_768::KG::keygen_from_seed(d, z);
    Ok(KemKeyPair {
        public_key_hex: hex::encode(ek.into_bytes()),
        secret_key_hex: hex::encode(dk.into_bytes()),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn deterministic_from_seed() {
        let s = "11".repeat(32);
        let a = cemp_mlkem_from_seed(s.clone()).unwrap();
        let b = cemp_mlkem_from_seed(s).unwrap();
        assert_eq!(a.public_key_hex, b.public_key_hex);
        // ML-KEM-768 encapsulation key is 1184 bytes -> 2368 hex chars.
        assert_eq!(a.public_key_hex.len(), 1184 * 2);
    }
    #[test]
    fn distinct_seeds_distinct_keys() {
        let a = cemp_mlkem_from_seed("11".repeat(32)).unwrap();
        let b = cemp_mlkem_from_seed("22".repeat(32)).unwrap();
        assert_ne!(a.public_key_hex, b.public_key_hex);
    }
}
```

> `fips203` API confirmation: verify the exact `keygen_from_seed` signature and the `KeyGen`/`SerDes` trait paths against the installed `fips203` 0.4 (`cargo doc -p fips203 --no-deps`). If `keygen_from_seed` takes a single 64-byte array rather than `(d, z)`, concatenate `d‖z`. Adjust the call to the real API; keep the function signature + test assertions.

- [ ] **Step 4: Register + run**

Add `pub mod cemp;` to `lib.rs` (after `pub mod agent;`) and re-export `cemp_mlkem_from_seed, KemKeyPair, CempError` (add the other CEMP symbols in their tasks). Create empty stubs `crypto.rs`/`molecule.rs`/`typeid.rs` with a doc-comment so `mod.rs` compiles, and comment out re-exports of not-yet-defined symbols for this task (same staging trick as Plan A Task 1).

Run: `cd ~/wyltek-wallet/rust-core && cargo test cemp::keys 2>&1 | tail -15`
Expected: `deterministic_from_seed`, `distinct_seeds_distinct_keys` PASS.

- [ ] **Step 5: Commit**

```bash
cd ~/wyltek-wallet
git add rust-core/Cargo.toml rust-core/src/cemp rust-core/src/lib.rs
git commit -m "feat(cemp): ML-KEM-768 key derivation from wallet seed"
```

---

### Task 2: Encrypt / decrypt (ML-KEM + AES-256-GCM + blake2b KDF)

**Files:**
- Modify: `rust-core/src/cemp/crypto.rs`

**Interfaces:**
- Consumes: `CempError`, `KemKeyPair` (Task 1).
- Produces:
  - `#[uniffi::export] fn cemp_encrypt(plaintext_hex: String, recipient_kem_pub_hex: String) -> Result<String, CempError>` — returns the `EncryptedMessage` molecule as hex.
  - `#[uniffi::export] fn cemp_decrypt(encrypted_msg_hex: String, recipient_kem_sec_hex: String) -> Result<String, CempError>` — returns plaintext hex.

- [ ] **Step 1: Write the failing test + implement**

`rust-core/src/cemp/crypto.rs`:

```rust
use crate::cemp::molecule::{serialize_encrypted_message, parse_encrypted_message};
use crate::cemp::CempError;
use aes_gcm::aead::{Aead, KeyInit, OsRng};
use aes_gcm::{Aes256Gcm, Nonce};
use aes_gcm::aead::rand_core::RngCore;
use blake2b_ref::Blake2bBuilder;
use fips203::ml_kem_768;
use fips203::traits::{Decaps, Encaps, SerDes};

fn sym_key(shared: &[u8]) -> [u8; 32] {
    let mut h = Blake2bBuilder::new(32).personal(b"CEMP-PQ-SYM-KEY_").build();
    h.update(shared);
    let mut out = [0u8; 32];
    h.finalize(&mut out);
    out
}

#[uniffi::export]
pub fn cemp_encrypt(plaintext_hex: String, recipient_kem_pub_hex: String) -> Result<String, CempError> {
    let plaintext = hex::decode(plaintext_hex.trim_start_matches("0x")).map_err(enc)?;
    let pub_bytes = hex::decode(recipient_kem_pub_hex.trim_start_matches("0x")).map_err(enc)?;
    let ek = ml_kem_768::EncapsKey::try_from_bytes(
        pub_bytes.try_into().map_err(|_| CempError::Encoding("kem pubkey wrong length".into()))?
    ).map_err(|e| CempError::Crypto(format!("{e:?}")))?;
    let (shared, kem_ct) = ek.try_encaps().map_err(|e| CempError::Crypto(format!("{e:?}")))?;

    let key = sym_key(shared.into_bytes().as_slice());
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|e| CempError::Crypto(e.to_string()))?;
    let mut nonce_bytes = [0u8; 12];
    OsRng.fill_bytes(&mut nonce_bytes);
    let ct = cipher
        .encrypt(Nonce::from_slice(&nonce_bytes), plaintext.as_ref())
        .map_err(|e| CempError::Crypto(e.to_string()))?;

    let molecule = serialize_encrypted_message(&kem_ct.into_bytes(), &nonce_bytes, &ct);
    Ok(hex::encode(molecule))
}

#[uniffi::export]
pub fn cemp_decrypt(encrypted_msg_hex: String, recipient_kem_sec_hex: String) -> Result<String, CempError> {
    let blob = hex::decode(encrypted_msg_hex.trim_start_matches("0x")).map_err(enc)?;
    let (kem_ct, nonce_bytes, ct) = parse_encrypted_message(&blob)?;
    let sec = hex::decode(recipient_kem_sec_hex.trim_start_matches("0x")).map_err(enc)?;
    let dk = ml_kem_768::DecapsKey::try_from_bytes(
        sec.try_into().map_err(|_| CempError::Encoding("kem seckey wrong length".into()))?
    ).map_err(|e| CempError::Crypto(format!("{e:?}")))?;
    let kem_ct_arr = ml_kem_768::CipherText::try_from_bytes(
        kem_ct.try_into().map_err(|_| CempError::Encoding("kem ct wrong length".into()))?
    ).map_err(|e| CempError::Crypto(format!("{e:?}")))?;
    let shared = dk.try_decaps(&kem_ct_arr).map_err(|e| CempError::Crypto(format!("{e:?}")))?;

    let key = sym_key(shared.into_bytes().as_slice());
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|e| CempError::Crypto(e.to_string()))?;
    let pt = cipher
        .decrypt(Nonce::from_slice(&nonce_bytes), ct.as_ref())
        .map_err(|e| CempError::Crypto(format!("decrypt failed: {e}")))?;
    Ok(hex::encode(pt))
}

fn enc<E: std::fmt::Display>(e: E) -> CempError { CempError::Encoding(e.to_string()) }

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cemp::keys::cemp_mlkem_from_seed;

    #[test]
    fn encrypt_then_decrypt_roundtrip() {
        let kp = cemp_mlkem_from_seed("33".repeat(32)).unwrap();
        let msg = hex::encode(b"hello agent");
        let enc_hex = cemp_encrypt(msg.clone(), kp.public_key_hex).unwrap();
        let dec_hex = cemp_decrypt(enc_hex, kp.secret_key_hex).unwrap();
        assert_eq!(dec_hex, msg);
        assert_eq!(String::from_utf8(hex::decode(dec_hex).unwrap()).unwrap(), "hello agent");
    }

    #[test]
    fn wrong_key_fails() {
        let a = cemp_mlkem_from_seed("33".repeat(32)).unwrap();
        let b = cemp_mlkem_from_seed("44".repeat(32)).unwrap();
        let enc_hex = cemp_encrypt(hex::encode(b"secret"), a.public_key_hex).unwrap();
        assert!(cemp_decrypt(enc_hex, b.secret_key_hex).is_err());
    }
}
```

> Confirm the `fips203` 0.4 trait method names (`try_encaps`/`try_decaps`/`try_from_bytes`/`into_bytes`) and the `aes-gcm` 0.10 API against `cargo doc`. The shared-secret type's `.into_bytes()`/`.as_slice()` may differ — adapt to the real API; keep behaviour + test assertions. (Implements `molecule.rs` helpers from Task 3 — land Tasks 2+3 together, or stub the two molecule fns first.)

- [ ] **Step 2: Run + commit**

Run: `cargo test cemp:: 2>&1 | tail -15` (after Task 3's molecule helpers exist) → roundtrip + wrong-key tests PASS.

```bash
git add rust-core/src/cemp/crypto.rs && git commit -m "feat(cemp): ML-KEM + AES-256-GCM encrypt/decrypt"
```

---

### Task 3: Molecule serializers (Profile, EncryptedMessage, MessagePointer)

**Files:**
- Modify: `rust-core/src/cemp/molecule.rs`

**Interfaces:**
- Consumes: `CempError`; the existing `crate::molecule` helpers (`serialize_table`, `write_bytes`, `write_u32`) — if these are private, add `pub(crate)` to them in `molecule.rs`.
- Produces (internal `pub(crate)`): `serialize_encrypted_message(kem_ct, nonce, ct) -> Vec<u8>`, `parse_encrypted_message(&[u8]) -> Result<(Vec<u8>,[u8;12],Vec<u8>), CempError>`. Produces (UniFFI): `serialize_profile(dsa_pub_hex, kem_pub_hex, metadata_utf8) -> Result<String, CempError>`, `parse_profile_kem(profile_data_hex) -> Result<String, CempError>`, `parse_profile_dsa(profile_data_hex) -> Result<String, CempError>`, `serialize_message_pointer(tx_hash_hex, index) -> Result<String, CempError>`, `parse_message_pointer(hex) -> Result<MessagePointerOut, CempError>` where `struct MessagePointerOut { tx_hash_hex: String, index: u32 }`.

- [ ] **Step 1: Expose the table helpers**

In `rust-core/src/molecule.rs`, change `fn serialize_table`, `fn write_bytes`, `fn write_u32` to `pub(crate) fn ...` (verify exact names; the explore confirmed `serialize_table(fields: &[Vec<u8>])`, `write_bytes(buf, data)`, `write_u32(buf, val)`). Add a small reader: `pub(crate) fn read_table_fields(buf: &[u8]) -> Option<Vec<&[u8]>>` that parses a molecule table header (full_size + offsets) and returns each field's byte slice — needed for parsing. (If a table reader already exists, reuse it.)

- [ ] **Step 2: Implement + test the serializers**

`rust-core/src/cemp/molecule.rs`:

```rust
use crate::cemp::CempError;
use crate::molecule::{read_table_fields, serialize_table, write_bytes};

fn bytes_field(data: &[u8]) -> Vec<u8> { let mut v = Vec::new(); write_bytes(&mut v, data); v }
fn read_bytes_field(field: &[u8]) -> Option<Vec<u8>> {
    if field.len() < 4 { return None; }
    let len = u32::from_le_bytes(field[0..4].try_into().ok()?) as usize;
    field.get(4..4 + len).map(|s| s.to_vec())
}
fn enc<E: std::fmt::Display>(e: E) -> CempError { CempError::Encoding(e.to_string()) }

// ---- EncryptedMessage (internal; used by crypto.rs) ----
pub(crate) fn serialize_encrypted_message(kem_ct: &[u8], nonce: &[u8], ct: &[u8]) -> Vec<u8> {
    serialize_table(&[bytes_field(kem_ct), bytes_field(nonce), bytes_field(ct)])
}
pub(crate) fn parse_encrypted_message(blob: &[u8]) -> Result<(Vec<u8>, [u8; 12], Vec<u8>), CempError> {
    let f = read_table_fields(blob).ok_or_else(|| CempError::Encoding("bad EncryptedMessage table".into()))?;
    if f.len() != 3 { return Err(CempError::Encoding("EncryptedMessage needs 3 fields".into())); }
    let kem = read_bytes_field(f[0]).ok_or_else(|| enc("kem"))?;
    let nonce_v = read_bytes_field(f[1]).ok_or_else(|| enc("nonce"))?;
    let ct = read_bytes_field(f[2]).ok_or_else(|| enc("ct"))?;
    let nonce: [u8; 12] = nonce_v.try_into().map_err(|_| CempError::Encoding("nonce must be 12 bytes".into()))?;
    Ok((kem, nonce, ct))
}

// ---- Profile (UniFFI) ----
#[uniffi::export]
pub fn serialize_profile(dsa_pub_hex: String, kem_pub_hex: String, metadata_utf8: String) -> Result<String, CempError> {
    let dsa = hex::decode(dsa_pub_hex.trim_start_matches("0x")).map_err(enc)?;
    let kem = hex::decode(kem_pub_hex.trim_start_matches("0x")).map_err(enc)?;
    let table = serialize_table(&[bytes_field(&dsa), bytes_field(&kem), bytes_field(metadata_utf8.as_bytes())]);
    Ok(hex::encode(table))
}
#[uniffi::export]
pub fn parse_profile_kem(profile_data_hex: String) -> Result<String, CempError> {
    field_hex(&profile_data_hex, 1)
}
#[uniffi::export]
pub fn parse_profile_dsa(profile_data_hex: String) -> Result<String, CempError> {
    field_hex(&profile_data_hex, 0)
}
fn field_hex(profile_data_hex: &str, idx: usize) -> Result<String, CempError> {
    let blob = hex::decode(profile_data_hex.trim_start_matches("0x")).map_err(enc)?;
    let f = read_table_fields(&blob).ok_or_else(|| CempError::Encoding("bad Profile table".into()))?;
    let field = f.get(idx).ok_or_else(|| CempError::Encoding("profile field missing".into()))?;
    let v = read_bytes_field(field).ok_or_else(|| enc("profile field"))?;
    Ok(hex::encode(v))
}

// ---- MessagePointer (UniFFI; improvement over prior art) ----
#[derive(Clone, uniffi::Record)]
pub struct MessagePointerOut { pub tx_hash_hex: String, pub index: u32 }

#[uniffi::export]
pub fn serialize_message_pointer(tx_hash_hex: String, index: u32) -> Result<String, CempError> {
    let tx = hex::decode(tx_hash_hex.trim_start_matches("0x")).map_err(enc)?;
    if tx.len() != 32 { return Err(CempError::Encoding("tx_hash must be 32 bytes".into())); }
    let table = serialize_table(&[bytes_field(&tx), index.to_le_bytes().to_vec()]);
    Ok(hex::encode(table))
}
#[uniffi::export]
pub fn parse_message_pointer(hex_str: String) -> Result<MessagePointerOut, CempError> {
    let blob = hex::decode(hex_str.trim_start_matches("0x")).map_err(enc)?;
    let f = read_table_fields(&blob).ok_or_else(|| CempError::Encoding("bad MessagePointer".into()))?;
    if f.len() != 2 { return Err(CempError::Encoding("MessagePointer needs 2 fields".into())); }
    let tx = read_bytes_field(f[0]).ok_or_else(|| enc("tx_hash"))?;
    let idx_bytes: [u8; 4] = f[1].get(0..4).ok_or_else(|| enc("index"))?.try_into().map_err(|_| enc("index"))?;
    Ok(MessagePointerOut { tx_hash_hex: hex::encode(tx), index: u32::from_le_bytes(idx_bytes) })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn profile_roundtrip() {
        let dsa = hex::encode([0xAAu8; 1952]);
        let kem = hex::encode([0xBBu8; 1184]);
        let p = serialize_profile(dsa.clone(), kem.clone(), "Phill".into()).unwrap();
        assert_eq!(parse_profile_dsa(p.clone()).unwrap(), dsa);
        assert_eq!(parse_profile_kem(p).unwrap(), kem);
    }
    #[test]
    fn pointer_roundtrip() {
        let tx = hex::encode([0x11u8; 32]);
        let mp = serialize_message_pointer(tx.clone(), 7).unwrap();
        let out = parse_message_pointer(mp).unwrap();
        assert_eq!(out.tx_hash_hex, tx);
        assert_eq!(out.index, 7);
    }
    #[test]
    fn encrypted_message_roundtrip() {
        let blob = serialize_encrypted_message(&[1,2,3], &[9u8;12], &[4,5,6,7]);
        let (k, n, c) = parse_encrypted_message(&blob).unwrap();
        assert_eq!(k, vec![1,2,3]); assert_eq!(n, [9u8;12]); assert_eq!(c, vec![4,5,6,7]);
    }
}
```

- [ ] **Step 3: Run + commit**

Run: `cargo test cemp:: 2>&1 | tail -20` → profile/pointer/encrypted-message roundtrips + Task 2's crypto roundtrip all PASS. Re-enable the `mod.rs`/`lib.rs` re-exports for the now-defined symbols.

```bash
git add rust-core/src/cemp/molecule.rs rust-core/src/molecule.rs rust-core/src/cemp/mod.rs rust-core/src/lib.rs
git commit -m "feat(cemp): Profile/EncryptedMessage/MessagePointer molecule serializers"
```

---

### Task 4: CKB Type ID computation

**Files:**
- Create: `rust-core/src/cemp/typeid.rs`

**Interfaces:**
- Consumes: the existing `crate::molecule::{CellInputSer, OutPointSer}`; `crate::hashing` blake2b (`ckb-default-hash`).
- Produces: `#[uniffi::export] fn compute_type_id(first_input_tx_hash_hex: String, first_input_index: u32, first_input_since: u64, output_index: u64) -> Result<String, CempError>`.

- [ ] **Step 1: Implement + test**

`rust-core/src/cemp/typeid.rs`:

```rust
use crate::cemp::CempError;
use crate::molecule::{CellInputSer, OutPointSer};
use blake2b_ref::Blake2bBuilder;

/// CKB Type ID = blake2b("ckb-default-hash", CellInput(first_input) ++ u64_le(output_index)).
#[uniffi::export]
pub fn compute_type_id(
    first_input_tx_hash_hex: String,
    first_input_index: u32,
    first_input_since: u64,
    output_index: u64,
) -> Result<String, CempError> {
    let txh = hex::decode(first_input_tx_hash_hex.trim_start_matches("0x"))
        .map_err(|e| CempError::Encoding(e.to_string()))?;
    let tx_hash: [u8; 32] = txh.try_into().map_err(|_| CempError::Encoding("tx_hash must be 32 bytes".into()))?;
    let cell_input = CellInputSer { since: first_input_since, previous_output: OutPointSer { tx_hash, index: first_input_index } };
    let mut data = cell_input.to_bytes();
    data.extend_from_slice(&output_index.to_le_bytes());
    let mut h = Blake2bBuilder::new(32).personal(b"ckb-default-hash").build();
    h.update(&data);
    let mut out = [0u8; 32];
    h.finalize(&mut out);
    Ok(format!("0x{}", hex::encode(out)))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn deterministic_and_index_sensitive() {
        let tx = hex::encode([0x11u8; 32]);
        let a = compute_type_id(tx.clone(), 0, 0, 0).unwrap();
        let b = compute_type_id(tx.clone(), 0, 0, 0).unwrap();
        let c = compute_type_id(tx, 0, 0, 1).unwrap();
        assert_eq!(a, b);
        assert_ne!(a, c);
        assert!(a.starts_with("0x") && a.len() == 66);
    }
}
```

> `CellInputSer`/`OutPointSer` field names confirmed by the explore (`since`, `previous_output`, `tx_hash`, `index`). If a known CKB Type-ID test vector is available (e.g. from `ckb-sdk`), add it as a second assertion to pin byte-exactness; otherwise the on-chain harness (Task 8) is the byte-exact check.

- [ ] **Step 2: Run + commit**

Run: `cargo test cemp::typeid 2>&1 | tail -10` → PASS. Add `compute_type_id` to the re-exports.

```bash
git add rust-core/src/cemp/typeid.rs rust-core/src/cemp/mod.rs rust-core/src/lib.rs
git commit -m "feat(cemp): CKB Type ID computation"
```

---

### Task 5: UniFFI export surface coverage + regenerate bindings & `.so`

**Files:** `rust-core/src/lib.rs` (re-exports), then regenerate both `wyltekwalletcore.kt` + both `.so` (as Plan B1 Task 1).

- [ ] **Step 1: Confirm all CEMP exports are re-exported in `lib.rs`**

`cemp_mlkem_from_seed`, `cemp_encrypt`, `cemp_decrypt`, `serialize_profile`, `parse_profile_kem`, `parse_profile_dsa`, `serialize_message_pointer`, `parse_message_pointer`, `compute_type_id`, records `KemKeyPair`/`MessagePointerOut`, error `CempError`.

Run: `cd ~/wyltek-wallet/rust-core && cargo test 2>&1 | tail -5` → all green.

- [ ] **Step 2: Regenerate bindings + `.so` (operator step if no NDK)**

Same commands as Plan B1 Task 1 Step 5 (regenerate `wyltekwalletcore.kt` into both checked-in locations; rebuild arm64 `.so` into both `jniLibs`).

- [ ] **Step 3: Confirm Kotlin sees the CEMP symbols**

Run: `grep -nE "fun (cempMlkemFromSeed|cempEncrypt|cempDecrypt|serializeProfile|parseProfileKem|computeTypeId|serializeMessagePointer)|class (KemKeyPair|MessagePointerOut)|CempException" ~/wyltek-wallet/app/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt | head`
Expected: matches.

- [ ] **Step 4: Commit**

```bash
cd ~/wyltek-wallet
git add rust-core/src/lib.rs wallet-core/src/main/java app/src/main/java wallet-core/src/main/jniLibs app/src/main/jniLibs
git commit -m "feat(cemp): expose CEMP UniFFI surface; regen bindings + .so"
```

---

### Task 6: Kotlin — create profile cell

**Files:**
- Modify: `wallet-core/.../chain/NetworkConfig.kt` (add `typeIdCodeHash` constant)
- Modify: `app/.../data/WalletRepository.kt` (add `createProfileCell`)

**Interfaces:**
- Consumes: `mldsa65FromSeed`, `cempMlkemFromSeed`, `serializeProfile`, `computeTypeId`, `buildTransaction`, `signCkbMldsa65`, `MldsaInputCell`, `resolveSigningContext`, `chainManager` cell fetch, `SeedVault`.
- Produces: `suspend fun createProfileCell(account: WalletAccount, metadata: String): WalletResult<String>` (returns tx hash). The profile cell is created on the account's **PQ** address (ML-DSA lock).

- [ ] **Step 1: Add the Type-ID constant**

In `NetworkConfig.kt`, add to the shared constants (network-independent — Type ID is a system script): `const val TYPE_ID_CODE_HASH = "0x00000000000000000000000000000000000000000000000000545950455f4944"` and `const val TYPE_ID_HASH_TYPE = "type"`.

- [ ] **Step 2: Implement `createProfileCell`**

Mirror the existing mint/`sendCkb` pattern in `WalletRepository` (fetch PQ-lock cells via `chainManager`, build a `TransactionRequest`, compute Type ID from `inputs[0]`, `buildTransaction`, `signCkbMldsa65`, assemble the JSON envelope used by `sendCkb`, broadcast). Key specifics:

- Resolve the PQ `CkbAddress` of `account` (the one whose `lockScript.codeHash == network.mldsa65.codeHash`). If none, `WalletResult.Error("no PQ account")`.
- `seed = seedVault.loadSeed(account.id)`; `dsa = mldsa65FromSeed(seed)`; `kem = cempMlkemFromSeed(seed)`.
- `profileData = serializeProfile(dsa.publicKeyHex, kem.publicKeyHex, metadata)`.
- Output[0]: lock = PQ lock; **type = Type ID** (codeHash `TYPE_ID_CODE_HASH`, hashType `type`, args = placeholder 32 zero-bytes); data = `profileData`; capacity sized to fit (let the existing fee/capacity completion handle it, as `sendCkb` does).
- Select inputs (PQ-lock cells) to cover capacity + fee (reuse the helper `sendCkb` uses).
- `typeIdArgs = computeTypeId(firstInput.txHash, firstInput.index, firstInput.since, 0)`; set output[0].type.args = `typeIdArgs`.
- cellDeps = PQ ML-DSA dep (`network.mldsa65.cellDep*`). (Type ID is a system script — no cell dep needed.)
- Build → `MldsaInputCell` list (same order as inputs, type-less) → `signCkbMldsa65(built.txHashHex, inputs, dsa.privateKeyHex, dsa.publicKeyHex)` → witnesses[0]=sig, rest "0x" → broadcast.

> Follow the EXACT input-selection, witness-array, and JSON-envelope code already in `sendCkb`/the mint path — do not invent a new broadcast path. The only new pieces are the Type-ID output and the profile data.

- [ ] **Step 3: Verify**

Run: `./gradlew :app:compileDebugKotlin` → green. (On-chain creation is exercised in Task 8 harness / operator run.)

- [ ] **Step 4: Commit**

```bash
git add wallet-core/src/main/java/com/wyltek/wallet/core/chain/NetworkConfig.kt app/src/main/java/com/wyltek/wallet/data/WalletRepository.kt
git commit -m "feat(cemp): WalletRepository.createProfileCell (Type ID profile on PQ lock)"
```

---

### Task 7: Kotlin — discover recipient + send encrypted message

**Files:**
- Modify: `app/.../data/WalletRepository.kt` (`discoverProfileKem`, `sendCempMessage`)

**Interfaces:**
- Consumes: `decodeAddress`, `chainManager.getCellsByLock`/`findCells` (the cell-scan the repo already uses), `parseProfileKem`, `cempEncrypt`, `serializeMessagePointer`, `buildTransaction`, `signCkbMldsa65`.
- Produces:
  - `suspend fun discoverProfileKem(recipientAddress: String): WalletResult<String>` — returns the recipient's ML-KEM pubkey hex (scan recipient lock cells for the Type-ID cell, `parseProfileKem`).
  - `suspend fun sendCempMessage(account: WalletAccount, recipientAddress: String, plaintextHex: String): WalletResult<String>` — returns the message tx hash.

- [ ] **Step 1: Implement `discoverProfileKem`**

Decode `recipientAddress` → recipient `LockScript`. Scan live cells by that lock (the same `chainManager` query `scanDaoDeposits`/asset scanners use). For the first cell whose `type.codeHash == TYPE_ID_CODE_HASH`, read its `outputData` hex and return `parseProfileKem(outputData)`. If none, `WalletResult.Error("recipient has no CEMP profile")`.

- [ ] **Step 2: Implement `sendCempMessage`**

1. `kemPub = discoverProfileKem(recipientAddress)` (bail on Error).
2. `encrypted = cempEncrypt(plaintextHex, kemPub)`.
3. Resolve sender PQ lock + recipient lock (`decodeAddress`).
4. Build a tx with two outputs: output[0] = Message Cell (sender PQ lock, no type, data = `encrypted`); output[1] = Notification Cell (recipient lock, no type, data = **MessagePointer** — but the pointer needs this tx's own hash, which isn't known pre-build). **Resolution (improvement, self-consistent):** the notification's MessagePointer references the message cell by `(this_tx_hash, 0)`. Since the tx hash isn't known until built, set output[1] data to `serializeMessagePointer("0x"+"00"*32, 0)` as a placeholder of the SAME length, build to learn the hash is not needed — instead use the **OutPoint convention**: leave the pointer's `tx_hash` as 32 zero-bytes meaning "this transaction" and set `index = 0`; the recipient, having found the notification cell, reads `index` and uses the notification cell's OWN `OutPoint.txHash` (the containing tx) to locate the message cell at that index. This is the single-tx convention from the project's `ckb-transactions` rule (self-referential tx_hash is impossible; recipients use the cell's own OutPoint.txHash). Document this in the code.
5. cellDeps = PQ ML-DSA dep. Select sender PQ-lock inputs for capacity+fee, build, sign (`signCkbMldsa65`), broadcast — reusing the `sendCkb` envelope.

> The capacity for the notification cell must fit the MessagePointer data (~48 bytes) — size it like any data cell (the existing fee/capacity completion handles it; ensure the placeholder data length equals the final length, per the `ckb-transactions` rule about late data mutation under `completeFeeBy`).

- [ ] **Step 3: Verify + commit**

Run: `./gradlew :app:compileDebugKotlin` → green.

```bash
git add app/src/main/java/com/wyltek/wallet/data/WalletRepository.kt
git commit -m "feat(cemp): discoverProfileKem + sendCempMessage on-chain"
```

---

### Task 8: Wire MessagingService + CempMessagingSender + harness

**Files:**
- Modify: `wallet-core/.../messaging/Messaging.kt`
- Create: `app/.../agent/CempMessagingSender.kt`
- Modify: `app/.../data/WalletViewModel.kt` (pass the real send through; signature unchanged)
- Modify: `app/.../agent/AgentGateway.kt` (swap `UnsupportedMessagingSender` → `CempMessagingSender`)
- Modify: `rust-core/examples/pq_testnet.rs` (`cemp-profile`, `cemp-send` harness commands)
- Create: `app/src/androidTest/.../agent/CempMessagingSenderTest.kt`

**Interfaces:**
- Produces: `class CempMessagingSender(repository: WalletRepository) : MessagingSender` implementing `send(account, to, amount, action)` → `repository.sendCempMessage(account, to, plaintextHexFromAction)`.

- [ ] **Step 1: Replace `MessagingService` stubs**

In `Messaging.kt`, route `sendMessage(from, to, content)` through `WalletRepository.sendCempMessage` (the service gains a `WalletRepository` collaborator), `decryptMessage` through `cempDecrypt` (with the account's `cempMlkemFromSeed(seed).secretKeyHex`), `createProfileCell` through `WalletRepository.createProfileCell`, `discoverProfile` through `WalletRepository.discoverProfileKem` (populating `ContactProfile.publicKey`). Keep the model types and the `sendMessage(from,to,content:ByteArray)` signature so `WalletViewModel`/`MessagesScreen` are unchanged.

- [ ] **Step 2: Implement `CempMessagingSender`**

`app/src/main/java/com/wyltek/wallet/agent/CempMessagingSender.kt`:

```kotlin
package com.wyltek.wallet.agent

import com.wyltek.wallet.core.model.WalletAccount
import com.wyltek.wallet.data.WalletRepository
import com.wyltek.wallet.data.WalletResult

/** Fulfills Plan B1's MessagingSender by sending an on-chain CEMP-PQ message. */
class CempMessagingSender(private val repository: WalletRepository) : MessagingSender {
    override suspend fun send(account: WalletAccount, to: String, amount: Long, action: String?): WalletResult<String> {
        // `action` carries the message plaintext as hex (the agent intent's payload); amount is ignored.
        val plaintextHex = action ?: return WalletResult.Error("messaging intent missing payload (action)")
        return repository.sendCempMessage(account, to, plaintextHex)
    }
}
```

In `AgentGateway.kt`, replace `messaging = UnsupportedMessagingSender()` with `messaging = CempMessagingSender(repository)`.

> Intent shape note: the agent supplies the message plaintext via `Intent.action` (hex). This reuses the field added in Plan B1 Task 1 — no further Rust change. Confirm this convention is documented where tokens with `messaging` scope are minted.

- [ ] **Step 3: Harness commands**

In `examples/pq_testnet.rs` add `cemp-profile <seed> <metadata>` (build+broadcast a profile cell via the same Rust pieces the app uses) and `cemp-send <seed> <recipient_addr> <message>` (discover → encrypt → 2-output tx → broadcast). Reuse `spend_pq`'s build/sign/broadcast helpers; the new bits are `serialize_profile`/`compute_type_id`/`cemp_encrypt`/`serialize_message_pointer`.

- [ ] **Step 4: Test**

`CempMessagingSenderTest` (instrumented): with a fake `WalletRepository` send (or the function-injection pattern from Plan B1's dispatcher test), assert `send` forwards `action` as plaintext and returns the repo result; assert missing-`action` → Error.

Run: `./gradlew :app:compileDebugKotlin`; if device, the instrumented test. On-chain (operator, funded testnet seed):
```bash
cd ~/wyltek-wallet/rust-core
cargo run --example pq_testnet -- cemp-profile <seed> "Phill agent"          # Phase 0
cargo run --example pq_testnet -- cemp-send <seed> <recipient_with_profile> "hello from the agent"  # Phase 1
```
Expected: profile tx + message tx pool-accepted; decrypt round-trips with the recipient's seed-derived KEM key.

- [ ] **Step 5: Commit**

```bash
cd ~/wyltek-wallet
git add wallet-core/src/main/java/com/wyltek/wallet/core/messaging/Messaging.kt app/src/main/java/com/wyltek/wallet/agent/CempMessagingSender.kt app/src/main/java/com/wyltek/wallet/agent/AgentGateway.kt app/src/main/java/com/wyltek/wallet/data/WalletViewModel.kt rust-core/examples/pq_testnet.rs app/src/androidTest
git commit -m "feat(cemp): wire on-chain messaging + CempMessagingSender + harness"
```

---

## Self-Review

**1. Spec/goal coverage:** Profile creation (Task 6), on-chain encrypted send (Task 7), recipient discovery (Task 7), the `MessagingSender` fulfillment for the agent scope (Task 8), and the crypto/molecule/Type-ID port (Tasks 1–4). The notification cell now carries a `MessagePointer` (Task 3 + Task 7) — closing the prior-art stub.

**2. Placeholder scan:** No "TBD"/"add error handling". Library-API confirmations (`fips203` 0.4 keygen/encaps signatures, `aes-gcm` 0.10) and the molecule-helper visibility change are named, compile-gated steps — not filler. The Kotlin Tasks 6/7 deliberately say "mirror the existing `sendCkb`/mint path" rather than re-printing the ~150-line broadcast envelope; the new code (Type-ID output, profile data, 2-output message tx, MessagePointer) is fully specified, and the reused envelope is an existing, verified pattern the implementer must follow, not re-derive.

**3. Type consistency:** Rust `cemp_*` functions + `KemKeyPair`/`MessagePointerOut`/`CempError` defined once; consumed by crypto.rs and the Kotlin repository methods. `serialize_encrypted_message`/`parse_encrypted_message` are the `pub(crate)` pair shared by crypto.rs (Task 2) and molecule.rs (Task 3). `MessagingSender.send(account,to,amount,action)` matches Plan B1's interface exactly; `CempMessagingSender` reads the plaintext from `action`.

**Open items (flagged):**
- **`fips203`/`aes-gcm` API drift** — confirm exact trait/method names against installed versions (Tasks 1–2 compile gates catch it).
- **Type-ID byte-exactness** — verified on-chain by the Task 8 harness; add a known test vector if one is available.
- **Self-referential tx_hash in the notification MessagePointer** — resolved via the project's single-tx convention (recipient uses the notification cell's own `OutPoint.txHash`; pointer stores `index` only, `tx_hash` = zeros). This must be honored on the read/inbox side (a follow-up, since full inbox scanning is beyond B-CEMP's send-focused scope).
- **Receive/inbox path** — B-CEMP delivers send + the decrypt primitive + profile discovery; a complete "scan my incoming notifications, follow pointers, decrypt" inbox is a follow-up (the agent `messaging` scope only needs send).
- **Carry-forward from Plan A review** still pending across the program: biscuit attenuation, decide-time revocation-list enforcement, multi-asset cap regression test.
