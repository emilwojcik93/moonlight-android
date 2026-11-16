package com.limelight.posture;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.InputDevice;

/**
 * Reads the Android signals and hands them to {@link PostureClassifier}.
 *
 * Jetpack WindowManager fold posture is intentionally not used: androidx.window is not a
 * dependency of this project and a fold does not change the class sent to the host.
 * Screen size and density are not sent (contract v2); only the touch/keyboard state is.
 */
public final class PostureSignals {
    private PostureSignals() {
    }

    /**
     * @param display the display the stream is shown on, may be null (treated as the default display)
     * @return the classification, or null when the override is Never
     */
    @SuppressWarnings("deprecation")
    public static PostureClassifier.Result classify(Context context, Display display,
                                                    PostureClassifier.UserOverride override) {
        PostureClassifier.Inputs in = new PostureClassifier.Inputs();
        in.override = override;
        if (override == PostureClassifier.UserOverride.NEVER) {
            return null;
        }

        UiModeManager uiMode = (UiModeManager) context.getSystemService(Context.UI_MODE_SERVICE);
        if (uiMode != null) {
            int type = uiMode.getCurrentModeType();
            in.nonHandheldUiMode = type != Configuration.UI_MODE_TYPE_NORMAL
                    && type != Configuration.UI_MODE_TYPE_UNDEFINED;
        }

        Configuration config = context.getResources().getConfiguration();
        in.hardKeyboardAttached = config.keyboard != Configuration.KEYBOARD_NOKEYS
                && config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;

        in.hasTouchscreen = context.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);

        in.alphabeticKeyboardDevice = hasPhysicalAlphabeticKeyboard();

        if (display != null) {
            in.externalDisplay = display.getDisplayId() != Display.DEFAULT_DISPLAY;

            // Only used for the phone/tablet label; the short side does not change on rotation.
            DisplayMetrics dm = new DisplayMetrics();
            display.getRealMetrics(dm);
            if (dm.densityDpi > 0) {
                int shortSidePx = Math.min(dm.widthPixels, dm.heightPixels);
                in.smallestWidthDp = Math.round(shortSidePx * 160f / dm.densityDpi);
            }
        }
        if (in.smallestWidthDp <= 0) {
            in.smallestWidthDp = config.smallestScreenWidthDp;
        }

        return PostureClassifier.classify(in);
    }

    /** A Bluetooth/USB keyboard counts; the on-screen keyboard (virtual) and keypads/gamepads do not. */
    public static boolean hasPhysicalAlphabeticKeyboard() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice dev = InputDevice.getDevice(id);
            if (dev == null || dev.isVirtual()) {
                continue;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !dev.isEnabled()) {
                continue;
            }
            if ((dev.getSources() & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
                    && dev.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                return true;
            }
        }
        return false;
    }
}
