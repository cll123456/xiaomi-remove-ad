package app.jingqi.guard.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class SplashHealthTest {
    @Test fun permissionAloneIsNotRunning() {
        assertEquals(SplashHealth.NOT_CONNECTED, SplashRuntimeState().health(true, 10_000L))
    }

    @Test fun revocationOverridesOldHeartbeat() {
        assertEquals(SplashHealth.DISABLED,
            SplashRuntimeState(connected = true, heartbeatAt = 10_000L).health(false, 10_000L))
    }

    @Test fun staleOrInvalidHeartbeatIsNotHealthy() {
        val state = SplashRuntimeState(connected = true, heartbeatAt = 10_000L)
        assertEquals(SplashHealth.UNRESPONSIVE, state.health(true, 18_001L))
        assertEquals(SplashHealth.UNRESPONSIVE, state.health(true, 9_999L))
    }

    @Test fun liveServiceDoesNotNeedExpertConnection() {
        assertEquals(SplashHealth.RUNNING,
            SplashRuntimeState(connected = true, heartbeatAt = 10_000L).health(true, 11_000L))
    }

    @Test fun disconnectAndProcessRestartCannotReuseLastKnownRunning() {
        val state = SplashRuntimeState(connected = true, heartbeatAt = 10_000L)
        assertEquals(SplashHealth.NOT_CONNECTED, state.copy(connected = false).health(true, 10_001L))
        assertEquals(SplashHealth.NOT_CONNECTED, SplashRuntimeState().health(true, 10_001L))
    }
}
