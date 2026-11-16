package com.limelight.posture;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PostureReconnectGateTest {
    private static final long NONE = -1;

    @Test
    public void disabledOrNotConnectedNeverReconnects() {
        assertEquals(PostureReconnectGate.DISABLED,
                PostureReconnectGate.delayMs(false, true, 100_000, 0, NONE));
        assertEquals(PostureReconnectGate.DISABLED,
                PostureReconnectGate.delayMs(true, false, 100_000, 0, NONE));
    }

    @Test
    public void startupGuardWaitsForRemainingTime() {
        assertEquals(10_000, PostureReconnectGate.delayMs(true, true, 5_000, 5_000, NONE));
        assertEquals(4_000, PostureReconnectGate.delayMs(true, true, 11_000, 5_000, NONE));
        assertEquals(0, PostureReconnectGate.delayMs(true, true, 15_000, 5_000, NONE));
    }

    @Test
    public void cooldownBlocksSecondReconnect() {
        long last = 100_000;
        // new stream came up 12 s after the reconnect, flip 15 s after it: cooldown still has 45 s
        assertEquals(45_000, PostureReconnectGate.delayMs(true, true, 115_000, 112_000, last));
        assertEquals(1, PostureReconnectGate.delayMs(true, true, 159_999, 112_000, last));
        assertEquals(0, PostureReconnectGate.delayMs(true, true, 160_000, 112_000, last));
    }

    @Test
    public void flipSequenceIsRateLimitedAndNeverLoops() {
        long connectedAt = 0;
        long last = NONE;
        int reconnects = 0;
        // keyboard toggled every 5 s for 5 minutes; a reconnect restarts the stream (new connectedAt)
        for (long t = 15_000; t <= 300_000; t += 5_000) {
            if (PostureReconnectGate.delayMs(true, true, t, connectedAt, last) == 0) {
                reconnects++;
                last = t;
                connectedAt = t + 3_000;   // resume takes a few seconds
            }
        }
        // one per cooldown window at most: 300 s / 60 s
        assertEquals(5, reconnects);
    }

    @Test
    public void firstFlipAfterLongStreamReconnectsImmediately() {
        assertEquals(0, PostureReconnectGate.delayMs(true, true, 3_600_000, 0, NONE));
    }
}
