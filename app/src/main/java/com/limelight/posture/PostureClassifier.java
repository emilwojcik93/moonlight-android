package com.limelight.posture;

import java.util.Locale;

/**
 * Decides which client posture class to advertise to the host.
 *
 * This class is deliberately free of Android dependencies: every input is a plain value
 * (see {@link Inputs}) so the decision logic can be unit tested on a plain JVM.
 * Wire format follows the stream-posture-sync contract v2: only {@code clientPosture} and
 * {@code clientTouch} are sent. Screen size, density, aspect ratio and orientation never take
 * part in the decision. The result only says whether the device is touch-primary (phone/tablet)
 * or has a physical keyboard in use (laptop), or is not a touch device at all (desktop).
 */
public final class PostureClassifier {
    /**
     * Smallest width (dp) at which a touch device is labelled "tablet" instead of "phone".
     * Diagnostics only: the host treats both labels the same. The smallest width does not
     * change when the device is rotated.
     */
    public static final int TABLET_MIN_SMALLEST_WIDTH_DP = 600;

    public enum Posture {
        DESKTOP, LAPTOP, TABLET, HANDHELD, PHONE;

        public String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Confidence { HIGH, LOW }

    /** What the host does with a posture; several postures share one mode. */
    public enum HostMode { DESKTOP, TABLET, HANDHELD }

    /** User setting: Auto / Always tablet / Always handheld / Always desktop / Never. */
    public enum UserOverride {
        AUTO, ALWAYS_TABLET, ALWAYS_HANDHELD, ALWAYS_DESKTOP, NEVER;

        /** Parses the stored preference value; anything unknown means AUTO. */
        public static UserOverride fromPreference(String value) {
            if ("tablet".equals(value)) {
                return ALWAYS_TABLET;
            }
            if ("handheld".equals(value)) {
                return ALWAYS_HANDHELD;
            }
            if ("desktop".equals(value)) {
                return ALWAYS_DESKTOP;
            }
            if ("never".equals(value)) {
                return NEVER;
            }
            return AUTO;
        }
    }

    /** Plain-value signals. Unknown numeric values are 0. */
    public static final class Inputs {
        public UserOverride override = UserOverride.AUTO;
        /** UiModeManager reports anything other than normal (TV, car, desk, watch, ...). */
        public boolean nonHandheldUiMode;
        /** The stream is shown on a secondary (external) display. */
        public boolean externalDisplay;
        public boolean hasTouchscreen;
        /** Configuration.keyboard/hardKeyboardHidden say a hardware keyboard is present (weak hint). */
        public boolean hardKeyboardAttached;
        /** An enabled, non-virtual InputDevice with an alphabetic keyboard is attached (BT/USB). */
        public boolean alphabeticKeyboardDevice;
        /**
         * An enabled, non-virtual gamepad is present: built in (handheld console) or attached
         * (telescopic grip, Bluetooth pad). With it the device is used like a handheld.
         */
        public boolean gamepadDevice;
        /** Smallest screen width in dp; only picks the phone/tablet label. 0 if unknown. */
        public int smallestWidthDp;
    }

    public static final class Result {
        public final Posture posture;
        public final Confidence confidence;
        public final boolean touch;

        Result(Posture posture, Confidence confidence, boolean touch) {
            this.posture = posture;
            this.confidence = confidence;
            this.touch = touch;
        }

        /** True when the host touch policy would apply (tablet, handheld, phone). */
        public boolean isTouchClass() {
            return posture == Posture.TABLET || posture == Posture.HANDHELD || posture == Posture.PHONE;
        }

        /** The mode the host picks for this posture: desktop, tablet or handheld. */
        public HostMode hostMode() {
            if (posture == Posture.HANDHELD) {
                return HostMode.HANDHELD;
            }
            return isTouchClass() ? HostMode.TABLET : HostMode.DESKTOP;
        }

        /** Query string without leading '&', e.g. {@code clientPosture=tablet&clientTouch=1&...}. */
        public String toQuery() {
            StringBuilder sb = new StringBuilder();
            sb.append("clientPosture=").append(posture.wireValue());
            sb.append("&clientTouch=").append(touch ? 1 : 0);
            return sb.toString();
        }

        /** Query for launch/resume: empty for a plain desktop so stock behaviour is unchanged. */
        public String toLaunchQuery() {
            return posture == Posture.DESKTOP ? "" : toQuery();
        }
    }

    private PostureClassifier() {
    }

    /**
     * @return the classification, or null when the user chose Never (nothing is ever sent)
     */
    public static Result classify(Inputs in) {
        if (in.override == UserOverride.NEVER) {
            return null;
        }

        if (in.override == UserOverride.ALWAYS_TABLET) {
            return new Result(Posture.TABLET, Confidence.HIGH, true);
        }

        if (in.override == UserOverride.ALWAYS_HANDHELD) {
            return new Result(Posture.HANDHELD, Confidence.HIGH, true);
        }

        if (in.override == UserOverride.ALWAYS_DESKTOP) {
            // Desktop mode on the host, but still a touch device: the host keeps the touch-sized display.
            return new Result(Posture.LAPTOP, Confidence.HIGH, true);
        }

        if (in.nonHandheldUiMode || in.externalDisplay || !in.hasTouchscreen) {
            // TV, car, desk dock, no touch hardware or a monitor: behaves like a desktop.
            return new Result(Posture.DESKTOP, Confidence.HIGH, false);
        }

        if (in.alphabeticKeyboardDevice) {
            // A physical keyboard is in use: desktop mode on the host. The device still has a touch
            // screen (touch = capability), so the host keeps the touch-sized display and a later
            // keyboard detach only switches its mode.
            return new Result(Posture.LAPTOP, Confidence.HIGH, true);
        }

        if (in.gamepadDevice) {
            // A gamepad and no real keyboard: the device is used like a handheld console. This is
            // checked before the Configuration keyboard hint because a gamepad's buttons make
            // Android report a (non-alphabetic) hardware keyboard.
            return new Result(Posture.HANDHELD, Confidence.HIGH, true);
        }

        if (in.hardKeyboardAttached) {
            // Only the Configuration hint, no keyboard device found: weak, but still a keyboard.
            return new Result(Posture.LAPTOP, Confidence.LOW, true);
        }

        // Touch-primary. The label does not matter to the host.
        Posture label = in.smallestWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP ? Posture.TABLET : Posture.PHONE;
        return new Result(label, in.smallestWidthDp <= 0 ? Confidence.LOW : Confidence.HIGH, true);
    }

    /**
     * Mid-stream rule: only a switch between "touch-primary" and "not touch-primary"
     * (keyboard attached or detached) is worth telling the host about. Phone to tablet,
     * rotation and similar changes are not.
     */
    public static boolean crossesBoundary(Result previous, Result current) {
        boolean before = previous != null && previous.isTouchClass();
        boolean after = current != null && current.isTouchClass();
        return before != after;
    }

    /**
     * Mid-stream rule for telling the host: its mode (desktop, tablet, handheld) would change.
     * A gamepad grip attached to or removed from a phone changes the mode without crossing the
     * touch boundary, so this is wider than {@link #crossesBoundary}.
     */
    public static boolean hostModeChanged(Result previous, Result current) {
        HostMode before = previous != null ? previous.hostMode() : HostMode.DESKTOP;
        HostMode after = current != null ? current.hostMode() : HostMode.DESKTOP;
        return before != after;
    }
}
