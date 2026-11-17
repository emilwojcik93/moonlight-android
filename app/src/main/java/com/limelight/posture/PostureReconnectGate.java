package com.limelight.posture;

/**
 * Decides when a posture flip may trigger a reconnect (resume of the same app).
 * Pure functions over plain values so the policy is unit testable on a plain JVM.
 */
public final class PostureReconnectGate {
    /** No reconnect this soon after the stream started. */
    public static final long STARTUP_GUARD_MS = 10_000;
    /** No second reconnect within this time of the previous one (never loop). */
    public static final long COOLDOWN_MS = 60_000;

    /** Returned by {@link #delayMs} when reconnecting is not allowed at all. */
    public static final long DISABLED = -1;

    private PostureReconnectGate() {
    }

    /**
     * Reads {@code display_change="0|1"} from the root element of a /posture reply.
     *
     * @return TRUE when the host needs a new display for the new posture, FALSE when its display still
     *         fits, null when the host did not say (older hosts)
     */
    public static Boolean parseDisplayChange(String xml) {
        if (xml == null) {
            return null;
        }
        int root = xml.indexOf("<root");
        int rootEnd = root < 0 ? -1 : xml.indexOf('>', root);
        String attr = "display_change=\"";
        int at = xml.indexOf(attr);
        if (at < 0 || rootEnd < 0 || at > rootEnd) {
            return null;
        }
        int value = at + attr.length();
        if (value + 1 >= xml.length() || xml.charAt(value + 1) != '"') {
            return null;
        }
        switch (xml.charAt(value)) {
            case '1':
                return Boolean.TRUE;
            case '0':
                return Boolean.FALSE;
            default:
                return null;
        }
    }

    /**
     * Whether the stream must be resumed to get a fitting host display.
     *
     * @param hostAnswer   the host's display_change answer, or null when it gave none
     * @param staleByClass the previous rule: the touch class changed since the stream started
     */
    public static boolean reconnectNeeded(Boolean hostAnswer, boolean staleByClass) {
        return hostAnswer != null ? hostAnswer : staleByClass;
    }

    /**
     * @param enabled          the "Reconnect when posture changes" setting
     * @param connected        the stream is up (not connecting or stopping)
     * @param nowMs            monotonic clock
     * @param connectedAtMs    when the stream came up
     * @param lastReconnectMs  when the last posture reconnect started, or a negative value if none
     * @return {@link #DISABLED}, 0 to reconnect now, or the time to wait before asking again
     */
    public static long delayMs(boolean enabled, boolean connected, long nowMs,
                               long connectedAtMs, long lastReconnectMs) {
        if (!enabled || !connected) {
            return DISABLED;
        }
        long wait = connectedAtMs + STARTUP_GUARD_MS - nowMs;
        if (lastReconnectMs >= 0) {
            wait = Math.max(wait, lastReconnectMs + COOLDOWN_MS - nowMs);
        }
        return Math.max(wait, 0);
    }
}
