package mindustry.game.strategy;

import mindustry.game.*;
import mindustry.game.power.*;
import mindustry.game.power.SectorPowerGridMath.*;
import mindustry.game.strategy.core.*;
import mindustry.type.*;
import mindustry.world.blocks.power.*;

/**
 * Integrates the V2.5 Cross-Sector Power Grid ({@link SectorPowerGridManager}) into
 * the real-time planetary settlement engine ({@link GlobalStrategyManager}).
 *
 * Guarantees that background sectors connected to an inter-sector power grid via a
 * {@link SurgeSectorPowerHub} operate at their true pooled cross-sector grid satisfaction
 * rather than stalling with local "low-power" when powered by a remote power-plant sector.
 */
public class CrossSectorPowerExtension implements CrossSectorExtension{

    @Override
    public String extensionId(){
        return "v25-cross-sector-power";
    }

    @Override
    public void beforeStrategyStep(Planet planet, StrategySimulationEngine engine, float dtSeconds){
        if(planet == null || dtSeconds <= 0f) return;

        SectorPowerGridManager pm = SectorPowerGridManager.get();
        // Step background shared batteries and prune lost hubs first so grid satisfaction is current
        pm.updateGlobalSecond(planet, dtSeconds);

        for(SectorStrategyProfile profile : engine.sectors.values()){
            if(profile == null || !profile.hasBase || profile.foregroundActive) continue;
            if(profile.sectorId < 0 || profile.sectorId >= planet.sectors.size) continue;

            Sector sector = planet.sectors.get(profile.sectorId);
            if(pm.hasHub(sector)){
                GridAggregate agg = pm.getGridAggregate(sector);
                if(agg != null){
                    profile.externalPowerSatisfaction = Math.max(0f, Math.min(1f, agg.satisfaction));
                }
            }
        }
    }
}
