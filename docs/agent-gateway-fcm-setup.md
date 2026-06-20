# Agent Gateway — FCM Fallback Wake (operator setup)

Plan C ships the **persistent tailnet connection** as the primary wake path (the
`RelayClient` foreground WebSocket — fully working). **FCM is an optional fallback**
for when the phone is off-tailnet. It is NOT wired in code because applying the
`com.google.gms.google-services` Gradle plugin requires a real `google-services.json`
at build time — committing the plugin without it breaks every build. Enable it as follows.

## Operator prerequisites (one-time)

1. Create a Firebase project; add an Android app with package `com.wyltek.wallet`.
2. Download `google-services.json` into `app/`.
3. Download an Admin SDK service-account JSON onto `wyltek-10700` at the path in the
   relay's `RELAY_FCM_CREDS` env var (the relay's `wake.fcm_wake` lazy-inits from it;
   with no creds it simply returns `False` and the persistent path is used).

## Code to add (after the JSON files exist)

**Root `build.gradle.kts`** plugins:
```kotlin
id("com.google.gms.google-services") version "4.4.2" apply false
```

**`app/build.gradle.kts`**: apply the plugin + add the dependency:
```kotlin
plugins { /* ... */ id("com.google.gms.google-services") }
dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")
}
```

**`AndroidManifest.xml`** inside `<application>`:
```xml
<service android:name=".agent.fcm.AgentFirebaseService" android:exported="false">
    <intent-filter><action android:name="com.google.firebase.MESSAGING_EVENT" /></intent-filter>
</service>
```

**`app/src/main/java/com/wyltek/wallet/agent/fcm/AgentFirebaseService.kt`**:
```kotlin
package com.wyltek.wallet.agent.fcm

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.wyltek.wallet.agent.service.AgentGatewayService

/** FCM fallback: a high-priority data-only "agent_wake" nudge starts the gateway
 *  service, whose RelayClient then connects and drains queued intents from the relay.
 *  The nudge carries NO intent contents — the phone pulls the real intent over the
 *  authenticated WebSocket and re-validates it through the on-device dispatcher. */
class AgentFirebaseService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] == "agent_wake") AgentGatewayService.start(this)
    }
    override fun onNewToken(token: String) {
        // Re-pair to upload the refreshed FCM token (RelayPairing reads the stored relay URL + device token).
        AgentGatewayService.start(this)
    }
}
```

**`RelayPairing`**: it already sends an `fcm_token` field in the `/pair` body (currently
`""`). Populate it from `FirebaseMessaging.getInstance().token` (await) once Firebase is
present, so the relay can `fcm_wake` this device.

## Verify

`./gradlew :app:assembleDebug` (needs `google-services.json`). On-device: with the phone
off-tailnet, POST a `/relay/intent`; the relay's `fcm_wake` sends the nudge; the phone
starts the service, the RelayClient connects over the tailnet (re-established by the
nudge), drains the intent, and posts the result back.

Until this is enabled, the relay uses ONLY the persistent connection — the phone must
have tailnet connectivity for the relay path to reach it.
