package mindustry.game.strategy;

import mindustry.game.*;
import mindustry.game.strategy.core.*;
import mindustry.type.*;

import java.util.*;

import static mindustry.Vars.*;

/**
 * Bridges Mindustry's Universe / Planet / SectorInfo with the pure-Java
 * StrategySimulationEngine for real-time cross-sector logistics and future
 * strategic troop mobilization.
 */
public class GlobalStrategyManager{
    private final HashMap<String, StrategySimulationEngine> engines = new HashMap<>();
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

        // 1. Sync sector profiles and default launch routes from Mindustry state
        for(Sector sector : planet.sectors){
            if(!sector.hasBase() && !sector.hasEnemyBase()) continue;

            SectorStrategyProfile profile = ensureProfile(sector);
            profile.hasBase = sector.hasBase();
            profile.enemyBase = sector.hasEnemyBase();
            profile.underAttack = sector.isAttacked();
            profile.foregroundActive = sector.isBeingPlayed();
            profile.storageCapacity = sector.info.storageCapacity;
            profile.defaultDestinationSectorId = sector.info.destination != null ? sector.info.destination.id : -1;

            // Keep foreground sector's live storage synced into the graph
            if(profile.foregroundActive){
                sector.info.items.each((item, amount) -> profile.setStored(item.name, amount));
            }

            engine.putSector(profile);

            // Sync default route from SectorInfo.destination
            engine.clearRoutesFrom(sector.id);
            if(sector.hasBase() && sector.info.destination != null && sector.info.destination.planet == planet){
                float cap = profile.exportCapacities.getOrDefault("*", 10f);
                if(cap > 0f){
                    engine.addRoute(new LogisticsRoute(sector.id, sector.info.destination.id, "*", cap));
                }
            }
        }

        // 2. Step the pure-Java event-driven simulation engine
        engine.step(dtSeconds);

        // 3. Push solved inventory & rates back into Mindustry SectorInfo for UI and gameplay
        for(Sector sector : planet.sectors){
            if(!sector.hasBase() || sector.isBeingPlayed()) continue;

            SectorStrategyProfile profile = engine.sectors.get(sector.id);
            if(profile == null) continue;

            // Update core items from float accumulator
            for(Map.Entry<String, Float> entry : profile.storedItems.entrySet()){
                Item item = content.item(entry.getKey());
                if(item != null){
                    int qty = Math.max(0, Math.min(sector.info.storageCapacity, Math.round(entry.getValue())));
                    sector.info.items.set(item, qty);
                }
            }

            // Mirror solved rates into SectorInfo so PlanetDialog HUD shows live numbers
            syncRatesToExportStat(profile.solvedNetRates, sector.info.production);
            syncRatesToExportStat(profile.solvedGrossProduction, sector.info.rawProduction);
            syncRatesToExportStat(profile.solvedExportRates, sector.info.export);
            syncRatesToExportStat(profile.solvedImportRates, sector.info.imports);
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

            for(Sector near : sector.near()){
                profile.adjacentSectorIds.add(near.id);
            }
            sector.info.strategyProfile = profile;
        }
        return profile;
    }

    private void syncRatesToExportStat(Map<String, Float> source, arc.struct.ObjectMap<Item, SectorInfo.ExportStat> target){
        for(Map.Entry<String, Float> entry : source.entrySet()){
            Item item = content.item(entry.getKey());
            if(item != null){
                target.get(item, SectorInfo.ExportStat::new).mean = entry.getValue();
            }
        }
    }
}
