package de.baschek.gymapp.core

import de.baschek.gymapp.core.live.LiveUpdateStatus
import de.baschek.gymapp.core.live.LiveUpdateStatusLogic.decide
import kotlin.test.Test
import kotlin.test.assertEquals

class LiveUpdateStatusTest {
    @Test fun olderThanAndroid16IsUnsupported() = assertEquals(LiveUpdateStatus.UNSUPPORTED, decide(35, null, false, null))

    @Test fun allowedWins() = assertEquals(LiveUpdateStatus.ALLOWED, decide(36, 3_600_000, true, null))

    @Test fun qpr0NotAllowedIsNotOfficial() = assertEquals(LiveUpdateStatus.NOT_OFFICIAL, decide(36, 3_600_000, false, null))

    @Test fun qpr0WithSamsungSwitchOff() = assertEquals(LiveUpdateStatus.NOT_OFFICIAL, decide(36, 3_600_000, false, 0))

    @Test fun qpr0WithSamsungSwitchOn() = assertEquals(LiveUpdateStatus.SAMSUNG_TEST_SWITCH, decide(36, 3_600_000, false, 1))

    @Test fun qpr2NotAllowedIsNotAllowed() = assertEquals(LiveUpdateStatus.NOT_ALLOWED, decide(36, 3_600_001, false, 1))

    @Test fun unreadableFullOnApi36IsNotOfficial() = assertEquals(LiveUpdateStatus.NOT_OFFICIAL, decide(36, null, false, null))

    @Test fun api37NotAllowedIsNotAllowed() = assertEquals(LiveUpdateStatus.NOT_ALLOWED, decide(37, null, false, null))
}
