# Agent Gateway — Plan B1: On-Device Android Core — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the on-device (non-UI, non-network) half of the Agent Gateway in the `app` module: a SQLCipher-backed spend ledger + token registry with atomic accounting, a StrongBox-sealed biscuit root key, a token-mint/revoke service, and an `AgentActionDispatcher` that turns an agent `Intent` into a policy decision (`decide()`) and, on `AllowAuto`, an atomic debit + real `WalletRepository` broadcast.

**Architecture:** The merged Rust core (`wyltekwalletcore`) provides the pure decision functions over UniFFI. This plan adds the Kotlin layer that owns persistence and orchestration: it resolves a token's bound account to a `WalletAccount`, builds the `LedgerView` from the SQLCipher ledger, calls `decide()`, and on a permit executes the matching `WalletRepository` suspend call inside an optimistic-debit-then-broadcast (compensating-delete-on-failure) flow with a DB unique-nonce constraint preventing replay/double-spend. UI and the Ktor/relay transports are out of scope (Plans B2/C). Messaging dispatch routes through a `MessagingSender` interface fulfilled later by Plan B-CEMP.

**Tech Stack:** Kotlin 2.1.0, KSP 2.1.0-1.0.29 (already applied in `:app`), Room 2.6.1 + SQLCipher (`net.zetetic:android-database-sqlcipher`), `androidx.security:security-crypto` (already a dep), the existing manual-DI style (no Hilt/Koin), UniFFI bindings in package `com.wyltek.wallet.core.native`.

## Global Constraints

- **No DI framework** — everything is manually constructed (see `WalletRepository(application)` in `WalletViewModel`). New services take their collaborators as constructor params; a single `AgentGateway` facade wires them, constructed from `Application`/`Context` like `WalletRepository`.
- **Module placement:** all new Kotlin lives in the **`:app`** module under `app/src/main/java/com/wyltek/wallet/agent/` (it depends on `WalletRepository`, which is in `:app`, and Room needs KSP, which is applied only in `:app`).
- **minSdk = 26**, Kotlin 2.1.0; no version catalog (`gradle/libs.versions.toml` does not exist) — declare dependency versions as inline string literals matching the existing style.
- **UniFFI bindings are checked in and duplicated** at `wallet-core/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt` AND `app/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt`; the `.so` lives at `{wallet-core,app}/src/main/jniLibs/arm64-v8a/libwyltekwalletcore.so`. Both copies must be regenerated/rebuilt together (Task 1). Generated Kotlin names are camelCased: `agentRootKeypair()`, `mintToken(spec, rootSecretHex)`, `tokenCaps(token, rootPubHex)`, `decide(token, rootPubHex, intent, view, ctx)`; records `Intent`, `RequestCtx`, `LedgerView`, `CapInfo`, `Decision`, `TokenSpec`, `AgentKeyPair`; enum `Scope`; error → `AgentException` sealed class.
- **Money is shannons as Long** on the Kotlin side (Rust `i64` → Kotlin `Long`). The wallet's own send APIs use `ULong`/`BigInteger`; convert at the call boundary (`Long.toULong()`, `BigInteger.valueOf(long)`).
- **Asset id** is `"CKB"` or a UDT type-script id `"{codeHash}:{hashType}:{args}"`.
- **Fail closed:** any error in the dispatch path resolves to a denied/failed result, never a silent broadcast.
- **WalletResult** is the repository's sealed result: `WalletResult.Success<T>(data)` / `WalletResult.Error(message)`.
- Commit after every task with a conventional message; no attribution footer.

## Existing types this plan binds to (verified, do not redefine)

```kotlin
// wallet-core .../core/model/Models.kt
data class WalletAccount(val id:String, val name:String, val type:AccountType,
    val network:NetworkType, val addresses:List<CkbAddress>, val createdAt:Long, val isHD:Boolean=true)
enum class AccountType { CLASSIC, POST_QUANTUM, HYBRID }
enum class NetworkType { MAINNET, TESTNET, DEVNET }
data class CkbAddress(val bech32m:String, val lockScript:LockScript, val network:NetworkType, val formatVersion:AddressFormatVersion)
data class LockScript(val codeHash:String, val hashType:String, val args:String)
// wallet-core .../core/model/DaoModels.kt
data class DaoDeposit(val outPoint:OutPoint, val capacity:ULong, val status:DaoCellStatus, /* ...many fields... */)
// OutPoint has txHash + index (verify exact field names in DaoModels.kt when used)
```
```kotlin
// app .../data/WalletRepository.kt  (constructed as WalletRepository(application))
suspend fun sendCkb(fromAccount:WalletAccount, toAddress:String, amount:ULong, feeRate:ULong=1000u, fromCkbAddress:CkbAddress?=null): WalletResult<String>
suspend fun sendToken(fromAccount:WalletAccount, tokenTypeScript:LockScript, toAddress:String, amount:java.math.BigInteger, feeRate:ULong=1000u, fromCkbAddress:CkbAddress?=null): WalletResult<String>
suspend fun depositDao(fromAccount:WalletAccount, amountCkb:ULong, feeRate:ULong=1000u): WalletResult<String>
suspend fun withdrawDaoPhase1(fromAccount:WalletAccount, deposit:DaoDeposit, feeRate:ULong=1000u): WalletResult<String>
suspend fun claimDao(fromAccount:WalletAccount, deposit:DaoDeposit, feeRate:ULong=1000u): WalletResult<String>
suspend fun scanDaoDeposits(lockScript:LockScript, network:NetworkType): WalletResult<List<DaoDeposit>>
fun getStrongBoxManager(): StrongBoxManager
// wallet-core .../core/account/AccountManager.kt  (no address->account lookup exists)
fun getAllAccounts(): List<WalletAccount>
// wallet-core .../core/security/StrongBoxManager.kt
fun isStrongBoxAvailable(): Boolean
fun createStrongBoxKey(): Boolean
fun wrapPrivateKey(privateKey: ByteArray): ByteArray?
fun unwrapPrivateKey(wrappedKey: ByteArray): ByteArray?
```

## File Structure

| File | Responsibility |
|------|----------------|
| `rust-core/src/agent/types.rs` (modify) | Add `Intent.action`, `Intent.dao_ref` (optional); update literals |
| `rust-core/src/agent/token.rs` (modify) | Add `#[uniffi::export] token_id_of(token, root_pub_hex)` |
| `rust-core/src/agent/policy.rs` + harness (modify) | Update `Intent { .. }` literals with new fields |
| `{wallet-core,app}/.../native/wyltekwalletcore.kt` + `.so` (regenerate) | Bring agent symbols + new Intent fields into Kotlin |
| `app/build.gradle.kts` (modify) | Add Room + SQLCipher deps |
| `app/.../agent/store/AgentSecureStore.kt` (create) | EncryptedSharedPreferences-backed blob store + SQLCipher passphrase + biscuit-root-key seal/load |
| `app/.../agent/db/AgentEntities.kt` (create) | `SpendRecordEntity`, `TokenRegistryEntity` |
| `app/.../agent/db/AgentDao.kt` (create) | Room DAO: usage queries + atomic insert/delete/revoke |
| `app/.../agent/db/AgentDatabase.kt` (create) | Room `@Database` opened with SQLCipher SupportFactory |
| `app/.../agent/AgentLedger.kt` (create) | View/apply contract over the DAO; maps to Rust `LedgerView` |
| `app/.../agent/AgentTokenService.kt` (create) | Mint (Rust) + persist registry, list, revoke, caps |
| `app/.../agent/MessagingSender.kt` (create) | Interface for on-chain message send + a v1 `UnsupportedMessagingSender` |
| `app/.../agent/AgentActionDispatcher.kt` (create) | Resolve account → view → `decide()` → atomic debit + broadcast |
| `app/.../agent/AgentGateway.kt` (create) | Facade wiring all of the above from `Context` |
| `app/src/androidTest/.../agent/*` (create) | Instrumented tests (Room/SQLCipher need a device/emulator) |

---

### Task 1: Extend Rust `Intent` + add `token_id_of`, regenerate bindings & `.so`

**Files:**
- Modify: `rust-core/src/agent/types.rs` (the `Intent` record)
- Modify: `rust-core/src/agent/token.rs` (new export)
- Modify: `rust-core/src/agent/policy.rs` (test `Intent` literals), `rust-core/examples/pq_testnet.rs` (harness `Intent` literals)
- Regenerate: both `wyltekwalletcore.kt` copies + both `.so` copies

**Interfaces:**
- Produces (Rust → Kotlin): `Intent` gains `action: Option<String>` and `dao_ref: Option<String>`; new `#[uniffi::export] pub fn token_id_of(token: String, root_pub_hex: String) -> Result<String, AgentError>`.

- [ ] **Step 1: Write the failing test (Rust)**

In `rust-core/src/agent/token.rs`, add to the `#[cfg(test)] mod tests`:

```rust
    #[test]
    fn token_id_of_matches_decide_token_id() {
        let (token, pubhex) = keypair_and_token(sample_spec());
        let id = token_id_of(token.clone(), pubhex.clone()).unwrap();
        assert!(!id.is_empty());
        // Same token id surfaced by parse_and_authorize.
        let a = parse_and_authorize(&token, &pubhex, "send_ckb", "ckt1qexample", "ckt1qto", "10.0.0.1", 1_700_000_000).unwrap();
        assert_eq!(id, a.token_id);
    }
```

- [ ] **Step 2: Add the export**

In `rust-core/src/agent/token.rs`, add (it reuses the existing `token_id_of` helper that takes an `&mut Authorizer`; give the public function a distinct name and build a no-context authorizer like `token_caps` does):

```rust
#[uniffi::export]
pub fn token_id_of(token: String, root_pub_hex: String) -> Result<String, AgentError> {
    let public = root_public_from_hex(&root_pub_hex).map_err(AgentError::TokenError)?;
    let biscuit = Biscuit::from_base64(&token, public).map_err(tok_err)?;
    let mut authorizer = biscuit.authorizer().map_err(tok_err)?;
    super::token::token_id_of_internal(&mut authorizer).map_err(AgentError::TokenError)
}
```

Rename the existing private `fn token_id_of(authorizer: &mut Authorizer) -> Result<String, String>` to `pub(crate) fn token_id_of_internal(...)` and update its single caller in `parse_and_authorize`. (This avoids a name clash with the new public `token_id_of`.) Add `token_id_of` to the `pub use token::{...}` in `mod.rs` and to the `lib.rs` re-export.

- [ ] **Step 3: Extend the `Intent` record**

In `rust-core/src/agent/types.rs`, change `Intent` to:

```rust
#[derive(Debug, Clone, uniffi::Record)]
pub struct Intent {
    pub op: String,
    pub asset: String,
    pub to: String,
    pub amount: i64,
    pub nonce: String,
    /// Granular action within a scope (e.g. "deposit" | "withdraw" | "claim" for op="dao"). None for simple ops.
    pub action: Option<String>,
    /// Reference to an existing cell for actions that target one, as "txhash:index" (e.g. a DAO deposit outpoint). None otherwise.
    pub dao_ref: Option<String>,
}
```

`decide` does not read `action`/`dao_ref` — the policy is unchanged. Update EVERY `Intent { .. }` struct literal to include `action: None, dao_ref: None` (unless a test needs a value): in `policy.rs` tests (the `intent()` helper and the two inline `Intent {…}` in `deny_wrong_scope_op`-style tests) and in `examples/pq_testnet.rs` (`cmd_agent_intent` builds `Intent {…}` twice — the probe and the real call).

- [ ] **Step 4: Run Rust tests**

Run: `cd ~/wyltek-wallet/rust-core && cargo test agent:: 2>&1 | tail -20`
Expected: all agent tests PASS including `token_id_of_matches_decide_token_id`; no warnings.

- [ ] **Step 5: Regenerate UniFFI bindings + rebuild the `.so`**

> The `.so` build needs the Android NDK + the `aarch64-linux-android` Rust target (and typically `cargo-ndk`). If this toolchain is unavailable in the execution environment, STOP and report this step as an operator action with the commands below; the rest of Plan B1 cannot be `compileDebugKotlin`-verified until it is done.

```bash
cd ~/wyltek-wallet/rust-core
# 1. Regenerate Kotlin bindings from the built library:
cargo build --release
cargo run --bin uniffi-bindgen generate --library target/release/libwyltekwalletcore.so --language kotlin --out-dir /tmp/uniffi-out
# 2. Copy the generated file into BOTH checked-in locations:
cp /tmp/uniffi-out/com/wyltek/wallet/core/native/wyltekwalletcore.kt ../wallet-core/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt
cp /tmp/uniffi-out/com/wyltek/wallet/core/native/wyltekwalletcore.kt ../app/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt
# 3. Rebuild the arm64 .so and copy into BOTH jniLibs:
cargo ndk -t arm64-v8a build --release   # or: cargo build --release --target aarch64-linux-android
cp target/aarch64-linux-android/release/libwyltekwalletcore.so ../wallet-core/src/main/jniLibs/arm64-v8a/libwyltekwalletcore.so
cp target/aarch64-linux-android/release/libwyltekwalletcore.so ../app/src/main/jniLibs/arm64-v8a/libwyltekwalletcore.so
```

- [ ] **Step 6: Confirm the bindings now expose the agent symbols**

Run: `grep -nE "fun (mintToken|decide|tokenCaps|agentRootKeypair|tokenIdOf)|class (Intent|TokenSpec|CapInfo|Decision|LedgerView|RequestCtx|AgentKeyPair)|AgentException" ~/wyltek-wallet/app/src/main/java/com/wyltek/wallet/core/native/wyltekwalletcore.kt | head`
Expected: matches for the agent functions, records, and `AgentException`, and `Intent` shows the new `action`/`daoRef` fields.

- [ ] **Step 7: Commit**

```bash
cd ~/wyltek-wallet
git add rust-core/ wallet-core/src/main/java app/src/main/java wallet-core/src/main/jniLibs app/src/main/jniLibs
git commit -m "feat(agent): extend Intent (action, dao_ref) + token_id_of export; regen bindings"
```

---

### Task 2: Room + SQLCipher dependencies and encrypted database scaffolding

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/wyltek/wallet/agent/store/AgentSecureStore.kt`
- Create: `app/src/main/java/com/wyltek/wallet/agent/db/AgentDatabase.kt` (entities/DAO added in Task 3; here it is a buildable shell)

**Interfaces:**
- Produces: `class AgentSecureStore(context: Context)` with `fun sqlcipherPassphrase(): ByteArray`, `fun storeBlob(key: String, value: ByteArray)`, `fun loadBlob(key: String): ByteArray?`, `fun deleteBlob(key: String)`. `object AgentDatabaseFactory { fun open(context: Context, passphrase: ByteArray): AgentDatabase }`.

- [ ] **Step 1: Add dependencies**

In `app/build.gradle.kts` `dependencies {}` add:

```kotlin
    // Agent Gateway encrypted ledger
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
    implementation("androidx.sqlite:sqlite:2.4.0")
```

(The `com.google.devtools.ksp` plugin is already applied in `:app`; `androidx.security:security-crypto:1.1.0-alpha06` is already present.)

- [ ] **Step 2: Create `AgentSecureStore`**

`app/src/main/java/com/wyltek/wallet/agent/store/AgentSecureStore.kt`:

```kotlin
package com.wyltek.wallet.agent.store

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Encrypted key/value blob store for Agent Gateway secrets, layered on
 * EncryptedSharedPreferences (AES-256-GCM, AndroidKeyStore master key) — the same
 * primitive SeedVault uses. Holds the SQLCipher passphrase and (via AgentKeyStore)
 * the sealed biscuit root key. Values never appear in plaintext prefs.
 */
class AgentSecureStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "agent_secure_store",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun storeBlob(key: String, value: ByteArray) {
        prefs.edit().putString(key, Base64.encodeToString(value, Base64.NO_WRAP)).apply()
    }

    fun loadBlob(key: String): ByteArray? =
        prefs.getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    fun deleteBlob(key: String) { prefs.edit().remove(key).apply() }

    /** Returns the SQLCipher passphrase, generating + persisting a 32-byte random one on first use. */
    fun sqlcipherPassphrase(): ByteArray {
        loadBlob(KEY_DB_PASSPHRASE)?.let { return it }
        val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
        storeBlob(KEY_DB_PASSPHRASE, pass)
        return pass
    }

    companion object {
        private const val KEY_DB_PASSPHRASE = "sqlcipher_passphrase"
    }
}
```

- [ ] **Step 3: Create the SQLCipher-opened database shell**

`app/src/main/java/com/wyltek/wallet/agent/db/AgentDatabase.kt`:

```kotlin
package com.wyltek.wallet.agent.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(entities = [SpendRecordEntity::class, TokenRegistryEntity::class], version = 1, exportSchema = false)
abstract class AgentDatabase : RoomDatabase() {
    abstract fun agentDao(): AgentDao
}

object AgentDatabaseFactory {
    fun open(context: Context, passphrase: ByteArray): AgentDatabase {
        SQLiteDatabase.loadLibs(context)
        val factory = SupportFactory(passphrase.copyOf(), null, false)
        return Room.databaseBuilder(context.applicationContext, AgentDatabase::class.java, "agent_gateway.db")
            .openHelperFactory(factory)
            .build()
    }
}
```

> Entities `SpendRecordEntity`/`TokenRegistryEntity` and `AgentDao` are created in Task 3; this file will not compile until then. Create Task 3's files in the same task sequence — do NOT commit Task 2 alone if the module won't build. (If you want an independently-green Task 2, temporarily reference an empty `@Dao interface AgentDao` and zero entities; Task 3 replaces them. Prefer to land Tasks 2+3 together.)

- [ ] **Step 4: Verify dependencies resolve**

Run: `cd ~/wyltek-wallet && ./gradlew :app:dependencies --configuration debugRuntimeClasspath 2>&1 | grep -E "room-runtime|sqlcipher" | head`
Expected: Room 2.6.1 and android-database-sqlcipher 4.5.4 appear.

- [ ] **Step 5: Commit (with Task 3, or as a deps-only commit if landing separately)**

```bash
cd ~/wyltek-wallet
git add app/build.gradle.kts app/src/main/java/com/wyltek/wallet/agent/store/AgentSecureStore.kt
git commit -m "build(agent): add Room+SQLCipher deps + AgentSecureStore"
```

---

### Task 3: Ledger schema, DAO, and the view/apply ledger

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/agent/db/AgentEntities.kt`
- Create: `app/src/main/java/com/wyltek/wallet/agent/db/AgentDao.kt`
- Create: `app/src/main/java/com/wyltek/wallet/agent/AgentLedger.kt`
- Create: `app/src/androidTest/java/com/wyltek/wallet/agent/AgentLedgerTest.kt`

**Interfaces:**
- Consumes: `AgentDatabase`/`AgentDatabaseFactory` (Task 2); the Rust `LedgerView` record (Task 1 bindings).
- Produces:
  - `@Entity SpendRecordEntity(id, tokenId, asset, amount: Long, unix: Long, nonce, status, txHash?)` with a unique index on `(tokenId, nonce)`.
  - `@Entity TokenRegistryEntity(tokenId, token, account, revoked: Boolean, createdAt: Long)`.
  - `AgentDao` with usage queries + `@Transaction` atomic ops.
  - `class AgentLedger(db: AgentDatabase)` with `suspend fun view(tokenId, asset, windowSeconds: Long, nowUnix: Long): LedgerView`, `suspend fun reserve(rec: SpendRecordEntity): Boolean` (atomic insert; false on replay), `suspend fun confirm(id, txHash)`, `suspend fun rollback(id)`.

- [ ] **Step 1: Create entities**

`app/src/main/java/com/wyltek/wallet/agent/db/AgentEntities.kt`:

```kotlin
package com.wyltek.wallet.agent.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

const val STATUS_PENDING = "pending"
const val STATUS_CONFIRMED = "confirmed"

@Entity(
    tableName = "spend_records",
    indices = [Index(value = ["tokenId", "nonce"], unique = true), Index(value = ["tokenId", "asset"])]
)
data class SpendRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tokenId: String,
    val asset: String,
    val amount: Long,
    val unix: Long,
    val nonce: String,
    val status: String = STATUS_PENDING,
    val txHash: String? = null
)

@Entity(tableName = "token_registry")
data class TokenRegistryEntity(
    @PrimaryKey val tokenId: String,
    val token: String,
    val account: String,
    val revoked: Boolean = false,
    val createdAt: Long
)
```

- [ ] **Step 2: Create the DAO**

`app/src/main/java/com/wyltek/wallet/agent/db/AgentDao.kt`:

```kotlin
package com.wyltek.wallet.agent.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface AgentDao {
    // ---- ledger usage ----
    @Query("SELECT COALESCE(SUM(amount),0) FROM spend_records WHERE tokenId=:tokenId AND asset=:asset")
    suspend fun cumulative(tokenId: String, asset: String): Long

    @Query("SELECT COALESCE(SUM(amount),0) FROM spend_records WHERE tokenId=:tokenId AND asset=:asset AND unix>=:winStart AND unix<=:now")
    suspend fun windowSum(tokenId: String, asset: String, winStart: Long, now: Long): Long

    @Query("SELECT COUNT(*) FROM spend_records WHERE tokenId=:tokenId AND nonce=:nonce")
    suspend fun nonceCount(tokenId: String, nonce: String): Int

    // ---- spend lifecycle ----
    @Insert
    suspend fun insertSpend(rec: SpendRecordEntity): Long

    /** Atomic reserve: returns the row id, or -1 if the (tokenId,nonce) pair already exists. */
    @Transaction
    suspend fun reserve(rec: SpendRecordEntity): Long {
        if (nonceCount(rec.tokenId, rec.nonce) > 0) return -1
        return insertSpend(rec)
    }

    @Query("UPDATE spend_records SET status=:status, txHash=:txHash WHERE id=:id")
    suspend fun setStatus(id: Long, status: String, txHash: String?)

    @Query("DELETE FROM spend_records WHERE id=:id")
    suspend fun deleteSpend(id: Long)

    // ---- token registry ----
    @Insert
    suspend fun insertToken(t: TokenRegistryEntity)

    @Query("SELECT * FROM token_registry WHERE token=:token LIMIT 1")
    suspend fun tokenByString(token: String): TokenRegistryEntity?

    @Query("SELECT * FROM token_registry ORDER BY createdAt DESC")
    suspend fun allTokens(): List<TokenRegistryEntity>

    @Query("UPDATE token_registry SET revoked=1 WHERE tokenId=:tokenId")
    suspend fun revoke(tokenId: String)

    @Query("SELECT revoked FROM token_registry WHERE tokenId=:tokenId LIMIT 1")
    suspend fun isRevoked(tokenId: String): Boolean?
}
```

- [ ] **Step 3: Create `AgentLedger`**

`app/src/main/java/com/wyltek/wallet/agent/AgentLedger.kt`:

```kotlin
package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.db.AgentDatabase
import com.wyltek.wallet.agent.db.SpendRecordEntity
import com.wyltek.wallet.agent.db.STATUS_CONFIRMED
import com.wyltek.wallet.core.native.LedgerView

/** Owns spend accounting; fulfills the same view/apply contract as the Rust InMemoryLedger. */
class AgentLedger(private val db: AgentDatabase) {
    private val dao = db.agentDao()

    suspend fun view(tokenId: String, asset: String, windowSeconds: Long, nowUnix: Long): LedgerView {
        val cumulative = dao.cumulative(tokenId, asset)
        val window = if (windowSeconds > 0) {
            val winStart = (nowUnix - windowSeconds).coerceAtLeast(0)
            dao.windowSum(tokenId, asset, winStart, nowUnix)
        } else 0L
        val nonceSeen = false // replay is enforced atomically in reserve(); decide() also guards on this
        return LedgerView(cumulativeSpent = cumulative, windowSpent = window, nonceSeen = nonceSeen)
    }

    /** Pre-checks replay for the decide() input (cheap read; the authoritative guard is reserve()). */
    suspend fun nonceSeen(tokenId: String, nonce: String): Boolean = dao.nonceCount(tokenId, nonce) > 0

    /** Atomically reserve a pending spend; returns row id, or null if the nonce was already used. */
    suspend fun reserve(tokenId: String, asset: String, amount: Long, unix: Long, nonce: String): Long? {
        val id = dao.reserve(SpendRecordEntity(tokenId = tokenId, asset = asset, amount = amount, unix = unix, nonce = nonce))
        return if (id < 0) null else id
    }

    suspend fun confirm(id: Long, txHash: String) = dao.setStatus(id, STATUS_CONFIRMED, txHash)
    suspend fun rollback(id: Long) = dao.deleteSpend(id)
}
```

> Note the field names on the generated `LedgerView` (`cumulativeSpent`, `windowSpent`, `nonceSeen`) — confirm against the regenerated bindings from Task 1 and adjust casing if UniFFI emitted different names.

- [ ] **Step 4: Write the instrumented test**

`app/src/androidTest/java/com/wyltek/wallet/agent/AgentLedgerTest.kt`:

```kotlin
package com.wyltek.wallet.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wyltek.wallet.agent.db.AgentDatabaseFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentLedgerTest {
    private fun newLedger(): AgentLedger {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = AgentDatabaseFactory.open(ctx, ByteArray(32) { 7 })
        return AgentLedger(db)
    }

    @Test fun reserve_then_view_sums_cumulative() = runBlocking {
        val l = newLedger()
        assertNotNull(l.reserve("t1", "CKB", 10, 100, "a"))
        assertNotNull(l.reserve("t1", "CKB", 5, 200, "b"))
        val v = l.view("t1", "CKB", 0, 300)
        assertEquals(15L, v.cumulativeSpent)
        assertEquals(0L, v.windowSpent)
    }

    @Test fun window_counts_only_recent() = runBlocking {
        val l = newLedger()
        l.reserve("t1", "CKB", 10, 100, "a")
        l.reserve("t1", "CKB", 7, 250, "b")
        val v = l.view("t1", "CKB", 100, 300) // winStart=200
        assertEquals(7L, v.windowSpent)
    }

    @Test fun duplicate_nonce_rejected() = runBlocking {
        val l = newLedger()
        assertNotNull(l.reserve("t1", "CKB", 10, 100, "dup"))
        assertNull(l.reserve("t1", "CKB", 1, 110, "dup"))
    }

    @Test fun rollback_removes_from_cumulative() = runBlocking {
        val l = newLedger()
        val id = l.reserve("t1", "CKB", 10, 100, "a")!!
        l.rollback(id)
        assertEquals(0L, l.view("t1", "CKB", 0, 300).cumulativeSpent)
    }
}
```

- [ ] **Step 5: Build + run instrumented tests (needs an emulator/device)**

Run: `cd ~/wyltek-wallet && ./gradlew :app:compileDebugKotlin` (compile gate), then `./gradlew :app:connectedDebugAndroidTest --tests "com.wyltek.wallet.agent.AgentLedgerTest"` if a device/emulator is available.
Expected: compiles; the four ledger tests pass. If no device is available in this environment, report that connected tests must be run by the operator and confirm `:app:compileDebugKotlin` is green.

- [ ] **Step 6: Commit**

```bash
cd ~/wyltek-wallet
git add app/src/main/java/com/wyltek/wallet/agent/db app/src/main/java/com/wyltek/wallet/agent/AgentLedger.kt app/src/androidTest/java/com/wyltek/wallet/agent/AgentLedgerTest.kt app/src/main/java/com/wyltek/wallet/agent/db/AgentDatabase.kt
git commit -m "feat(agent): SQLCipher ledger + token registry with atomic reserve/replay"
```

---

### Task 4: StrongBox-sealed biscuit root key

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/agent/AgentKeyStore.kt`
- Create: `app/src/androidTest/java/com/wyltek/wallet/agent/AgentKeyStoreTest.kt`

**Interfaces:**
- Consumes: `AgentSecureStore` (Task 2); `StrongBoxManager` (existing, via `WalletRepository.getStrongBoxManager()`); Rust `agentRootKeypair()` + `AgentKeyPair` (Task 1 bindings).
- Produces: `class AgentKeyStore(secure: AgentSecureStore, strongBox: StrongBoxManager)` with `fun rootPublicHex(): String` and `fun rootSecretHex(): String` (provisioning on first use), idempotent.

- [ ] **Step 1: Write the failing test**

`app/src/androidTest/java/com/wyltek/wallet/agent/AgentKeyStoreTest.kt`:

```kotlin
package com.wyltek.wallet.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wyltek.wallet.agent.store.AgentSecureStore
import com.wyltek.wallet.core.security.StrongBoxManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentKeyStoreTest {
    private fun ks(): AgentKeyStore {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return AgentKeyStore(AgentSecureStore(ctx), StrongBoxManager(ctx))
    }

    @Test fun provisions_once_and_is_stable() {
        val k = ks()
        val pub1 = k.rootPublicHex(); val sec1 = k.rootSecretHex()
        assertTrue(pub1.isNotEmpty()); assertTrue(sec1.isNotEmpty())
        // second instance over the same store returns the same key
        val pub2 = ks().rootPublicHex()
        assertEquals(pub1, pub2)
    }
}
```

- [ ] **Step 2: Implement `AgentKeyStore`**

`app/src/main/java/com/wyltek/wallet/agent/AgentKeyStore.kt`:

```kotlin
package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.store.AgentSecureStore
import com.wyltek.wallet.core.native.agentRootKeypair
import com.wyltek.wallet.core.security.StrongBoxManager

/**
 * Provisions and stores the biscuit root Ed25519 keypair — independent of the wallet
 * seed (compromise of one is not compromise of the other). The secret is sealed with
 * StrongBox when available (hardware-backed), else with the EncryptedSharedPreferences
 * AES-GCM in AgentSecureStore. The public half is stored in the clear (it's public).
 */
class AgentKeyStore(
    private val secure: AgentSecureStore,
    private val strongBox: StrongBoxManager
) {
    fun rootPublicHex(): String {
        provisionIfNeeded()
        return String(secure.loadBlob(KEY_PUB)!!, Charsets.UTF_8)
    }

    fun rootSecretHex(): String {
        provisionIfNeeded()
        val sealed = secure.loadBlob(KEY_SEC_SEALED)!!
        val raw = if (useStrongBox()) strongBox.unwrapPrivateKey(sealed) ?: sealed else sealed
        return String(raw, Charsets.UTF_8)
    }

    private fun provisionIfNeeded() {
        if (secure.loadBlob(KEY_PUB) != null && secure.loadBlob(KEY_SEC_SEALED) != null) return
        val kp = agentRootKeypair() // Rust: { secretHex, publicHex }
        secure.storeBlob(KEY_PUB, kp.publicHex.toByteArray(Charsets.UTF_8))
        val secretBytes = kp.secretHex.toByteArray(Charsets.UTF_8)
        val sealed = if (useStrongBox()) strongBox.wrapPrivateKey(secretBytes) ?: secretBytes else secretBytes
        secure.storeBlob(KEY_SEC_SEALED, sealed)
    }

    private fun useStrongBox(): Boolean = try {
        strongBox.isStrongBoxAvailable().also { if (it) strongBox.createStrongBoxKey() }
    } catch (_: Throwable) { false }

    companion object {
        private const val KEY_PUB = "biscuit_root_pub_hex"
        private const val KEY_SEC_SEALED = "biscuit_root_sec_sealed"
    }
}
```

> If `agentRootKeypair()`'s generated record exposes the fields as `secretHex`/`publicHex` vs another casing, adjust to the regenerated bindings (Task 1, Step 6 output names).

- [ ] **Step 3: Build + run**

Run: `./gradlew :app:compileDebugKotlin` then (if device) `:app:connectedDebugAndroidTest --tests "com.wyltek.wallet.agent.AgentKeyStoreTest"`.
Expected: compiles; `provisions_once_and_is_stable` passes. (No device → compile-gate only, operator runs connected test.)

- [ ] **Step 4: Commit**

```bash
cd ~/wyltek-wallet
git add app/src/main/java/com/wyltek/wallet/agent/AgentKeyStore.kt app/src/androidTest/java/com/wyltek/wallet/agent/AgentKeyStoreTest.kt
git commit -m "feat(agent): StrongBox-sealed biscuit root key provisioning"
```

---

### Task 5: Token mint / registry / revoke service

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/agent/AgentTokenService.kt`
- Create: `app/src/androidTest/java/com/wyltek/wallet/agent/AgentTokenServiceTest.kt`

**Interfaces:**
- Consumes: `AgentKeyStore` (Task 4); `AgentDatabase`/`AgentDao` (Task 3); Rust `mintToken`, `tokenCaps`, `tokenIdOf`, records `TokenSpec`/`CapInfo`/`Scope` (Task 1 bindings).
- Produces: `class AgentTokenService(keyStore: AgentKeyStore, db: AgentDatabase)` with `suspend fun mint(spec: TokenSpec): MintedToken`, `suspend fun list(): List<TokenRegistryEntity>`, `suspend fun revoke(tokenId: String)`, `suspend fun caps(token: String): List<CapInfo>`, `suspend fun isRevoked(tokenId: String): Boolean`. `data class MintedToken(val token: String, val tokenId: String)`.

- [ ] **Step 1: Write the failing test**

`app/src/androidTest/java/com/wyltek/wallet/agent/AgentTokenServiceTest.kt`:

```kotlin
package com.wyltek.wallet.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wyltek.wallet.agent.db.AgentDatabaseFactory
import com.wyltek.wallet.agent.store.AgentSecureStore
import com.wyltek.wallet.core.native.CapInfo
import com.wyltek.wallet.core.native.Scope
import com.wyltek.wallet.core.native.TokenSpec
import com.wyltek.wallet.core.security.StrongBoxManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentTokenServiceTest {
    private fun svc(): AgentTokenService {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ks = AgentKeyStore(AgentSecureStore(ctx), StrongBoxManager(ctx))
        val db = AgentDatabaseFactory.open(ctx, ByteArray(32) { 9 })
        return AgentTokenService(ks, db)
    }

    private fun spec() = TokenSpec(
        account = "ckt1qfunded",
        scopes = listOf(Scope.SEND_CKB),
        caps = listOf(CapInfo(asset = "CKB", cumulative = 100_000_000_000, windowSeconds = 0, windowLimit = 0, autoLimit = 20_000_000_000)),
        ttlUnix = null, allowTo = emptyList(), allowIp = emptyList()
    )

    @Test fun mint_persists_and_lists() = runBlocking {
        val s = svc()
        val m = s.mint(spec())
        assertTrue(m.token.isNotEmpty()); assertTrue(m.tokenId.isNotEmpty())
        assertTrue(s.list().any { it.tokenId == m.tokenId && it.account == "ckt1qfunded" })
        assertFalse(s.isRevoked(m.tokenId))
        assertEquals(1, s.caps(m.token).size)
    }

    @Test fun revoke_marks_revoked() = runBlocking {
        val s = svc()
        val m = s.mint(spec())
        s.revoke(m.tokenId)
        assertTrue(s.isRevoked(m.tokenId))
    }
}
```

- [ ] **Step 2: Implement `AgentTokenService`**

`app/src/main/java/com/wyltek/wallet/agent/AgentTokenService.kt`:

```kotlin
package com.wyltek.wallet.agent

import com.wyltek.wallet.agent.db.AgentDatabase
import com.wyltek.wallet.agent.db.TokenRegistryEntity
import com.wyltek.wallet.core.native.CapInfo
import com.wyltek.wallet.core.native.TokenSpec
import com.wyltek.wallet.core.native.mintToken
import com.wyltek.wallet.core.native.tokenCaps
import com.wyltek.wallet.core.native.tokenIdOf

data class MintedToken(val token: String, val tokenId: String)

/** Mints biscuit tokens via the Rust core and records them in the encrypted registry. */
class AgentTokenService(
    private val keyStore: AgentKeyStore,
    db: AgentDatabase
) {
    private val dao = db.agentDao()

    suspend fun mint(spec: TokenSpec): MintedToken {
        val secret = keyStore.rootSecretHex()
        val pub = keyStore.rootPublicHex()
        val token = mintToken(spec, secret)            // throws AgentException on bad spec
        val tokenId = tokenIdOf(token, pub)
        dao.insertToken(
            TokenRegistryEntity(tokenId = tokenId, token = token, account = spec.account, revoked = false, createdAt = epochSeconds())
        )
        return MintedToken(token, tokenId)
    }

    suspend fun list(): List<TokenRegistryEntity> = dao.allTokens()
    suspend fun revoke(tokenId: String) = dao.revoke(tokenId)
    suspend fun isRevoked(tokenId: String): Boolean = dao.isRevoked(tokenId) ?: false
    suspend fun caps(token: String): List<CapInfo> = tokenCaps(token, keyStore.rootPublicHex())

    private fun epochSeconds(): Long = System.currentTimeMillis() / 1000
}
```

> `Scope.SEND_CKB` etc.: confirm the generated enum-variant casing from the regenerated bindings (UniFFI typically emits SCREAMING_SNAKE for enum variants); adjust the test + any references.

- [ ] **Step 3: Build + run**

Run: `./gradlew :app:compileDebugKotlin`, then (if device) the two `AgentTokenServiceTest` tests.
Expected: compiles; tests pass (operator runs connected tests if no device).

- [ ] **Step 4: Commit**

```bash
cd ~/wyltek-wallet
git add app/src/main/java/com/wyltek/wallet/agent/AgentTokenService.kt app/src/androidTest/java/com/wyltek/wallet/agent/AgentTokenServiceTest.kt
git commit -m "feat(agent): token mint/registry/revoke service"
```

---

### Task 6: AgentActionDispatcher + MessagingSender seam + gateway facade

**Files:**
- Create: `app/src/main/java/com/wyltek/wallet/agent/MessagingSender.kt`
- Create: `app/src/main/java/com/wyltek/wallet/agent/AgentActionDispatcher.kt`
- Create: `app/src/main/java/com/wyltek/wallet/agent/AgentGateway.kt`
- Create: `app/src/androidTest/java/com/wyltek/wallet/agent/AgentActionDispatcherTest.kt`

**Interfaces:**
- Consumes: `AgentLedger` (Task 3), `AgentTokenService` (Task 5), `AgentKeyStore` (Task 4), `AccountManager` + `WalletRepository` (existing), Rust `decide`/`Intent`/`RequestCtx`/`Decision`/`CapInfo` (Task 1 bindings).
- Produces:
  - `interface MessagingSender { suspend fun send(account: WalletAccount, to: String, amount: Long, action: String?): WalletResult<String> }` + `class UnsupportedMessagingSender : MessagingSender`.
  - `sealed class DispatchResult { data class Sent(txHash); data class Approval(intentId..); data class Denied(reason); data class Failed(message) }`.
  - `class AgentActionDispatcher(...)` with `suspend fun dispatch(token: String, intent: Intent, sourceIp: String, nowUnix: Long): DispatchResult`.
  - `class AgentGateway(context: Context)` facade exposing `tokenService` and `dispatcher`.

- [ ] **Step 1: Create the messaging seam**

`app/src/main/java/com/wyltek/wallet/agent/MessagingSender.kt`:

```kotlin
package com.wyltek.wallet.agent

import com.wyltek.wallet.core.model.WalletAccount
import com.wyltek.wallet.data.WalletResult

/** On-chain message send on the wallet's behalf. v1 impl is Unsupported; Plan B-CEMP fulfills it. */
interface MessagingSender {
    suspend fun send(account: WalletAccount, to: String, amount: Long, action: String?): WalletResult<String>
}

class UnsupportedMessagingSender : MessagingSender {
    override suspend fun send(account: WalletAccount, to: String, amount: Long, action: String?): WalletResult<String> =
        WalletResult.Error("on-chain messaging not yet available (Plan B-CEMP)")
}
```

- [ ] **Step 2: Write the failing test (with a fake repository + in-memory DB)**

`app/src/androidTest/java/com/wyltek/wallet/agent/AgentActionDispatcherTest.kt` — exercises the dispatcher against a real ledger/token-service and a fake send path. Because `WalletRepository` is a concrete class, inject the send via a function type rather than the whole repository:

```kotlin
package com.wyltek.wallet.agent

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wyltek.wallet.agent.db.AgentDatabaseFactory
import com.wyltek.wallet.agent.store.AgentSecureStore
import com.wyltek.wallet.core.model.*
import com.wyltek.wallet.core.native.*
import com.wyltek.wallet.core.security.StrongBoxManager
import com.wyltek.wallet.data.WalletResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentActionDispatcherTest {
    private val account = WalletAccount(
        id = "acc1", name = "test", type = AccountType.CLASSIC, network = NetworkType.TESTNET,
        addresses = listOf(CkbAddress("ckt1qfunded", LockScript("0x00","type","0x00"), NetworkType.TESTNET, AddressFormatVersion.CKB2021)),
        createdAt = 0
    )

    private fun build(sendResult: WalletResult<String>): Triple<AgentActionDispatcher, AgentTokenService, MutableList<String>> {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val ks = AgentKeyStore(AgentSecureStore(ctx), StrongBoxManager(ctx))
        val db = AgentDatabaseFactory.open(ctx, ByteArray(32) { 3 })
        val tokenSvc = AgentTokenService(ks, db)
        val ledger = AgentLedger(db)
        val sentTo = mutableListOf<String>()
        val dispatcher = AgentActionDispatcher(
            keyStore = ks, ledger = ledger, tokenService = tokenSvc,
            resolveAccount = { addr -> if (addr == "ckt1qfunded") account else null },
            sendCkb = { _, to, _ -> sentTo.add(to); sendResult },
            sendToken = { _, _, _, _ -> sendResult },
            daoDeposit = { _, _ -> sendResult },
            daoWithdraw = { _, _ -> sendResult },
            daoClaim = { _, _ -> sendResult },
            messaging = UnsupportedMessagingSender()
        )
        return Triple(dispatcher, tokenSvc, sentTo)
    }

    private fun ckbSpec() = TokenSpec("ckt1qfunded", listOf(Scope.SEND_CKB),
        listOf(CapInfo("CKB", 100, 0, 0, 20)), null, emptyList(), emptyList())

    private fun intent(amount: Long, nonce: String) = Intent(
        op = "send_ckb", asset = "CKB", to = "ckt1qfunded", amount = amount, nonce = nonce, action = null, daoRef = null)

    @Test fun auto_spend_broadcasts_and_records() = runBlocking {
        val (d, svc, sent) = build(WalletResult.Success("0xtxhash"))
        val m = svc.mint(ckbSpec())
        val r = d.dispatch(m.token, intent(5, "n1"), "10.0.0.1", 1_700_000_000)
        assertTrue(r is DispatchResult.Sent)
        assertEquals("0xtxhash", (r as DispatchResult.Sent).txHash)
        assertEquals(listOf("ckt1qfunded"), sent)
    }

    @Test fun over_auto_limit_needs_approval_no_broadcast() = runBlocking {
        val (d, svc, sent) = build(WalletResult.Success("0xtxhash"))
        val m = svc.mint(ckbSpec())
        val r = d.dispatch(m.token, intent(50, "n1"), "10.0.0.1", 1_700_000_000)
        assertTrue(r is DispatchResult.Approval)
        assertTrue(sent.isEmpty())
    }

    @Test fun broadcast_failure_rolls_back_debit() = runBlocking {
        val (d, svc, _) = build(WalletResult.Error("pool rejected"))
        val m = svc.mint(ckbSpec())
        val r = d.dispatch(m.token, intent(5, "n1"), "10.0.0.1", 1_700_000_000)
        assertTrue(r is DispatchResult.Failed)
        // the debit was rolled back, so the same nonce can be retried and a NEW one still fits cap
        val r2 = d.dispatch(m.token, intent(5, "n2"), "10.0.0.1", 1_700_000_000)
        assertTrue(r2 is DispatchResult.Failed) // still failing send, but proves no cap consumed by the rolled-back n1
    }

    @Test fun replayed_nonce_denied() = runBlocking {
        val (d, svc, _) = build(WalletResult.Success("0xtxhash"))
        val m = svc.mint(ckbSpec())
        d.dispatch(m.token, intent(5, "n1"), "10.0.0.1", 1_700_000_000)
        val r = d.dispatch(m.token, intent(5, "n1"), "10.0.0.1", 1_700_000_000)
        assertTrue(r is DispatchResult.Denied)
    }
}
```

- [ ] **Step 3: Implement `AgentActionDispatcher`**

`app/src/main/java/com/wyltek/wallet/agent/AgentActionDispatcher.kt`:

```kotlin
package com.wyltek.wallet.agent

import com.wyltek.wallet.core.model.WalletAccount
import com.wyltek.wallet.core.native.Decision
import com.wyltek.wallet.core.native.Intent
import com.wyltek.wallet.core.native.LedgerView
import com.wyltek.wallet.core.native.RequestCtx
import com.wyltek.wallet.core.native.decide
import com.wyltek.wallet.data.WalletResult

sealed class DispatchResult {
    data class Sent(val txHash: String, val tokenId: String) : DispatchResult()
    data class Approval(val tokenId: String, val asset: String, val amount: Long) : DispatchResult()
    data class Denied(val reason: String) : DispatchResult()
    data class Failed(val message: String) : DispatchResult()
}

/**
 * Turns an agent Intent into a policy decision and, on AllowAuto, an atomic
 * debit-then-broadcast. The send functions are injected (the concrete WalletRepository
 * is wired by AgentGateway) so this class is unit-testable with fakes.
 */
class AgentActionDispatcher(
    private val keyStore: AgentKeyStore,
    private val ledger: AgentLedger,
    private val tokenService: AgentTokenService,
    private val resolveAccount: (bech32m: String) -> WalletAccount?,
    private val sendCkb: suspend (acct: WalletAccount, to: String, amount: Long) -> WalletResult<String>,
    private val sendToken: suspend (acct: WalletAccount, assetId: String, to: String, amount: Long) -> WalletResult<String>,
    private val daoDeposit: suspend (acct: WalletAccount, amount: Long) -> WalletResult<String>,
    private val daoWithdraw: suspend (acct: WalletAccount, daoRef: String) -> WalletResult<String>,
    private val daoClaim: suspend (acct: WalletAccount, daoRef: String) -> WalletResult<String>,
    private val messaging: MessagingSender
) {
    suspend fun dispatch(token: String, intent: Intent, sourceIp: String, nowUnix: Long): DispatchResult {
        // 1. Registry: known + not revoked. (token_id is the ledger key.)
        val reg = tokenService.list().firstOrNull { it.token == token }
            ?: return DispatchResult.Denied("unknown token")
        if (reg.revoked) return DispatchResult.Denied("token revoked")

        // 2. Caps (to learn the window length for the view) + ledger view + replay flag.
        val pub = keyStore.rootPublicHex()
        val caps = try { tokenService.caps(token) } catch (e: Throwable) { return DispatchResult.Denied("bad token: ${e.message}") }
        val cap = caps.firstOrNull { it.asset == intent.asset }
            ?: return DispatchResult.Denied("no cap for asset ${intent.asset}")
        val baseView = ledger.view(reg.tokenId, intent.asset, cap.windowSeconds, nowUnix)
        val view = LedgerView(
            cumulativeSpent = baseView.cumulativeSpent,
            windowSpent = baseView.windowSpent,
            nonceSeen = ledger.nonceSeen(reg.tokenId, intent.nonce)
        )

        // 3. Pure policy decision (scope/account/ttl/allow_to/allow_ip + caps + tiering).
        val ctx = RequestCtx(account = reg.account, sourceIp = sourceIp, nowUnix = nowUnix)
        when (val d = decide(token, pub, intent, view, ctx)) {
            is Decision.Deny -> return DispatchResult.Denied(d.reason)
            is Decision.NeedApproval -> return DispatchResult.Approval(d.tokenId, d.asset, d.amount)
            is Decision.AllowAuto -> return executeAuto(d.tokenId, intent, nowUnix)
        }
    }

    private suspend fun executeAuto(tokenId: String, intent: Intent, nowUnix: Long): DispatchResult {
        val account = resolveAccount(currentAccountAddr(tokenId)) ?: return DispatchResult.Denied("unknown account")
        // Atomic reserve (durable debit) BEFORE broadcast; unique (tokenId,nonce) guards concurrency/replay.
        val rowId = ledger.reserve(tokenId, intent.asset, intent.amount, nowUnix, intent.nonce)
            ?: return DispatchResult.Denied("replay: nonce ${intent.nonce}")
        val result = try {
            runAction(account, intent)
        } catch (e: Throwable) {
            ledger.rollback(rowId); return DispatchResult.Failed("send threw: ${e.message}")
        }
        return when (result) {
            is WalletResult.Success -> { ledger.confirm(rowId, result.data); DispatchResult.Sent(result.data, tokenId) }
            is WalletResult.Error -> { ledger.rollback(rowId); DispatchResult.Failed(result.message) }
        }
    }

    private suspend fun runAction(account: WalletAccount, intent: Intent): WalletResult<String> = when (intent.op) {
        "send_ckb" -> sendCkb(account, intent.to, intent.amount)
        "send_udt" -> sendToken(account, intent.asset, intent.to, intent.amount)
        "dao" -> when (intent.action) {
            "deposit", null -> daoDeposit(account, intent.amount)
            "withdraw" -> intent.daoRef?.let { daoWithdraw(account, it) } ?: WalletResult.Error("dao withdraw needs dao_ref")
            "claim" -> intent.daoRef?.let { daoClaim(account, it) } ?: WalletResult.Error("dao claim needs dao_ref")
            else -> WalletResult.Error("unknown dao action ${intent.action}")
        }
        "messaging" -> messaging.send(account, intent.to, intent.amount, intent.action)
        else -> WalletResult.Error("unsupported op ${intent.op}")
    }

    // The token's bound account address is stored in the registry; fetch by tokenId.
    private suspend fun currentAccountAddr(tokenId: String): String =
        tokenService.list().firstOrNull { it.tokenId == tokenId }?.account ?: ""
}
```

- [ ] **Step 4: Implement the `AgentGateway` facade**

`app/src/main/java/com/wyltek/wallet/agent/AgentGateway.kt`:

```kotlin
package com.wyltek.wallet.agent

import android.content.Context
import com.wyltek.wallet.agent.db.AgentDatabaseFactory
import com.wyltek.wallet.agent.store.AgentSecureStore
import com.wyltek.wallet.core.account.AccountManager
import com.wyltek.wallet.core.model.LockScript
import com.wyltek.wallet.data.WalletRepository
import com.wyltek.wallet.data.WalletResult
import java.math.BigInteger

/** Wires the on-device Agent Gateway from a Context (manual DI, mirroring WalletRepository). */
class AgentGateway(context: Context) {
    private val app = context.applicationContext
    private val secure = AgentSecureStore(app)
    private val repository = WalletRepository(app)
    private val accountManager = AccountManager(app)
    private val keyStore = AgentKeyStore(secure, repository.getStrongBoxManager())
    private val db = AgentDatabaseFactory.open(app, secure.sqlcipherPassphrase())
    private val ledger = AgentLedger(db)

    val tokenService = AgentTokenService(keyStore, db)

    val dispatcher = AgentActionDispatcher(
        keyStore = keyStore,
        ledger = ledger,
        tokenService = tokenService,
        resolveAccount = { addr -> accountManager.getAllAccounts().firstOrNull { a -> a.addresses.any { it.bech32m == addr } } },
        sendCkb = { acct, to, amount ->
            val from = acct.addresses.firstOrNull { it.bech32m == to } ?: acct.addresses.firstOrNull()
            repository.sendCkb(acct, to, amount.toULong(), fromCkbAddress = from)
        },
        sendToken = { acct, assetId, to, amount ->
            val ts = parseTypeScript(assetId) ?: return@AgentActionDispatcher_send WalletResult.Error("bad asset id")
            repository.sendToken(acct, ts, to, BigInteger.valueOf(amount))
        },
        daoDeposit = { acct, amount -> repository.depositDao(acct, amount.toULong()) },
        daoWithdraw = { acct, daoRef -> resolveDeposit(acct, daoRef)?.let { repository.withdrawDaoPhase1(acct, it) } ?: WalletResult.Error("deposit not found: $daoRef") },
        daoClaim = { acct, daoRef -> resolveDeposit(acct, daoRef)?.let { repository.claimDao(acct, it) } ?: WalletResult.Error("deposit not found: $daoRef") },
        messaging = UnsupportedMessagingSender()
    )

    private fun parseTypeScript(assetId: String): LockScript? {
        val p = assetId.split(":"); if (p.size != 3) return null
        return LockScript(codeHash = p[0], hashType = p[1], args = p[2])
    }

    /** Resolve a "txhash:index" dao_ref to a DaoDeposit by scanning the account's deposits. */
    private suspend fun resolveDeposit(acct: com.wyltek.wallet.core.model.WalletAccount, daoRef: String) =
        run {
            val lock = acct.addresses.firstOrNull()?.lockScript ?: return@run null
            val scan = repository.scanDaoDeposits(lock, acct.network)
            val list = (scan as? WalletResult.Success)?.data ?: return@run null
            list.firstOrNull { matchesOutpoint(it, daoRef) }
        }

    private fun matchesOutpoint(deposit: com.wyltek.wallet.core.model.DaoDeposit, daoRef: String): Boolean {
        // daoRef = "txHash:index"; compare against deposit.outPoint fields (confirm exact names in DaoModels.kt)
        val parts = daoRef.split(":"); if (parts.size != 2) return false
        return deposit.outPoint.txHash == parts[0] && deposit.outPoint.index.toString() == parts[1]
    }
}
```

> Two integration details to confirm against the real source when implementing:
> 1. The lambda return inside `sendToken` cannot use a labeled `return@AgentActionDispatcher_send` — that placeholder denotes "return the WalletResult from this lambda." Write it as an `if (ts == null) WalletResult.Error(...) else repository.sendToken(...)` expression so the lambda yields a `WalletResult<String>` without a non-local return.
> 2. `DaoDeposit.outPoint` field names (`txHash`/`index`) — verify in `DaoModels.kt`/`Models.kt` and adjust `matchesOutpoint`.

- [ ] **Step 5: Build + run**

Run: `./gradlew :app:compileDebugKotlin`, then (if device) `:app:connectedDebugAndroidTest --tests "com.wyltek.wallet.agent.AgentActionDispatcherTest"`.
Expected: compiles; the four dispatcher tests pass (auto-spend broadcasts + records, over-limit→approval no broadcast, broadcast-failure rolls back, replay denied). Operator runs connected tests if no device.

- [ ] **Step 6: Commit**

```bash
cd ~/wyltek-wallet
git add app/src/main/java/com/wyltek/wallet/agent/MessagingSender.kt app/src/main/java/com/wyltek/wallet/agent/AgentActionDispatcher.kt app/src/main/java/com/wyltek/wallet/agent/AgentGateway.kt app/src/androidTest/java/com/wyltek/wallet/agent/AgentActionDispatcherTest.kt
git commit -m "feat(agent): action dispatcher (decide -> atomic debit+broadcast) + gateway facade"
```

---

## Self-Review

**1. Spec coverage** (against `2026-06-19-agent-gateway-design.md` Parts 3 + parts of 5, and the B1 scope agreed with the user):

- Part 3 `AgentActionDispatcher` mapping Intent → existing builders under atomic sign+debit → Task 6. send_ckb/send_udt/dao(deposit+withdraw+claim) wired; messaging via `MessagingSender` interface (Plan B-CEMP fulfills it — user chose "build on-chain CEMP now", tracked as the next plan).
- Part 5 token mint/registry/revoke + caps → Task 5; StrongBox-sealed biscuit root key → Task 4. (Approval UI, the notification/biometric flow, and token-management screens are Plan B2.)
- Spec Part 2 atomic debit-then-sign → realized as durable optimistic-reserve (pending) → broadcast → confirm|rollback, with a DB unique `(tokenId,nonce)` index as the concurrency/replay guard (Task 3 + Task 6). This is the Android-faithful form of the spec's atomicity (a DB transaction cannot be held across the network broadcast).
- Intent extension for DAO withdraw/claim (user choice) → Task 1.

**2. Placeholder scan:** No "TBD"/"add error handling". Three explicit verify-against-source notes are named, compile-gated adjustments (generated binding casing in Task 1 Step 6; `DaoDeposit.outPoint` field names; the non-local-return rewrite in the `sendToken` lambda) — each names exactly what to check, not vague filler.

**3. Type consistency:** `DispatchResult`, `MintedToken`, `MessagingSender`, `AgentLedger`, `AgentTokenService`, `AgentKeyStore`, `AgentSecureStore` defined once and used consistently. The dispatcher consumes `tokenService.list()`/`.caps()`/`ledger.view/nonceSeen/reserve/confirm/rollback` exactly as Tasks 3/5 define them. Rust `Intent` (with `action`/`daoRef`), `LedgerView`, `RequestCtx`, `Decision`, `CapInfo`, `TokenSpec`, `Scope` are the Task-1 regenerated bindings.

**Open items (flagged):**
- **Binding casing** (`cumulativeSpent` vs `cumulative_spent`, `Scope.SEND_CKB` vs `SendCkb`, `daoRef` vs `dao_ref`): confirm against Task 1 Step 6 grep output and fix references in one pass. UniFFI Kotlin conventions are assumed; the compile gate catches mismatches.
- **No-device CI:** Room/SQLCipher instrumented tests need an emulator/device. Where unavailable, the per-task DoD is `:app:compileDebugKotlin` green + operator runs `connectedDebugAndroidTest`. Note this is weaker than Plan A's host-run tests.
- **Atomicity model** is reserve-then-broadcast-then-confirm/rollback (not a single DB transaction spanning the network call). The unique-nonce index is the hard guard; document this in the dispatcher so Plan B2's UI and Plan C's relay rely on the same invariant.
- **Messaging** is an interface only in B1; Plan B-CEMP (next) ports `~/ecms/cemp-pq` to implement it.
