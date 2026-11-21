package mindustry.game.strategy;

import mindustry.game.*;
import mindustry.game.strategy.core.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.campaign.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.consumers.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Compiles the active foreground sector's buildings into a compact, persistent
 * SectorStrategyProfile for real-time background simulation.
 */
public class SectorProfileCompiler{

    /**
     * Scans the player's current foreground sector and writes a fresh
     * SectorStrategyProfile into sectorInfo.strategyProfile.
     */
    public static SectorStrategyProfile compileCurrentSector(Sector sector, SectorInfo info){
        SectorStrategyProfile profile = new SectorStrategyProfile(sector.id);
        profile.hasBase = info.hasCore;
        profile.enemyBase = sector.hasEnemyBase();
        profile.underAttack = sector.isAttacked();
        profile.storageCapacity = info.storageCapacity;
        profile.defaultDestinationSectorId = info.destination != null ? info.destination.id : -1;

        // Snapshot core inventory
        info.items.each((item, amount) -> profile.setStored(item.name, amount));

        // Record planet adjacency topology for future logistics distance & troop dispatch
        for(Sector near : sector.near()){
            profile.adjacentSectorIds.add(near.id);
        }

        // Aggregate identical building signatures to keep the graph compact
        HashMap<String, ProductionNode> mergedNodes = new HashMap<>();
        HashMap<String, Float> compiledRawOutputs = new HashMap<>();

        float totalLaunchCapPerSec = 0f;

        if(state.rules.defaultTeam != null){
            for(Building build : state.rules.defaultTeam.data().buildings){
                if(build == null || !build.enabled) continue;
                Block block = build.block;
                float powerUsePerSec = block.consPower != null && !block.consPower.buffered
                    ? block.consPower.usage * 60f
                    : 0f;
                float eff = Math.max(0.05f, Math.min(1f, build.efficiency));

                if(build instanceof Drill.DrillBuild drillBuild && block instanceof Drill drill){
                    Item item = drillBuild.dominantItem;
                    if(item != null && drillBuild.dominantItems > 0){
                        float rate = (60f / Math.max(1f, drill.getDrillTime(item))) * drillBuild.dominantItems;
                        String key = "drill:" + block.name + ":" + item.name;
                        ProductionNode node = mergedNodes.computeIfAbsent(key, k -> new ProductionNode(block.name));
                        node.count++;
                        node.addOutput(item.name, rate);
                        node.powerConsumePerSec += powerUsePerSec;
                        node.measuredEfficiency = blendEfficiency(node.measuredEfficiency, eff, node.count);
                        compiledRawOutputs.put(item.name, compiledRawOutputs.getOrDefault(item.name, 0f) + rate * eff);
                    }
                }else if(build instanceof GenericCrafter.GenericCrafterBuild && block instanceof GenericCrafter crafter){
                    float cyclesPerSec = 60f / Math.max(1f, crafter.craftTime);
                    String key = "crafter:" + block.name;
                    ProductionNode node = mergedNodes.computeIfAbsent(key, k -> new ProductionNode(block.name));
                    node.count++;
                    node.powerConsumePerSec += powerUsePerSec;
                    node.measuredEfficiency = blendEfficiency(node.measuredEfficiency, eff, node.count);

                    if(crafter.outputItems != null){
                        for(ItemStack out : crafter.outputItems){
                            float rate = out.amount * cyclesPerSec;
                            node.addOutput(out.item.name, rate);
                            compiledRawOutputs.put(out.item.name, compiledRawOutputs.getOrDefault(out.item.name, 0f) + rate * eff);
                        }
                    }else if(crafter.outputItem != null){
                        float rate = crafter.outputItem.amount * cyclesPerSec;
                        node.addOutput(crafter.outputItem.item.name, rate);
                        compiledRawOutputs.put(crafter.outputItem.item.name, compiledRawOutputs.getOrDefault(crafter.outputItem.item.name, 0f) + rate * eff);
                    }

                    addItemConsumers(node, block, cyclesPerSec);
                }else if(build instanceof PowerGenerator.GeneratorBuild genBuild && block instanceof PowerGenerator gen){
                    float powerOutPerSec = gen.powerProduction * 60f;
                    float genEff = Math.max(0.05f, Math.min(1f, genBuild.productionEfficiency > 0f ? genBuild.productionEfficiency : eff));
                    float durationTicks = block instanceof ConsumeGenerator cg ? Math.max(1f, cg.itemDuration) : 60f;
                    float cyclesPerSec = 60f / durationTicks;

                    String key = "gen:" + block.name;
                    ProductionNode node = mergedNodes.computeIfAbsent(key, k -> new ProductionNode(block.name));
                    node.count++;
                    node.powerProducePerSec += powerOutPerSec;
                    node.powerConsumePerSec += powerUsePerSec;
                    node.stopWhenOutputsFull = false;
                    node.measuredEfficiency = blendEfficiency(node.measuredEfficiency, genEff, node.count);

                    addItemConsumers(node, block, cyclesPerSec);
                }else if(build instanceof LaunchPad.LaunchPadBuild && block instanceof LaunchPad pad){
                    float cap = (60f / Math.max(1f, pad.launchTime)) * pad.itemCapacity * eff;
                    totalLaunchCapPerSec += cap;
                }else if(build instanceof LandingPad.LandingPadBuild landingBuild && block instanceof LandingPad pad){
                    if(landingBuild.config != null){
                        float demand = (60f / Math.max(1f, pad.cooldownTime)) * pad.itemCapacity;
                        profile.importDemands.put(
                            landingBuild.config.name,
                            profile.importDemands.getOrDefault(landingBuild.config.name, 0f) + demand
                        );
                    }
                }
            }
        }

        profile.nodes.addAll(mergedNodes.values());

        if(totalLaunchCapPerSec > 0f){
            profile.exportCapacities.put("*", totalLaunchCapPerSec);
        }

        // Fallback node for unmodeled sources (unit miners, custom mod blocks, logic-fed items)
        ProductionNode fallback = new ProductionNode("fallback-raw");
        boolean hasFallback = false;
        for(Item item : content.items()){
            SectorInfo.ExportStat stat = info.rawProduction.get(item);
            if(stat == null || stat.mean <= 0.01f) continue;
            float modeled = compiledRawOutputs.getOrDefault(item.name, 0f);
            float residual = stat.mean - modeled;
            if(residual > 0.05f){
                fallback.addOutput(item.name, residual);
                hasFallback = true;
            }
        }
        if(hasFallback){
            fallback.measuredEfficiency = 1f;
            profile.nodes.add(fallback);
        }

        info.strategyProfile = profile;
        return profile;
    }

    private static void addItemConsumers(ProductionNode node, Block block, float cyclesPerSec){
        for(Consume c : block.consumers){
            if(c instanceof ConsumeItems ci){
                for(ItemStack stack : ci.items){
                    node.addInput(stack.item.name, stack.amount * cyclesPerSec);
                }
            }
        }
    }

    private static float blendEfficiency(float currentAvg, float nextSample, int newCount){
        if(newCount <= 1) return nextSample;
        return currentAvg + (nextSample - currentAvg) / newCount;
    }
}
