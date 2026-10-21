package eu.nurkert.neverUp2Late.gui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PluginSummaryTest {

    @Test
    void countsEachStatus() {
        PluginSummary summary = PluginSummary.of(List.of(
                PluginStatus.UP_TO_DATE, PluginStatus.UPDATE_READY, PluginStatus.UPDATE_READY,
                PluginStatus.FAILING, PluginStatus.NOT_LINKED));

        assertEquals(5, summary.total());
        assertEquals(2, summary.updatesReady());
        assertEquals(1, summary.failing());
        assertEquals(1, summary.notLinked());
        assertEquals("5 plugins · 2 updates ready · 1 error", summary.headline());
    }

    @Test
    void theHeadlineSpeaksInTheSingularForOne() {
        PluginSummary summary = PluginSummary.of(List.of(PluginStatus.UPDATE_READY));

        assertEquals("1 plugin · 1 update ready · 0 errors", summary.headline());
    }

    @Test
    void theFrameIsGreenWhenNothingNeedsAttention() {
        assertEquals(FrameState.UP_TO_DATE, PluginSummary.of(List.of()).frameState());
        // Not linking a plugin is a choice, not a fault.
        assertEquals(FrameState.UP_TO_DATE,
                PluginSummary.of(List.of(PluginStatus.UP_TO_DATE, PluginStatus.NOT_LINKED)).frameState());
    }

    @Test
    void theFrameIsYellowWhileUpdatesWait() {
        assertEquals(FrameState.UPDATES_WAITING,
                PluginSummary.of(List.of(PluginStatus.UP_TO_DATE, PluginStatus.UPDATE_READY)).frameState());
    }

    @Test
    void aFailingSourceTurnsTheFrameRedEvenWithUpdatesWaiting() {
        assertEquals(FrameState.FAILING,
                PluginSummary.of(List.of(PluginStatus.UPDATE_READY, PluginStatus.FAILING)).frameState());
    }

    @Test
    void aSinglePluginsFrameFollowsItsStatus() {
        assertEquals(FrameState.FAILING, FrameState.of(PluginStatus.FAILING));
        assertEquals(FrameState.UPDATES_WAITING, FrameState.of(PluginStatus.UPDATE_READY));
        assertEquals(FrameState.UP_TO_DATE, FrameState.of(PluginStatus.UP_TO_DATE));
        assertEquals(FrameState.UP_TO_DATE, FrameState.of(PluginStatus.NOT_LINKED));
    }

    @Test
    void aPluginStatusPutsAFailingSourceAboveAWaitingUpdate() {
        assertEquals(PluginStatus.NOT_LINKED, PluginStatus.of(false, true, true));
        assertEquals(PluginStatus.FAILING, PluginStatus.of(true, true, true));
        assertEquals(PluginStatus.UPDATE_READY, PluginStatus.of(true, false, true));
        assertEquals(PluginStatus.UP_TO_DATE, PluginStatus.of(true, false, false));
    }
}
