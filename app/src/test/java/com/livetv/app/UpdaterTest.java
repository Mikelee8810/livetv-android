package com.livetv.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.livetv.app.data.Updater;

import org.junit.Test;

/** Version comparison for the GitHub-release updater. */
public class UpdaterTest {

    @Test
    public void newerPatchIsNewer() {
        assertTrue(Updater.INSTANCE.isNewer("4.2.2", "4.2.1"));
    }

    @Test
    public void sameVersionIsNotNewer() {
        assertFalse(Updater.INSTANCE.isNewer("4.2.2", "4.2.2"));
    }

    @Test
    public void olderIsNotNewer() {
        assertFalse(Updater.INSTANCE.isNewer("4.2.1", "4.2.2"));
    }

    @Test
    public void tagPrefixIsIgnored() {
        assertTrue(Updater.INSTANCE.isNewer("v4.3.0", "4.2.9"));
        assertFalse(Updater.INSTANCE.isNewer("v4.2.2", "4.2.2"));
    }

    @Test
    public void minorBeatsPatch() {
        assertTrue(Updater.INSTANCE.isNewer("4.3.0", "4.2.99"));
    }
}
