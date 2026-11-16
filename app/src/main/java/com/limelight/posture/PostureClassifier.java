package com.limelight.posture;

import java.util.Locale;

/**
 * Decides which client posture class to advertise to the host.
 *
 * This class is deliberately free of Android dependencies: every input is a plain value
 * (see {@link Inputs}) so the decision logic can be unit tested on a plain JVM.
 * Wire format follows the stream-posture-sync contract v1.
 */
public final class PostureClassifier {
    public static final int MIN_DIAGONAL_MM = 50;
    public static final int MAX_DIAGONAL_MM = 800;
    public static final int MIN_DPI = 72;
    public static final int MAX_DPI = 800;

    /** Smallest width (dp) at which a touch device is considered a tablet. */
    public static final int TABLET_MIN_SMALLEST_WIDTH_DP = 600;

    private static final double MM_PER_INCH = 25.4;

    // Manufacturers that only (or almost only) build handheld gaming devices.
    private static final String[] HANDHELD_MANUFACTURERS = {
            "ayaneo", "ayn", "retroid", "anbernic"
    };

    public enum Posture {
        DESKTOP, LAPTOP, TABLET, HANDHELD, PHONE;

        public String wireValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public enum Confidence { HIGH, LOW }

    /** User setting: Auto / Always tablet / Never. */
    public enum UserOverride {
        AUTO, ALWAYS_TABLET, NEVER;

        /** Parses the stored preference value; anything unknown means AUTO. */
        public static UserOverride fromPreference(String value) {
            if ("tablet".equals(value)) {
                return ALWAYS_TABLET;
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
        /** A hardware keyboard is present and not hidden. */
        public boolean hardKeyboardAttached;
        public boolean knownHandheld;
        public int smallestWidthDp;
        /** Physical diagonal as reported by the OS, 0 if unknown. */
        public double diagonalMm;
        /** Screen density as reported by the OS, 0 if unknown. */
        public int dpi;
    }

    public static final class Result {
        public final Posture posture;
        public final Confidence confidence;
        public final boolean touch;
        /** Validated diagonal, or 0 when unknown/out of range. */
        public final int diagonalMm;
        /** Validated density, or 0 when unknown/out of range. */
        public final int dpi;

        Result(Posture posture, Confidence confidence, boolean touch, int diagonalMm, int dpi) {
            this.posture = posture;
            this.confidence = confidence;
            this.touch = touch;
            this.diagonalMm = diagonalMm;
            this.dpi = dpi;
        }

        /** True when the host touch/size policy would apply (tablet, handheld, phone). */
        public boolean isTouchClass() {
            return posture == Posture.TABLET || posture == Posture.HANDHELD || posture == Posture.PHONE;
        }

        /** Query string without leading '&', e.g. {@code clientPosture=tablet&clientTouch=1&...}. */
        public String toQuery() {
            StringBuilder sb = new StringBuilder();
            sb.append("clientPosture=").append(posture.wireValue());
            sb.append("&clientTouch=").append(touch ? 1 : 0);
            if (diagonalMm != 0) {
                sb.append("&clientDiagonalMm=").append(diagonalMm);
            }
            if (dpi != 0) {
                sb.append("&clientDpi=").append(dpi);
            }
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

        int diag = validDiagonalMm(in.diagonalMm);
        int dpi = validDpi(in.dpi);

        if (in.override == UserOverride.ALWAYS_TABLET) {
            return new Result(Posture.TABLET, Confidence.HIGH, true, diag, dpi);
        }

        if (in.nonHandheldUiMode || in.externalDisplay || !in.hasTouchscreen) {
            // TV, car, desk dock, no touch hardware or a monitor: behaves like a desktop.
            return new Result(Posture.DESKTOP, Confidence.HIGH, false, diag, dpi);
        }

        if (in.knownHandheld) {
            return new Result(Posture.HANDHELD, Confidence.HIGH, true, diag, dpi);
        }

        if (in.smallestWidthDp >= TABLET_MIN_SMALLEST_WIDTH_DP) {
            if (in.hardKeyboardAttached) {
                // Touch tablet with a docked keyboard: laptop-like, but the signal is ambiguous.
                return new Result(Posture.LAPTOP, Confidence.LOW, true, diag, dpi);
            }
            return new Result(Posture.TABLET, Confidence.HIGH, true, diag, dpi);
        }

        // A phone with a paired keyboard is still a phone; only the confidence drops.
        Confidence c = (in.smallestWidthDp <= 0 || in.hardKeyboardAttached) ? Confidence.LOW : Confidence.HIGH;
        return new Result(Posture.PHONE, c, true, diag, dpi);
    }

    /**
     * Mid-stream rule: only a switch between "touch policy applies" and "does not apply"
     * is worth telling the host about. Phone to tablet and similar changes are not.
     */
    public static boolean crossesBoundary(Result previous, Result current) {
        boolean before = previous != null && previous.isTouchClass();
        boolean after = current != null && current.isTouchClass();
        return before != after;
    }

    public static boolean isKnownHandheldManufacturer(String manufacturer) {
        if (manufacturer == null) {
            return false;
        }
        String m = manufacturer.trim().toLowerCase(Locale.ROOT);
        for (String h : HANDHELD_MANUFACTURERS) {
            if (m.equals(h)) {
                return true;
            }
        }
        return false;
    }

    /** Diagonal in mm from pixel size and per-axis density; 0 if any input is unusable. */
    public static double diagonalMmFromPixels(int widthPx, int heightPx, float xdpi, float ydpi) {
        if (widthPx <= 0 || heightPx <= 0 || !(xdpi > 0) || !(ydpi > 0)) {
            return 0;
        }
        double w = widthPx / (double) xdpi;
        double h = heightPx / (double) ydpi;
        return Math.sqrt(w * w + h * h) * MM_PER_INCH;
    }

    static int validDiagonalMm(double mm) {
        long rounded = Math.round(mm);
        return (rounded >= MIN_DIAGONAL_MM && rounded <= MAX_DIAGONAL_MM) ? (int) rounded : 0;
    }

    static int validDpi(int dpi) {
        return (dpi >= MIN_DPI && dpi <= MAX_DPI) ? dpi : 0;
    }
}
