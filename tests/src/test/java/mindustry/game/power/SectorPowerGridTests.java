package mindustry.game.power;

import mindustry.core.*;
import mindustry.game.power.SectorPowerGridMath.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-Java JUnit 5 unit tests for V2.5 Cross-Sector Power Grid ("跨区块电力系统"):
 * version tag, adjacent-only hub linking & relay chaining, auto-disconnect & graph
 * splitting on hub destruction, unlimited-throughput surplus sharing, cross-sector
 * shared battery charge/discharge, proportional overload brownout, and binary sync.
 */
public class SectorPowerGridTests{

    @Test
    void versionForkTagIsCurrent(){
        assertEquals("v3.1", Version.forkVersion);
    }

    @Test
    void onlyAdjacentHubSectorsCanLinkAndRelayChainingFormsConnectedGrid(){
        PlanetPowerTopology topo = new PlanetPowerTopology();
        topo.setHubActive(1, true);
        topo.setHubActive(2, true);
        topo.setHubActive(3, true);
        // Sector 4 has NO hub

        // Non-adjacent link rejected
        assertFalse(topo.canLink(1, 3, false));
        assertEquals(0, topo.toggleLink(1, 3, false));

        // Adjacent without hub rejected
        assertFalse(topo.canLink(3, 4, true));
        assertEquals(0, topo.toggleLink(3, 4, true));

        // Adjacent with hubs succeeds: 1 -- 2 -- 3
        assertEquals(1, topo.toggleLink(1, 2, true));
        assertEquals(1, topo.toggleLink(2, 3, true));
        assertTrue(topo.areLinked(1, 2));
        assertTrue(topo.areLinked(2, 3));

        // Connected component includes all 3 sectors via relay sector 2
        int[] comp = topo.getConnectedComponent(1);
        assertArrayEquals(new int[]{1, 2, 3}, comp);

        // Toggling an existing link disconnects cleanly
        assertEquals(-1, topo.toggleLink(1, 2, true));
        assertFalse(topo.areLinked(1, 2));
        assertArrayEquals(new int[]{1}, topo.getConnectedComponent(1));
        assertArrayEquals(new int[]{2, 3}, topo.getConnectedComponent(3));
    }

    @Test
    void hubDestructionAutomaticallySeversLinksAndSplitsSubnets(){
        PlanetPowerTopology topo = new PlanetPowerTopology();
        topo.setHubActive(10, true);
        topo.setHubActive(11, true);
        topo.setHubActive(12, true);
        topo.setHubActive(13, true);

        // Chain: 10 -- 11 -- 12 -- 13
        assertTrue(topo.addLink(10, 11, true));
        assertTrue(topo.addLink(11, 12, true));
        assertTrue(topo.addLink(12, 13, true));
        assertArrayEquals(new int[]{10, 11, 12, 13}, topo.getConnectedComponent(10));

        // Destroy hub in sector 11 -> auto-severs 10-11 and 11-12; {10} and {12, 13} remain valid subnets
        assertTrue(topo.setHubActive(11, false));
        assertFalse(topo.hasHub(11));
        assertFalse(topo.areLinked(10, 11));
        assertFalse(topo.areLinked(11, 12));
        assertTrue(topo.areLinked(12, 13));

        assertArrayEquals(new int[]{10}, topo.getConnectedComponent(10));
        assertArrayEquals(new int[0], topo.getConnectedComponent(11));
        assertArrayEquals(new int[]{12, 13}, topo.getConnectedComponent(12));
    }

    @Test
    void unlimitedCrossSectorSurplusAndSharedBatteriesPowerDeficitSector(){
        PlanetPowerTopology topo = new PlanetPowerTopology();
        // Sector 1: Power Plant (+200/tick produced, 20/tick needed, 10000/20000 battery)
        topo.updateSectorTelemetry(1, 200f, 20f, 10000f, 20000f, 100L);
        // Sector 2: Frontline Fortress (0/tick local produced, 150/tick needed, 0 local battery)
        topo.updateSectorTelemetry(2, 0f, 150f, 0f, 0f, 100L);
        assertTrue(topo.addLink(1, 2, true));

        // Active tick in Sector 2 (dt = 1.0): net grid = 200 - (20 + 150) = +30/tick surplus!
        PowerStepResult step = topo.resolveActiveSectorTick(2, 0f, 150f, 0f, 0f, 1f, 200L);
        assertEquals(1f, step.coverage, 1e-4f);
        assertTrue(step.charged);
        assertEquals(0f, step.localBatteryDelta, 1e-4f);
        assertEquals(30f, step.remoteBatteryDelta, 1e-3f);
        assertEquals(10030f, topo.getNode(1).batteryStored, 1e-2f);
        assertEquals(2, step.connectedSectors);

        // Now spike Sector 2 demand to 250/tick (total need = 270 > 200 produced; deficit = 70 drawn from Sector 1's shared battery!)
        PowerStepResult deficitStep = topo.resolveActiveSectorTick(2, 0f, 250f, 0f, 0f, 1f, 300L);
        assertEquals(1f, deficitStep.coverage, 1e-4f);
        assertFalse(deficitStep.charged);
        assertEquals(-70f, deficitStep.remoteBatteryDelta, 1e-3f);
        assertEquals(9960f, topo.getNode(1).batteryStored, 1e-2f);
    }

    @Test
    void overloadWithEmptySharedBatteriesScalesSatisfactionProportionally(){
        PlanetPowerTopology topo = new PlanetPowerTopology();
        // Sector 1 produces 100/tick, needs 0, battery empty
        topo.updateSectorTelemetry(1, 100f, 0f, 0f, 5000f, 100L);
        // Sector 2 produces 0, needs 200/tick, battery empty
        topo.updateSectorTelemetry(2, 0f, 200f, 0f, 0f, 100L);
        assertTrue(topo.addLink(1, 2, true));

        PowerStepResult step = topo.resolveActiveSectorTick(2, 0f, 200f, 0f, 0f, 1f, 200L);
        // Total produced = 100, total needed = 200 -> exact 50% proportional brownout
        assertEquals(0.5f, step.coverage, 1e-4f);

        GridAggregate agg = topo.computeAggregate(2);
        assertTrue(agg.overloaded);
        assertEquals(0.5f, agg.satisfaction, 1e-4f);
        assertEquals(100f, agg.totalProduced, 1e-4f);
        assertEquals(200f, agg.totalNeeded, 1e-4f);
    }

    @Test
    void backgroundStepChargesUnvisitedGridWithoutTouchingActiveSimulatedGrid(){
        PlanetPowerTopology topo = new PlanetPowerTopology();
        // Background grid: 1 -- 2 (+10/tick net, 0/10000 battery)
        topo.updateSectorTelemetry(1, 15f, 5f, 0f, 10000f, 0L);
        topo.updateSectorTelemetry(2, 0f, 0f, 0f, 0f, 0L);
        assertTrue(topo.addLink(1, 2, true));

        // Active simulated grid: 5 (+10/tick net, 0/10000 battery)
        topo.updateSectorTelemetry(5, 10f, 0f, 0f, 10000f, 0L);

        Set<Integer> active = new HashSet<>();
        active.add(5);
        topo.stepBackgroundComponents(active, 1.0f); // 1 second = 60 ticks -> +600 energy

        assertEquals(600f, topo.getNode(1).batteryStored, 1e-2f);
        assertEquals(0f, topo.getNode(5).batteryStored, 1e-2f);
    }

    @Test
    void binarySerializationRoundtripPreservesTopologyAndTelemetry() throws IOException{
        PlanetPowerTopology original = new PlanetPowerTopology();
        original.updateSectorTelemetry(3, 120f, 40f, 5500f, 10000f, 0L);
        original.updateSectorTelemetry(7, 30f, 90f, 2500f, 5000f, 0L);
        assertTrue(original.addLink(3, 7, true));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try(DataOutputStream out = new DataOutputStream(baos)){
            original.writeToStream(out);
        }

        PlanetPowerTopology restored = new PlanetPowerTopology();
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()))){
            restored.readFromStream(in);
        }

        assertTrue(restored.hasHub(3));
        assertTrue(restored.hasHub(7));
        assertTrue(restored.areLinked(3, 7));

        GridAggregate agg = restored.computeAggregate(3);
        assertEquals(2, agg.sectorCount());
        assertEquals(150f, agg.totalProduced, 1e-4f);
        assertEquals(130f, agg.totalNeeded, 1e-4f);
        assertEquals(8000f, agg.totalBatteryStored, 1e-4f);
        assertEquals(15000f, agg.totalBatteryCapacity, 1e-4f);
    }

    @Test
    void hubAnimationLifecycleTransitionsStartupRunningShutdownSmoothly(){
        HubAnimationController ctrl = new HubAnimationController();
        assertEquals(HubAnimState.OFFLINE, ctrl.state);
        assertEquals(0f, ctrl.warmup, 1e-4f);

        // Engage hub -> enters STARTING
        assertTrue(ctrl.step(true, 1f, 10f, 8f, 10f));
        assertEquals(HubAnimState.STARTING, ctrl.state);

        // Halfway through startup (5 ticks / 10 ticks = 0.5)
        assertFalse(ctrl.step(true, 5f, 10f, 8f, 10f));
        assertEquals(HubAnimState.STARTING, ctrl.state);
        assertEquals(0.5f, ctrl.phaseProgress, 1e-4f);
        assertEquals(0.5f, ctrl.warmup, 1e-4f);

        // Finish startup -> enters RUNNING
        assertTrue(ctrl.step(true, 5f, 10f, 8f, 10f));
        assertEquals(HubAnimState.RUNNING, ctrl.state);
        assertEquals(1f, ctrl.warmup, 1e-4f);

        // Running loop wraps cleanly across runCycleDuration = 8
        assertFalse(ctrl.step(true, 2f, 10f, 8f, 10f));
        assertEquals(0.25f, ctrl.runCycleProgress, 1e-4f);
        assertFalse(ctrl.step(true, 8f, 10f, 8f, 10f));
        assertEquals(0.25f, ctrl.runCycleProgress, 1e-4f);

        // Disengage hub -> enters STOPPING
        assertTrue(ctrl.step(false, 1f, 10f, 8f, 10f));
        assertEquals(HubAnimState.STOPPING, ctrl.state);

        // Halfway through shutdown (5 ticks / 10 ticks -> phaseProgress 0.5, warmup 0.5)
        assertFalse(ctrl.step(false, 5f, 10f, 8f, 10f));
        assertEquals(0.5f, ctrl.phaseProgress, 1e-4f);
        assertEquals(0.5f, ctrl.warmup, 1e-4f);

        // Finish shutdown -> returns to OFFLINE
        assertTrue(ctrl.step(false, 5f, 10f, 8f, 10f));
        assertEquals(HubAnimState.OFFLINE, ctrl.state);
        assertEquals(0f, ctrl.warmup, 1e-4f);
    }

    @Test
    void hubAnimationMidTransitionReversalPreservesWarmupAndFrameIndices(){
        HubAnimationController ctrl = new HubAnimationController();
        ctrl.step(true, 0f, 10f, 8f, 10f); // OFFLINE -> STARTING
        ctrl.step(true, 6f, 10f, 8f, 10f); // warmup = 0.6

        // Abort mid-startup -> seamlessly enters STOPPING at mirrored progress 0.4 (warmup stays 0.6)
        assertTrue(ctrl.step(false, 0f, 10f, 8f, 10f));
        assertEquals(HubAnimState.STOPPING, ctrl.state);
        assertEquals(0.4f, ctrl.phaseProgress, 1e-4f);
        assertEquals(0.6f, ctrl.warmup, 1e-4f);

        // Advance shutdown by 2 ticks -> phaseProgress = 0.6, warmup = 0.4
        ctrl.step(false, 2f, 10f, 8f, 10f);
        assertEquals(0.4f, ctrl.warmup, 1e-4f);

        // Re-engage mid-shutdown -> seamlessly resumes STARTING at progress 0.4
        assertTrue(ctrl.step(true, 0f, 10f, 8f, 10f));
        assertEquals(HubAnimState.STARTING, ctrl.state);
        assertEquals(0.4f, ctrl.phaseProgress, 1e-4f);

        // Forward & reverse frame index clamping and symmetry
        assertEquals(0, HubAnimationController.frameIndex(4, 0f));
        assertEquals(1, HubAnimationController.frameIndex(4, 0.25f));
        assertEquals(3, HubAnimationController.frameIndex(4, 1f));
        assertEquals(3, HubAnimationController.reverseFrameIndex(4, 0f));
        assertEquals(2, HubAnimationController.reverseFrameIndex(4, 0.25f));
        assertEquals(0, HubAnimationController.reverseFrameIndex(4, 1f));
    }
}

