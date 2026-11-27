package mindustry.game.strategy;

import mindustry.core.*;
import mindustry.game.logistics.SectorConveyorMath;
import mindustry.game.logistics.SectorConveyorMath.*;
import mindustry.game.power.SectorPowerGridMath.*;
import mindustry.game.strategy.core.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-Java JUnit 5 unit tests for the unified real-time multi-sector settlement engine
 * and its cross-sector extension channels (V2.5 Power Grid, V2.8 Conveyors, and future devices).
 */
public class RealTimeSettlementTests{

    @Test
    void versionForkTagIsV32(){
        assertEquals("v3.9", Version.forkVersion);
    }

    @Test
    void externalPowerSatisfactionRunsZeroGeneratorIndustrialSectorAtGridSatisfaction(){
        StrategySimulationEngine engine = new StrategySimulationEngine();

        SectorStrategyProfile factory = new SectorStrategyProfile(10);
        factory.hasBase = true;
        factory.storageCapacity = 4000;
        factory.setStored("coal", 500f);
        factory.setStored("sand", 500f);

        ProductionNode smelter = new ProductionNode("silicon-smelter")
            .addInput("coal", 1.5f)
            .addInput("sand", 3.0f)
            .addOutput("silicon", 1.5f)
            .withPower(0f, 30f);
        factory.nodes.add(smelter);
        engine.putSector(factory);

        // Without external cross-sector power, local power is 0/30 -> sector stalls
        factory.clearExternalStepChannels();
        engine.step(2f);
        assertEquals(0f, factory.solvedPowerSatisfaction, 1e-4f);
        assertEquals(0f, factory.getStored("silicon"), 1e-3f);

        // With V2.5 Cross-Sector Power Grid providing 100% pooled satisfaction
        factory.externalPowerSatisfaction = 1.0f;
        engine.step(2f);
        assertEquals(1.0f, factory.solvedPowerSatisfaction, 1e-4f);
        assertEquals(3.0f, factory.getStored("silicon"), 0.05f);

        // Under 50% inter-sector grid brownout, production scales proportionally
        factory.externalPowerSatisfaction = 0.5f;
        engine.step(2f);
        assertEquals(0.5f, factory.solvedPowerSatisfaction, 1e-4f);
        assertEquals(4.5f, factory.getStored("silicon"), 0.05f);
    }

    @Test
    void externalExportDemandsPreventFullCoreBackpressureStall(){
        StrategySimulationEngine engine = new StrategySimulationEngine();

        SectorStrategyProfile producer = new SectorStrategyProfile(20);
        producer.hasBase = true;
        producer.storageCapacity = 1000;
        producer.setStored("copper", 1000f); // core completely full

        ProductionNode drill = new ProductionNode("mechanical-drill")
            .addOutput("copper", 4.0f);
        producer.nodes.add(drill);
        engine.putSector(producer);

        // Without external export demand, storage backpressure throttles full-core drill to 0
        producer.clearExternalStepChannels();
        engine.step(1f);
        assertEquals(0f, producer.solvedGrossProduction.getOrDefault("copper", 0f), 1e-3f);

        // When Cross-Sector Conveyor (or future device) declares 2.0/s external export demand,
        // drill smoothly runs at 2.0/s instead of stalling
        producer.externalExportDemands.put("copper", 2.0f);
        engine.step(1f);
        assertEquals(2.0f, producer.solvedGrossProduction.getOrDefault("copper", 0f), 0.05f);
    }

    @Test
    void conveyorFeedPatternMixPowerScalingFreezeAndStreamV2Roundtrip() throws IOException{
        PlanetConveyorTopology topo = new PlanetConveyorTopology();
        SectorPortRecord sender = topo.registerPort(1, 1, true, SectorConveyorMath.TIER_SENIOR);
        SectorPortRecord receiver = topo.registerPort(2, 1, false, SectorConveyorMath.TIER_SENIOR);
        assertTrue(topo.bind(1, 1, 2, 1, true));

        // Mixed 1:1 feed pattern (item 0 and item 1)
        sender.recordAcceptedItem(0);
        sender.recordAcceptedItem(1);
        assertEquals(0, sender.nextFeedItemId());
        assertEquals(1, sender.nextFeedItemId());
        assertEquals(0, sender.nextFeedItemId());

        // Preload 8 items in sender FIFO
        for(int i = 0; i < 8; i++){
            assertTrue(sender.enqueue(i % 2));
        }

        // Senior sender under 50% power satisfaction transfers 2/s instead of 4/s
        Map<Integer, Float> powerMap = new HashMap<>();
        powerMap.put(1, 0.5f);
        topo.stepBackgroundLinks(Collections.emptySet(), powerMap, Collections.emptySet(), 1f);
        assertEquals(2, receiver.bufferedCount());
        assertEquals(6, sender.bufferedCount());

        // Frozen (under-attack) sector pauses transfer without losing any buffered items
        Set<Integer> frozen = new HashSet<>();
        frozen.add(1);
        topo.stepBackgroundLinks(Collections.emptySet(), powerMap, frozen, 2f);
        assertEquals(2, receiver.bufferedCount());
        assertEquals(6, sender.bufferedCount());

        // Stream V2 roundtrip preserves backgroundFeedPattern alongside FIFO and links
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try(DataOutputStream out = new DataOutputStream(baos)){
            topo.writeToStream(out);
        }
        PlanetConveyorTopology restored = new PlanetConveyorTopology();
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()))){
            restored.readFromStream(in);
        }
        SectorPortRecord restoredSender = restored.getPort(1, true, 1);
        assertNotNull(restoredSender);
        assertEquals(Arrays.asList(0, 1), restoredSender.backgroundFeedPattern);
        assertEquals(6, restoredSender.bufferedCount());
        assertNotNull(restored.getLinkForSender(1, 1));
    }

    @Test
    void continuousExternalImportRateFeedsDownstreamFactoryInBackground(){
        StrategySimulationEngine engine = new StrategySimulationEngine();

        SectorStrategyProfile consumer = new SectorStrategyProfile(30);
        consumer.hasBase = true;
        consumer.storageCapacity = 2000;
        // Zero stored coal/sand locally; coal arrives via external continuous channel, sand is local
        consumer.setStored("sand", 200f);
        consumer.nodes.add(new ProductionNode("silicon-smelter")
            .addInput("coal", 2.0f)
            .addInput("sand", 4.0f)
            .addOutput("silicon", 2.0f));
        engine.putSector(consumer);

        consumer.externalImportRates.put("coal", 2.0f);
        engine.step(3f);

        assertEquals(6.0f, consumer.getStored("silicon"), 0.1f);
    }

    @Test
    void powerGridCacheInvalidatesAccuratelyAcross272Sectors(){
        PlanetPowerTopology topo = new PlanetPowerTopology();
        for(int i = 0; i < 272; i++){
            topo.updateSectorTelemetry(i, 10f, 5f, 100f, 200f, 1000L);
            if(i > 0){
                assertTrue(topo.addLink(i - 1, i, true));
            }
        }

        GridAggregate fullA = topo.computeAggregate(0);
        GridAggregate fullB = topo.computeAggregate(271);
        assertSame(fullA, fullB); // Cached component shares the same GridAggregate instance
        assertEquals(272, fullA.sectorCount());
        assertEquals(2720f, fullA.totalProduced, 1e-3f);

        // Sever link in the middle -> cache must invalidate and split cleanly into two 136-sector components
        assertTrue(topo.removeLink(135, 136));
        GridAggregate left = topo.computeAggregate(0);
        GridAggregate right = topo.computeAggregate(271);
        assertNotSame(left, right);
        assertEquals(136, left.sectorCount());
        assertEquals(136, right.sectorCount());

        // Telemetry update must invalidate aggregate cache without losing topology
        topo.updateSectorTelemetry(0, 0f, 5000f, 0f, 200f, 2000L);
        GridAggregate leftOverloaded = topo.computeAggregate(10);
        assertTrue(leftOverloaded.overloaded);
        assertTrue(leftOverloaded.satisfaction < 0.5f);
    }

    @Test
    void conveyorSectorBucketIndexAndTrackedSteppingStayConsistentAt272Sectors(){
        PlanetConveyorTopology topo = new PlanetConveyorTopology();
        for(int i = 0; i < 272; i++){
            assertNotNull(topo.registerPort(i, 2, false, SectorConveyorMath.TIER_JUNIOR));
            assertNotNull(topo.registerPort(i, 1, true, SectorConveyorMath.TIER_SENIOR));
            assertNotNull(topo.registerPort(i, 1, false, SectorConveyorMath.TIER_SENIOR));
        }

        assertTrue(topo.hasAnyPort(100));
        assertFalse(topo.hasAnyPort(999));
        assertEquals(1, topo.countSendersInSector(100));
        assertEquals(2, topo.countReceiversInSector(100));

        // Read-only bucket order: senders asc, then receivers asc
        List<SectorPortRecord> bucket = topo.getAllSectorPortsReadOnly(100);
        assertEquals(3, bucket.size());
        assertTrue(bucket.get(0).isSender && bucket.get(0).portNumber == 1);
        assertTrue(!bucket.get(1).isSender && bucket.get(1).portNumber == 1);
        assertTrue(!bucket.get(2).isSender && bucket.get(2).portNumber == 2);

        // Idle network reports no activity so UI revision and disk save stay quiet
        assertFalse(topo.stepBackgroundLinksTracked(Collections.emptySet(), null, null, 1f));

        // Active link reports activity when items transfer
        assertTrue(topo.bind(0, 1, 1, 1, true));
        topo.getPort(0, true, 1).enqueue(0);
        topo.getPort(0, true, 1).enqueue(0);
        assertTrue(topo.stepBackgroundLinksTracked(Collections.emptySet(), null, null, 1f));
        assertEquals(2, topo.getPort(1, false, 1).bufferedCount());

        // Unregistering all ports cleans up bucket
        assertTrue(topo.clearSector(100));
        assertFalse(topo.hasAnyPort(100));
        assertTrue(topo.getAllSectorPortsReadOnly(100).isEmpty());
    }

    @Test
    void strategyEngineHandles272SectorsWithScratchReuseAndBoundaryRefresh(){
        StrategySimulationEngine engine = new StrategySimulationEngine();
        for(int i = 0; i < 272; i++){
            SectorStrategyProfile p = new SectorStrategyProfile(i);
            p.hasBase = true;
            p.storageCapacity = 1000;
            // Stagger near-full capacities to stress boundary transitions without substep avalanche
            p.setStored("copper", i < 20 ? 999.0f : 500.0f);
            p.nodes.add(new ProductionNode("mechanical-drill").addOutput("copper", 2.0f));
            engine.putSector(p);
        }

        engine.step(1f);
        assertEquals(1.0, engine.getElapsedSeconds(), 1e-4);
        // Near-full sector reaches exact capacity and refreshes backpressure rate to 0
        assertEquals(1000f, engine.sectors.get(0).getStored("copper"), 1e-3f);
        assertEquals(0f, engine.sectors.get(0).solvedGrossProduction.getOrDefault("copper", 0f), 1e-3f);
        // Mid-full sector advances cleanly by 2.0/s
        assertEquals(502f, engine.sectors.get(100).getStored("copper"), 0.05f);
        assertEquals(2.0f, engine.sectors.get(100).solvedGrossProduction.getOrDefault("copper", 0f), 0.05f);
    }
}
