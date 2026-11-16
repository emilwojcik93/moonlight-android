package com.limelight.posture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.limelight.posture.PostureClassifier.Confidence;
import com.limelight.posture.PostureClassifier.Inputs;
import com.limelight.posture.PostureClassifier.UserOverride;
import com.limelight.posture.PostureClassifier.Posture;
import com.limelight.posture.PostureClassifier.Result;

import org.junit.Test;

public class PostureClassifierTest {
    private static Inputs touchDevice(int swDp) {
        Inputs in = new Inputs();
        in.hasTouchscreen = true;
        in.smallestWidthDp = swDp;
        return in;
    }

    @Test
    public void phone() {
        Inputs in = touchDevice(411);
        in.diagonalMm = 160.4;
        in.dpi = 420;
        Result r = PostureClassifier.classify(in);
        assertEquals(Posture.PHONE, r.posture);
        assertEquals(Confidence.HIGH, r.confidence);
        assertTrue(r.touch);
        assertEquals("clientPosture=phone&clientTouch=1&clientDiagonalMm=160&clientDpi=420", r.toLaunchQuery());
    }

    @Test
    public void tabletBySmallestWidth() {
        Result r = PostureClassifier.classify(touchDevice(600));
        assertEquals(Posture.TABLET, r.posture);
        assertEquals(Confidence.HIGH, r.confidence);
        assertEquals("clientPosture=tablet&clientTouch=1", r.toLaunchQuery());
    }

    @Test
    public void justBelowTabletThresholdIsPhone() {
        assertEquals(Posture.PHONE, PostureClassifier.classify(touchDevice(599)).posture);
    }

    @Test
    public void tabletWithKeyboardIsLowConfidenceLaptop() {
        Inputs in = touchDevice(800);
        in.hardKeyboardAttached = true;
        Result r = PostureClassifier.classify(in);
        assertEquals(Posture.LAPTOP, r.posture);
        assertEquals(Confidence.LOW, r.confidence);
        assertFalse(r.isTouchClass());
    }

    @Test
    public void phoneWithKeyboardStaysPhoneLowConfidence() {
        Inputs in = touchDevice(400);
        in.hardKeyboardAttached = true;
        Result r = PostureClassifier.classify(in);
        assertEquals(Posture.PHONE, r.posture);
        assertEquals(Confidence.LOW, r.confidence);
    }

    @Test
    public void unknownWidthIsLowConfidencePhone() {
        Result r = PostureClassifier.classify(touchDevice(0));
        assertEquals(Posture.PHONE, r.posture);
        assertEquals(Confidence.LOW, r.confidence);
    }

    @Test
    public void handheldBeatsSizeClass() {
        Inputs in = touchDevice(640);
        in.knownHandheld = true;
        assertEquals(Posture.HANDHELD, PostureClassifier.classify(in).posture);
    }

    @Test
    public void tvCarDeskUiModeIsDesktopAndSendsNothing() {
        Inputs in = touchDevice(960);
        in.nonHandheldUiMode = true;
        Result r = PostureClassifier.classify(in);
        assertEquals(Posture.DESKTOP, r.posture);
        assertFalse(r.touch);
        assertEquals("", r.toLaunchQuery());
        assertEquals("clientPosture=desktop&clientTouch=0", r.toQuery());
    }

    @Test
    public void noTouchscreenIsDesktop() {
        Inputs in = new Inputs();
        in.smallestWidthDp = 1000;
        assertEquals(Posture.DESKTOP, PostureClassifier.classify(in).posture);
    }

    @Test
    public void externalDisplayIsDesktop() {
        Inputs in = touchDevice(411);
        in.externalDisplay = true;
        assertEquals(Posture.DESKTOP, PostureClassifier.classify(in).posture);
    }

    @Test
    public void neverSendsNothing() {
        Inputs in = touchDevice(800);
        in.override = UserOverride.NEVER;
        assertNull(PostureClassifier.classify(in));
    }

    @Test
    public void alwaysTabletWinsEvenOnTvOrNoTouch() {
        Inputs in = new Inputs();
        in.override = UserOverride.ALWAYS_TABLET;
        in.nonHandheldUiMode = true;
        Result r = PostureClassifier.classify(in);
        assertEquals(Posture.TABLET, r.posture);
        assertTrue(r.touch);
        assertEquals(Confidence.HIGH, r.confidence);
    }

    @Test
    public void overridePreferenceParsing() {
        assertEquals(UserOverride.AUTO, UserOverride.fromPreference("auto"));
        assertEquals(UserOverride.AUTO, UserOverride.fromPreference(null));
        assertEquals(UserOverride.AUTO, UserOverride.fromPreference("garbage"));
        assertEquals(UserOverride.ALWAYS_TABLET, UserOverride.fromPreference("tablet"));
        assertEquals(UserOverride.NEVER, UserOverride.fromPreference("never"));
    }

    @Test
    public void outOfRangeValuesAreOmitted() {
        Inputs in = touchDevice(411);
        in.diagonalMm = 49.4;   // < 50
        in.dpi = 71;            // < 72
        assertEquals("clientPosture=phone&clientTouch=1", PostureClassifier.classify(in).toQuery());

        in.diagonalMm = 800.6;  // rounds to 801 > 800
        in.dpi = 801;
        assertEquals("clientPosture=phone&clientTouch=1", PostureClassifier.classify(in).toQuery());

        in.diagonalMm = 50;
        in.dpi = 72;
        assertEquals("clientPosture=phone&clientTouch=1&clientDiagonalMm=50&clientDpi=72",
                PostureClassifier.classify(in).toQuery());

        in.diagonalMm = 800;
        in.dpi = 800;
        assertEquals("clientPosture=phone&clientTouch=1&clientDiagonalMm=800&clientDpi=800",
                PostureClassifier.classify(in).toQuery());
    }

    @Test
    public void nanAndNegativeDiagonalOmitted() {
        Inputs in = touchDevice(411);
        in.diagonalMm = Double.NaN;
        assertEquals("clientPosture=phone&clientTouch=1", PostureClassifier.classify(in).toQuery());
        in.diagonalMm = -5;
        assertEquals("clientPosture=phone&clientTouch=1", PostureClassifier.classify(in).toQuery());
    }

    @Test
    public void boundaryOnlyOnNoneSomeChange() {
        Result phone = PostureClassifier.classify(touchDevice(400));
        Result tablet = PostureClassifier.classify(touchDevice(700));
        Inputs kb = touchDevice(700);
        kb.hardKeyboardAttached = true;
        Result laptop = PostureClassifier.classify(kb);
        Inputs tv = touchDevice(700);
        tv.nonHandheldUiMode = true;
        Result desktop = PostureClassifier.classify(tv);

        assertFalse(PostureClassifier.crossesBoundary(phone, tablet));
        assertFalse(PostureClassifier.crossesBoundary(laptop, desktop));
        assertTrue(PostureClassifier.crossesBoundary(laptop, tablet));
        assertTrue(PostureClassifier.crossesBoundary(tablet, laptop));
        assertTrue(PostureClassifier.crossesBoundary(phone, desktop));
        assertFalse(PostureClassifier.crossesBoundary(null, desktop));
        assertTrue(PostureClassifier.crossesBoundary(null, tablet));
    }

    @Test
    public void diagonalFromPixels() {
        // 1920x1200 at 224 dpi both axes ~ 10.1 in ~ 257 mm
        double mm = PostureClassifier.diagonalMmFromPixels(1920, 1200, 224f, 224f);
        assertEquals(257.0, mm, 2.0);
        assertEquals(0.0, PostureClassifier.diagonalMmFromPixels(1920, 1200, 0f, 224f), 0.0);
        assertEquals(0.0, PostureClassifier.diagonalMmFromPixels(0, 1200, 224f, 224f), 0.0);
    }

    @Test
    public void knownHandheldManufacturers() {
        assertTrue(PostureClassifier.isKnownHandheldManufacturer("AYANEO"));
        assertTrue(PostureClassifier.isKnownHandheldManufacturer(" Retroid "));
        assertFalse(PostureClassifier.isKnownHandheldManufacturer("samsung"));
        assertFalse(PostureClassifier.isKnownHandheldManufacturer(null));
    }
}
