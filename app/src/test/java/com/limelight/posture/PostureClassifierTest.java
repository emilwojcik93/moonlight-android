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

    private static Inputs withKeyboardDevice(int swDp) {
        Inputs in = touchDevice(swDp);
        in.alphabeticKeyboardDevice = true;
        return in;
    }

    @Test
    public void phoneSendsOnlyPostureAndTouch() {
        Result r = PostureClassifier.classify(touchDevice(411));
        assertEquals(Posture.PHONE, r.posture);
        assertEquals(Confidence.HIGH, r.confidence);
        assertTrue(r.touch);
        assertEquals("clientPosture=phone&clientTouch=1", r.toLaunchQuery());
    }

    @Test
    public void tabletLabelBySmallestWidthIsOnlyALabel() {
        Result r = PostureClassifier.classify(touchDevice(600));
        assertEquals(Posture.TABLET, r.posture);
        assertEquals("clientPosture=tablet&clientTouch=1", r.toLaunchQuery());
        assertEquals(Posture.PHONE, PostureClassifier.classify(touchDevice(599)).posture);
        // both labels are touch-primary: switching between them is never a boundary
        assertFalse(PostureClassifier.crossesBoundary(
                PostureClassifier.classify(touchDevice(599)), r));
    }

    @Test
    public void physicalKeyboardDeviceMeansLaptopOnPhoneAndTablet() {
        for (int sw : new int[]{360, 411, 800}) {
            Result r = PostureClassifier.classify(withKeyboardDevice(sw));
            assertEquals(Posture.LAPTOP, r.posture);
            assertEquals(Confidence.HIGH, r.confidence);
            assertTrue(r.touch);
            assertFalse(r.isTouchClass());
            assertEquals("clientPosture=laptop&clientTouch=1", r.toLaunchQuery());
        }
    }

    @Test
    public void configurationKeyboardHintAloneIsLowConfidenceLaptop() {
        Inputs in = touchDevice(400);
        in.hardKeyboardAttached = true;
        Result r = PostureClassifier.classify(in);
        assertEquals(Posture.LAPTOP, r.posture);
        assertEquals(Confidence.LOW, r.confidence);
    }

    @Test
    public void noKeyboardIsTouchPrimary() {
        Result r = PostureClassifier.classify(touchDevice(800));
        assertTrue(r.touch);
        assertTrue(r.isTouchClass());
    }

    @Test
    public void unknownWidthIsLowConfidencePhone() {
        Result r = PostureClassifier.classify(touchDevice(0));
        assertEquals(Posture.PHONE, r.posture);
        assertEquals(Confidence.LOW, r.confidence);
    }

    @Test
    public void queryNeverCarriesSizeOrDensity() {
        for (int sw : new int[]{0, 320, 411, 600, 1000}) {
            String q = PostureClassifier.classify(touchDevice(sw)).toQuery();
            assertFalse(q, q.contains("Diagonal"));
            assertFalse(q, q.contains("Dpi"));
        }
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
    public void tvWithKeyboardStaysDesktop() {
        Inputs in = withKeyboardDevice(960);
        in.nonHandheldUiMode = true;
        assertEquals(Posture.DESKTOP, PostureClassifier.classify(in).posture);
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
    public void alwaysTabletWinsEvenOnTvOrWithKeyboard() {
        Inputs in = withKeyboardDevice(0);
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
    public void boundaryOnlyWhenKeyboardStateFlips() {
        Result phone = PostureClassifier.classify(touchDevice(400));
        Result tablet = PostureClassifier.classify(touchDevice(700));
        Result laptop = PostureClassifier.classify(withKeyboardDevice(700));
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
    public void rotationAloneNeverFlipsState() {
        // Rotation swaps width and height but the smallest width (the only size input, used for
        // the label) is the same, and no keyboard signal changes.
        Result portrait = PostureClassifier.classify(touchDevice(411));
        Result landscape = PostureClassifier.classify(touchDevice(411));
        assertEquals(portrait.toQuery(), landscape.toQuery());
        assertFalse(PostureClassifier.crossesBoundary(portrait, landscape));
    }

    @Test
    public void keyboardFlipSequence() {
        // attach, detach, attach: each step is a boundary crossing
        Result t0 = PostureClassifier.classify(touchDevice(411));
        Result k1 = PostureClassifier.classify(withKeyboardDevice(411));
        Result t2 = PostureClassifier.classify(touchDevice(411));
        Result k3 = PostureClassifier.classify(withKeyboardDevice(411));
        assertTrue(PostureClassifier.crossesBoundary(t0, k1));
        assertTrue(PostureClassifier.crossesBoundary(k1, t2));
        assertTrue(PostureClassifier.crossesBoundary(t2, k3));
        assertFalse(PostureClassifier.crossesBoundary(k1, k3));
    }
}
