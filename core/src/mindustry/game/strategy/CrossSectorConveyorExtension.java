package mindustry.game.strategy;

import mindustry.game.*;
import mindustry.game.logistics.*;
import mindustry.game.logistics.SectorConveyorMath.*;
import mindustry.game.strategy.core.*;
import mindustry.type.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.distribution.SectorConveyorPort.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Integrates the V2.8 Cross-Sector Conveyor system ({@link SectorConveyorManager}) into
 * the real-time planetary settlement engine ({@link GlobalStrategyManager}).
 *
 * Lifecycle per 1-second settlement step:
 * 1. {@link #onCompileSector}: records Senior Sender power consumption and ensures observed
 *    feed patterns are seeded from existing buffers when leaving a sector.
 * 2. {@link #beforeStrategyStep}:
 *    - Drains background receiver FIFOs into their sector's {@link SectorStrategyProfile} storage
 *      at up to receiver release rate (strictly respecting storageCapacity for lossless backpressure).
 *    - Registers linked background senders' outgoing pull rates in {@code externalExportDemands}
 *      so storage-full backpressure never stalls upstream factories while conveyors are exporting.
 * 3. {@link #afterStrategyStep}:
 *    - Refills background sender FIFOs from real sector storage (deducting exact integer counts,
 *      zero item fabrication, power-scaled for Senior Senders).
 *    - Advances background conveyor links with each sender sector's solved power satisfaction
 *      and freeze status.
 */
public class CrossSectorConveyorExtension implements CrossSectorExtension{
    private final HashMap<Integer, Float> scratchPowerEfficiency = new HashMap<>();
    private final HashSet<Integer> scratchFrozenSectorIds = new HashSet<>();

    @Override
    public String extensionId(){
        return "v28-cross-sector-conveyor";
    }

    @Override
    public void onCompileSector(Sector sector, SectorInfo info, SectorStrategyProfile profile){
        if(sector == null || profile == null) return;
        SectorConveyorManager cm = SectorConveyorManager.get();
        PlanetConveyorTopology topo = cm.getTopology(sector.planet);

        // Ensure all senders on this sector have their feed pattern seeded from live buffers
        List<SectorPortRecord> ports = topo.getAllSectorPorts(sector.id);
        for(SectorPortRecord port : ports){
            if(port.isSender){
                port.seedFeedPatternIfEmpty();
            }
        }

        // Include Senior Sender power consumption in the sector's compiled power profile
        if(state != null && state.rules != null && state.rules.defaultTeam != null){
            ProductionNode seniorSenderPowerNode = null;
            for(var build : state.rules.defaultTeam.data().buildings){
                if(build == null || !build.enabled) continue;
                if(build instanceof SectorConveyorPortBuild && build.block instanceof SectorConveyorPort portBlock){
                    if(portBlock.isSender && portBlock.consPower != null && !portBlock.consPower.buffered && portBlock.consPower.usage > 0f){
                        if(seniorSenderPowerNode == null){
                            seniorSenderPowerNode = new ProductionNode(portBlock.name);
                            seniorSenderPowerNode.count = 0;
                            seniorSenderPowerNode.stopWhenOutputsFull = false;
                        }
                        seniorSenderPowerNode.count++;
                        seniorSenderPowerNode.powerConsumePerSec += portBlock.consPower.usage * 60f;
                        float eff = Math.max(0f, Math.min(1f, build.efficiency));
                        if(seniorSenderPowerNode.count <= 1){
                            seniorSenderPowerNode.measuredEfficiency = eff;
                        }else{
                            seniorSenderPowerNode.measuredEfficiency += (eff - seniorSenderPowerNode.measuredEfficiency) / seniorSenderPowerNode.count;
                        }
                    }
                }
            }
            if(seniorSenderPowerNode != null){
                profile.nodes.add(seniorSenderPowerNode);
            }
        }
    }

    @Override
    public void beforeStrategyStep(Planet planet, StrategySimulationEngine engine, float dtSeconds){
        if(planet == null || dtSeconds <= 0f || content == null) return;

        SectorConveyorManager cm = SectorConveyorManager.get();
        PlanetConveyorTopology topo = cm.getTopology(planet);

        for(SectorStrategyProfile profile : engine.sectors.values()){
            if(profile == null || !profile.hasBase || profile.foregroundActive || profile.underAttack) continue;

            List<SectorPortRecord> ports = topo.getAllSectorPortsReadOnly(profile.sectorId);
            if(ports.isEmpty()) continue;

            for(int pi = 0; pi < ports.size(); pi++){
                SectorPortRecord port = ports.get(pi);
                if(!port.isSender){
                    // Drain background receiver FIFO into sector storage at up to receiver release rate
                    if(port.isEmpty()){
                        port.backgroundDrainCarry = Math.min(port.backgroundDrainCarry, 1f);
                        continue;
                    }
                    port.backgroundDrainCarry = Math.min(
                        port.backgroundDrainCarry + port.transferRate * dtSeconds,
                        Math.max(1f, port.transferRate)
                    );
                    while(port.backgroundDrainCarry >= 1f && !port.isEmpty()){
                        int itemId = port.peek();
                        Item item = content.item(itemId);
                        if(item == null){
                            port.dequeue();
                            continue;
                        }
                        if(profile.storageCapacity > 0 && profile.getStored(item.name) + 1f <= profile.storageCapacity + 0.001f){
                            port.dequeue();
                            profile.addStored(item.name, 1f);
                            port.backgroundDrainCarry -= 1f;
                            cm.markDirty();
                        }else{
                            // Target sector storage is full -> preserve item in receiver FIFO (lossless backpressure!)
                            break;
                        }
                    }
                }else{
                    // Declare outgoing conveyor export demand so PlanetNetworkSolver does not stall full-core producers
                    SectorConveyorLink link = topo.getLinkForSender(profile.sectorId, port.portNumber);
                    if(link == null) continue;
                    SectorStrategyProfile dstProfile = engine.sectors.get(link.receiverSectorId);
                    if(dstProfile != null && (!dstProfile.hasBase || dstProfile.underAttack)) continue;

                    SectorPortRecord receiver = topo.getPort(link.receiverSectorId, false, link.receiverPortNumber);
                    if(receiver == null || receiver.isFull()) continue;

                    List<Integer> pattern = port.getEffectiveFeedPattern();
                    if(pattern.isEmpty()) continue;

                    float powerHint = (port.tier == SectorConveyorMath.TIER_SENIOR && profile.externalPowerSatisfaction >= 0f)
                        ? profile.externalPowerSatisfaction
                        : 1f;
                    float effRate = Math.min(port.transferRate, receiver.transferRate) * powerHint;
                    if(effRate <= 0.0001f) continue;

                    float perSlotRate = effRate / pattern.size();
                    for(int i = 0; i < pattern.size(); i++){
                        Item item = content.item(pattern.get(i));
                        if(item != null){
                            profile.externalExportDemands.put(
                                item.name,
                                profile.externalExportDemands.getOrDefault(item.name, 0f) + perSlotRate
                            );
                        }
                    }
                }
            }
        }
    }

    @Override
    public void afterStrategyStep(Planet planet, StrategySimulationEngine engine, float dtSeconds){
        if(planet == null || dtSeconds <= 0f) return;

        SectorConveyorManager cm = SectorConveyorManager.get();
        PlanetConveyorTopology topo = cm.getTopology(planet);

        scratchPowerEfficiency.clear();
        scratchFrozenSectorIds.clear();

        for(SectorStrategyProfile p : engine.sectors.values()){
            if(p == null) continue;
            scratchPowerEfficiency.put(p.sectorId, Math.max(0f, Math.min(1f, p.solvedPowerSatisfaction)));
            if(!p.hasBase || p.underAttack){
                scratchFrozenSectorIds.add(p.sectorId);
            }
        }

        // Refill background sender FIFOs from real sector storage before stepping conveyor links
        if(content != null){
            for(SectorStrategyProfile profile : engine.sectors.values()){
                if(profile == null || !profile.hasBase || profile.foregroundActive || profile.underAttack) continue;

                List<SectorPortRecord> ports = topo.getAllSectorPortsReadOnly(profile.sectorId);
                if(ports.isEmpty()) continue;

                for(int pi = 0; pi < ports.size(); pi++){
                    SectorPortRecord sender = ports.get(pi);
                    if(!sender.isSender) break; // Senders are always ordered before receivers in bucket
                    SectorConveyorLink link = topo.getLinkForSender(profile.sectorId, sender.portNumber);
                    if(link == null) continue;
                    if(scratchFrozenSectorIds.contains(link.receiverSectorId)) continue;

                    SectorPortRecord receiver = topo.getPort(link.receiverSectorId, false, link.receiverPortNumber);
                    if(receiver == null || receiver.isFull()) continue;

                    float senderPowerEff = (sender.tier == SectorConveyorMath.TIER_SENIOR)
                        ? Math.max(0f, Math.min(1f, profile.solvedPowerSatisfaction))
                        : 1f;
                    float pullRate = Math.min(sender.transferRate, receiver.transferRate) * senderPowerEff;
                    if(pullRate <= 0.0001f) continue;

                    List<Integer> pattern = sender.getEffectiveFeedPattern();
                    if(pattern.isEmpty()) continue;

                    sender.backgroundPullCarry = Math.min(
                        sender.backgroundPullCarry + pullRate * dtSeconds,
                        Math.max(1f, sender.transferRate)
                    );

                    while(sender.backgroundPullCarry >= 1f && sender.freeSpace() > 0){
                        boolean pulled = false;
                        int attempts = pattern.size();
                        for(int a = 0; a < attempts; a++){
                            int itemId = sender.nextFeedItemId();
                            Item item = content.item(itemId);
                            if(item != null && profile.getStored(item.name) >= 1f - 1e-4f){
                                if(sender.enqueue((int)item.id)){
                                    profile.addStored(item.name, -1f);
                                    sender.backgroundPullCarry -= 1f;
                                    cm.markDirty();
                                    pulled = true;
                                    break;
                                }
                            }
                        }
                        if(!pulled){
                            break;
                        }
                    }
                }
            }
        }

        // Advance background conveyor links (sender FIFO -> receiver FIFO) with solved power & freeze state
        cm.updateGlobalSecond(planet, dtSeconds, scratchPowerEfficiency, scratchFrozenSectorIds);
    }
}
