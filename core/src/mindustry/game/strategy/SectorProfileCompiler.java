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
                float eff = Math.max(0f, Math.min(1f, build.efficiency));
                float timeScale = Math.max(0.01f, build.timeScale());

                if(build instanceof Drill.DrillBuild drillBuild && block instanceof Drill drill){
                    Item item = drillBuild.dominantItem;
                    if(item != null && drillBuild.dominantItems > 0){
                        // Account for liquid boost (warmup * speed => liquidMult^2) and overdrive timeScale
                        float liquidMult = 1f + (drill.liquidBoostIntensity - 1f) * Math.max(0f, Math.min(1f, drillBuild.optionalEfficiency));
                        float boostFactor = liquidMult * liquidMult;
                        float rate = (60f / Math.max(1f, drill.getDrillTime(item))) * drillBuild.dominantItems * boostFactor * timeScale;
                        String key = "drill:" + block.name + ":" + item.name;
                        ProductionNode node = getOrCreateNode(mergedNodes, key, block.name);
                        node.count++;
                        node.addOutput(item.name, rate);
                        node.powerConsumePerSec += powerUsePerSec;
                        node.measuredEfficiency = blendEfficiency(node.measuredEfficiency, eff, node.count);
                        compiledRawOutputs.put(item.name, compiledRawOutputs.getOrDefault(item.name, 0f) + rate * eff);
                    }
                }else if(build instanceof BeamDrill.BeamDrillBuild beamBuild && block instanceof BeamDrill beam){
                    Item item = beamBuild.lastItem;
                    if(item != null && beamBuild.facingAmount > 0){
                        float optEff = Math.max(0f, Math.min(1f, beamBuild.optionalEfficiency));
                        float boostMult = 1f + (beam.optionalBoostIntensity - 1f) * optEff;
                        float rate = (60f / Math.max(1f, beam.getDrillTime(item))) * beamBuild.facingAmount * boostMult * timeScale;
                        String key = "beamdrill:" + block.name + ":" + item.name;
                        ProductionNode node = getOrCreateNode(mergedNodes, key, block.name);
                        node.count++;
                        node.addOutput(item.name, rate);
                        node.powerConsumePerSec += powerUsePerSec;
                        node.measuredEfficiency = blendEfficiency(node.measuredEfficiency, eff, node.count);
                        compiledRawOutputs.put(item.name, compiledRawOutputs.getOrDefault(item.name, 0f) + rate * eff);
                    }
                }else if(build instanceof GenericCrafter.GenericCrafterBuild && block instanceof GenericCrafter crafter){
                    // Preserve >100% attribute/heat efficiency boosts in cyclesPerSec while keeping measuredEfficiency in [0, 1]
                    float overBoost = Math.max(1f, build.efficiency);
                    float cyclesPerSec = (60f / Math.max(1f, crafter.craftTime)) * timeScale * overBoost;
                    String key = "crafter:" + block.name;
                    ProductionNode node = getOrCreateNode(mergedNodes, key, block.name);
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
                    float powerOutPerSec = gen.powerProduction * 60f * timeScale;
                    float genEff = Math.max(0f, Math.min(1f, genBuild.productionEfficiency > 0f ? genBuild.productionEfficiency : eff));

                    Item filterFuel = null;
                    float durationTicks = 60f;
                    if(block instanceof ConsumeGenerator cg){
                        if(cg.filterItem != null){
                            filterFuel = resolveFilterFuel(cg.filterItem, genBuild, info, genEff > 0f);
                        }
                        float durMult = filterFuel != null ? cg.itemDurationMultipliers.get(filterFuel, 1f) : 1f;
                        durationTicks = Math.max(1f, cg.itemDuration * durMult);
                    }else if(block instanceof NuclearReactor nr){
                        durationTicks = Math.max(1f, nr.itemDuration);
                    }else if(block instanceof ImpactReactor ir){
                        durationTicks = Math.max(1f, ir.itemDuration);
                    }
                    float cyclesPerSec = (60f / durationTicks) * timeScale;

                    String key = "gen:" + block.name + (filterFuel != null ? ":" + filterFuel.name : "");
                    ProductionNode node = getOrCreateNode(mergedNodes, key, block.name);
                    node.count++;
                    node.powerProducePerSec += powerOutPerSec;
                    node.powerConsumePerSec += powerUsePerSec;
                    node.stopWhenOutputsFull = false;
                    node.measuredEfficiency = blendEfficiency(node.measuredEfficiency, genEff, node.count);

                    if(filterFuel != null){
                        node.addInput(filterFuel.name, cyclesPerSec);
                    }
                    addItemConsumers(node, block, cyclesPerSec);
                }else if(build instanceof LaunchPad.LaunchPadBuild && block instanceof LaunchPad pad){
                    float cap = (60f / Math.max(1f, pad.launchTime)) * pad.itemCapacity * eff * timeScale;
                    totalLaunchCapPerSec += cap;
                    if(powerUsePerSec > 0f){
                        String key = "launchpad:" + block.name;
                        ProductionNode node = getOrCreateNode(mergedNodes, key, block.name);
                        node.count++;
                        node.powerConsumePerSec += powerUsePerSec;
                        node.stopWhenOutputsFull = false;
                        node.measuredEfficiency = blendEfficiency(node.measuredEfficiency, eff, node.count);
                    }
                }else if(build instanceof LandingPad.LandingPadBuild landingBuild && block instanceof LandingPad pad){
                    if(landingBuild.config != null){
                        float cycleTicks = Math.max(1f, pad.cooldownTime + pad.arrivalDuration);
                        float demand = (60f / cycleTicks) * pad.itemCapacity;
                        profile.importDemands.put(
                            landingBuild.config.name,
                            profile.importDemands.getOrDefault(landingBuild.config.name, 0f) + demand
                        );
                    }
                }
            }
        }

        profile.nodes.addAll(mergedNodes.values());

        // Always record explicit shared launch pad cap (including 0f when no LaunchPads exist)
        profile.exportCapacities.put("*", totalLaunchCapPerSec);

        // Fallback node for unmodeled sources (unit miners, custom mod blocks, logic-fed items).
        // Subtract info.imports because vanilla LandingPad calls produced() and includes imports in rawProduction.
        ProductionNode fallback = new ProductionNode("fallback-raw");
        boolean hasFallback = false;
        for(Item item : content.items()){
            SectorInfo.ExportStat stat = info.rawProduction.get(item);
            if(stat == null || stat.mean <= 0.01f) continue;
            SectorInfo.ExportStat impStat = info.imports.get(item);
            float importedMean = impStat != null ? Math.max(0f, impStat.mean) : 0f;
            float modeled = compiledRawOutputs.getOrDefault(item.name, 0f);
            float residual = stat.mean - importedMean - modeled;
            if(residual > 0.05f){
                fallback.addOutput(item.name, residual);
                hasFallback = true;
            }
        }
        if(hasFallback){
            fallback.measuredEfficiency = 1f;
            profile.nodes.add(fallback);
        }

        if(universe != null && universe.strategy != null){
            universe.strategy.notifyCompileSector(sector, info, profile);
        }

        info.strategyProfile = profile;
        return profile;
    }

    private static ProductionNode getOrCreateNode(HashMap<String, ProductionNode> map, String key, String blockName){
        return map.computeIfAbsent(key, k -> {
            ProductionNode n = new ProductionNode(blockName);
            n.count = 0;
            return n;
        });
    }

    private static Item resolveFilterFuel(ConsumeItemFilter filter, Building build, SectorInfo info, boolean currentlyRunning){
        Item consumed = filter.getConsumed(build);
        if(consumed != null) return consumed;
        if(!currentlyRunning) return null;

        // Generator is mid-burn with empty local buffer; pick a matching item present in the sector
        Item fallback = null;
        for(Item candidate : content.items()){
            if(filter.filter.get(candidate)){
                if(info.items.get(candidate) > 0){
                    return candidate;
                }
                if(fallback == null){
                    fallback = candidate;
                }
            }
        }
        return fallback;
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
