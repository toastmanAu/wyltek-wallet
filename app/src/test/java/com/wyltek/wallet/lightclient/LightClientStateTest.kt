package com.wyltek.wallet.lightclient

import org.junit.Assert.assertEquals
import org.junit.Test

class LightClientStateTest {
    @Test fun stopped_when_not_running() {
        assertEquals(LightClientState.STOPPED, classifyState(false, null, null))
    }
    @Test fun starting_when_running_but_no_tip_yet() {
        assertEquals(LightClientState.STARTING, classifyState(true, null, 100u))
    }
    @Test fun syncing_when_client_tip_lags_network() {
        assertEquals(LightClientState.SYNCING, classifyState(true, 100u, 1000u))
    }
    @Test fun ready_when_within_lag_threshold() {
        assertEquals(LightClientState.READY, classifyState(true, 970u, 1000u, lagThreshold = 50u))
    }
}
