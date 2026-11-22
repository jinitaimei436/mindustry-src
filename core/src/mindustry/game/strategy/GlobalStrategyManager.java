package mindustry.game.strategy;

import mindustry.game.*;
import mindustry.game.strategy.core.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.blocks.campaign.*;
import mindustry.world.blocks.storage.CoreBlock.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Bridges Mindustry's Universe / Planet / SectorInfo with the pure-Java
 * StrategySimulationEngine for real-time cross-sector logistics and future
 * strategic troop mobilization.
 */
public class GlobalStrategyManager{
    private final HashMap<String, StrategySimulationEngine> engines = new HashMap<>();
    /**
     * Tracks the last integer item counts pushed by this manager into each background
     * sector's SectorInfo.items, keyed by planetName -> sectorId -> itemName -> intQty.
     * Used to reconcile external mutations (research spending, launch loadouts, legacy pads)
     * instead of overwriting them on the next 1-second tick.
     */
    private final HashMap<String, HashMap<Integer, HashMap<String, Integer>>> lastSyncedStorage = new HashMap<>();
    private final LogisticsPolicy policy;
    private StrategicAIController aiController;

    public GlobalStrategyManager(){
        this(new DefaultLogisticsPolicy());
    }

    public GlobalStrategyManager(LogisticsPolicy policy){
        this.policy = policy;
    }

    public void setAIController(StrategicAIController controller){
        this.aiController = controller;
        for(StrategySimulationEngine engine : engines.values()){
            engine.setAIController(controller);
        }
    }

    public StrategySimulationEngine getEngine(Planet planet){
        return engines.computeIfAbsent(planet.name, k -> {
            StrategySimulationEngine e = new StrategySimulationEngine(policy);
            e.setAIController(aiController);
            return e;
        });
    }

    /**
     * Advances real-time global logistics for the given planet by dtSeconds.
     * Typically called once per second from Universe.update().
     */
    public void update(Planet planet, float dtSeconds){
        if(planet == null || !planet.campaignRules.globalLogistics) return;

        StrategySimulationEngine engine = getEngine(planet);
        HashMap<Integer, HashMap<String, Integer>> planetLastSynced =
            lastSyncedStorage.computeIfAbsent(planet.name, k -> new HashMap<>());
        boolean legacyPads = planet.campaignRules.legacyLaunchPads;

        // 1. Sync sector profiles, reconcile external storage changes, and build active routes
        for(Sector sector : planet.sectors){
            if(!sector.hasBase() && !sector.hasEnemyBase()){
                // Clean up abandoned or destroyed sectors so they never persist as ghost producers
                engine.removeSector(sector.id);
                planetLastSynced.remove(sector.id);
                if(sector.info.strategyProfile != null){
                    sector.info.strategyProfile.hasBase = false;
                }
                continue;
            }

            SectorStrategyProfile profile = ensureProfile(sector);
            profile.hasBase = sector.hasBase();
            profile.enemyBase = sector.hasEnemyBase();
            profile.underAttack = sector.isAttacked();
            profile.foregroundActive = sector.isBeingPlayed();
            profile.storageCapacity = sector.info.storageCapacity;
            profile.defaultDestinationSectorId = sector.info.destination != null ? sector.info.destination.id : -1;
            profile.requireExplicitImportDemand = false;
            profile.foregroundExportRates.clear();

            if(profile.foregroundActive){
                // Foreground sector runs natively; sync live storage from active core (including zeroes)
                planetLastSynced.remove(sector.id);
                CoreBuild core = state.rules.defaultTeam != null ? state.rules.defaultTeam.core() : null;
                if(core != null){
                    profile.storageCapacity = core.storageCapacity;
                    for(Item item : content.items()){
                        profile.setStored(item.name, core.items.get(item));
                    }
                }else{
                    for(Item item : content.items()){
                        profile.setStored(item.name, sector.info.items.get(item));
                    }
                }

                if(!legacyPads){
                    // In v8 LandingPad mode:
                    // - Outgoing foreground exports come from physical LaunchPad stats (sector.info.export)
                    // - Incoming foreground imports are gated by active physical LandingPads
                    sector.info.export.each((item, stat) -> {
                        if(stat != null && stat.mean > 1e-4f){
                            profile.foregroundExportRates.put(item.name, stat.mean);
                        }
                    });
                    profile.requireExplicitImportDemand = true;
                    syncForegroundLandingPadDemands(profile);
                }
                // In legacyLaunchPads mode: vanilla LaunchPad calls destsec.addItems() directly on launch
                // (absorbed by external delta reconciliation below), so foregroundExportRates stays empty.
            }else if(sector.hasBase()){
                // Reconcile any external changes to sector.info.items (tech tree, launch loadout, legacy pads)
                HashMap<String, Integer> prevSynced = planetLastSynced.get(sector.id);
                for(Item item : content.items()){
                    int actual = sector.info.items.get(item);
                    int expected = prevSynced != null
                        ? prevSynced.getOrDefault(item.name, 0)
                        : Math.max(0, Math.min(profile.storageCapacity, Math.round(profile.getStored(item.name))));
                    int externalDelta = actual - expected;
                    if(externalDelta != 0){
                        profile.addStored(item.name, externalDelta);
                    }
                }
            }else{
                planetLastSynced.remove(sector.id);
            }

            engine.putSector(profile);

            // Sync default route from SectorInfo.destination
            engine.clearRoutesFrom(sector.id);
            if(sector.hasBase() && sector.info.destination != null && sector.info.destination != sector && sector.info.destination.planet == planet){
                if(profile.foregroundActive){
                    if(!legacyPads && !profile.foregroundExportRates.isEmpty()){
                        float fgCap = 0f;
                        for(float v : profile.foregroundExportRates.values()) fgCap += v;
                        if(fgCap > 1e-4f){
                            engine.addRoute(new LogisticsRoute(sector.id, sector.info.destination.id, "*", fgCap));
                        }
                    }
                }else{
                    // Default to 0f so sectors without LaunchPads never receive phantom export capacity
                    float cap = profile.exportCapacities.getOrDefault("*", 0f);
                    if(cap > 1e-4f){
                        engine.addRoute(new LogisticsRoute(sector.id, sector.info.destination.id, "*", cap));
                    }
                }
            }
        }

        // 2. Step the pure-Java event-driven simulation engine
        engine.step(dtSeconds);

        // 3. Push solved inventory & rates back into Mindustry SectorInfo (and foreground core in legacy mode)
        Sector foregroundSector = null;
        for(Sector sector : planet.sectors){
            if(!sector.hasBase()) continue;

            SectorStrategyProfile profile = engine.sectors.get(sector.id);
            if(profile == null) continue;

            if(sector.isBeingPlayed()){
                foregroundSector = sector;
                if(legacyPads){
                    flushForegroundDeliveries(profile);
                }else{
                    profile.foregroundPendingDelivery.clear();
                }
                continue;
            }

            // Update core items from float accumulator and snapshot pushed integers
            HashMap<String, Integer> syncedMap = planetLastSynced.computeIfAbsent(sector.id, k -> new HashMap<>());
            syncedMap.clear();
            for(Item item : content.items()){
                int qty = Math.max(0, Math.min(sector.info.storageCapacity, Math.round(profile.getStored(item.name))));
                sector.info.items.set(item, qty);
                if(qty > 0){
                    syncedMap.put(item.name, qty);
                }
            }

            // Mirror solved rates into SectorInfo so PlanetDialog HUD and LandingPads show live numbers
            syncRatesToExportStat(profile.solvedNetRates, sector.info.production);
            syncRatesToExportStat(profile.solvedGrossProduction, sector.info.rawProduction);
            syncRatesToExportStat(profile.solvedExportRates, sector.info.export);
            syncRatesToExportStat(profile.solvedImportRates, sector.info.imports);
        }

        // Immediately refresh foreground LandingPad import rate cache so background export changes take effect now
        if(foregroundSector != null && !legacyPads){
            foregroundSector.info.refreshImportRates(planet);
        }
    }

    private void syncForegroundLandingPadDemands(SectorStrategyProfile profile){
        profile.importDemands.clear();
        if(state.rules.defaultTeam == null) return;
        for(Building build : state.rules.defaultTeam.data().buildings){
            if(build == null || !build.enabled) continue;
            if(build instanceof LandingPad.LandingPadBuild landingBuild && build.block instanceof LandingPad pad){
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

    private void flushForegroundDeliveries(SectorStrategyProfile profile){
        if(profile.foregroundPendingDelivery.isEmpty()) return;
        CoreBuild core = state.rules.defaultTeam != null ? state.rules.defaultTeam.core() : null;
        if(core == null) return;

        Iterator<Map.Entry<String, Float>> it = profile.foregroundPendingDelivery.entrySet().iterator();
        while(it.hasNext()){
            Map.Entry<String, Float> entry = it.next();
            float pending = entry.getValue();
            int whole = (int)Math.floor(pending);
            if(whole <= 0) continue;

            Item item = content.item(entry.getKey());
            if(item == null){
                it.remove();
                continue;
            }
            int room = Math.max(0, core.storageCapacity - core.items.get(item));
            int toAdd = Math.min(whole, room);
            if(toAdd > 0){
                core.items.add(item, toAdd);
            }
            float remainder = pending - whole;
            if(remainder <= 1e-5f){
                it.remove();
            }else{
                entry.setValue(remainder);
            }
        }
    }

    private SectorStrategyProfile ensureProfile(Sector sector){
        SectorStrategyProfile profile = sector.info.strategyProfile;
        if(profile == null){
            profile = new SectorStrategyProfile(sector.id);
            profile.hasBase = sector.hasBase();
            profile.enemyBase = sector.hasEnemyBase();
            profile.storageCapacity = sector.info.storageCapacity;
            final SectorStrategyProfile created = profile;
            sector.info.items.each((item, amount) -> created.setStored(item.name, amount));

            // Bootstrap legacy sectors that have not been re-visited yet using vanilla mean rates
            ProductionNode legacyNode = new ProductionNode("legacy-bootstrap");
            boolean hasLegacy = false;
            for(Item item : content.items()){
                SectorInfo.ExportStat stat = sector.info.production.get(item);
                if(stat != null && stat.mean > 0.01f){
                    legacyNode.addOutput(item.name, stat.mean);
                    hasLegacy = true;
                }
            }
            if(hasLegacy){
                profile.nodes.add(legacyNode);
            }

            // Preserve recorded launch pad export throughput for legacy unvisited sectors (0 if none)
            float legacyExportTotal = 0f;
            for(Item item : content.items()){
                SectorInfo.ExportStat expStat = sector.info.export.get(item);
                if(expStat != null && expStat.mean > 0.01f){
                    legacyExportTotal += expStat.mean;
                }
            }
            profile.exportCapacities.put("*", legacyExportTotal);

            for(Sector near : sector.near()){
                profile.adjacentSectorIds.add(near.id);
            }
            sector.info.strategyProfile = profile;
        }
        return profile;
    }

    private void syncRatesToExportStat(Map<String, Float> source, arc.struct.ObjectMap<Item, SectorInfo.ExportStat> target){
        // Clear stale entries that are no longer present in the solved rates
        target.each((item, stat) -> {
            if(!source.containsKey(item.name)){
                stat.mean = 0f;
                stat.counter = 0f;
            }
        });
        for(Map.Entry<String, Float> entry : source.entrySet()){
            Item item = content.item(entry.getKey());
            if(item != null){
                target.get(item, SectorInfo.ExportStat::new).mean = entry.getValue();
            }
        }
    }
}
