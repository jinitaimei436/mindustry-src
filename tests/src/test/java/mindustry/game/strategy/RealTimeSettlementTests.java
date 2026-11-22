package mindustry.game.strategy;

import mindustry.core.*;
import mindustry.game.logistics.SectorConveyorMath;
import mindustry.game.logistics.SectorConveyorMath.*;
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
    void versionForkTagIsV31(){
        assertEquals("v3.1", Version.forkVersion);
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
}
