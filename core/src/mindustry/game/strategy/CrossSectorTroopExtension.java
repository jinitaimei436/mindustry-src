package mindustry.game.strategy;

import mindustry.game.*;
import mindustry.game.strategy.core.*;
import mindustry.game.troops.*;
import mindustry.type.*;

/**
 * Pluggable V3.9 cross-sector extension bridging {@link SectorTroopAirliftManager}
 * into {@link GlobalStrategyManager}'s 1-second real-time planet settlement loop.
 */
public class CrossSectorTroopExtension implements CrossSectorExtension{

    @Override
    public String extensionId(){
        return "v39-cross-sector-troops";
    }

    @Override
    public void onCompileSector(Sector sector, SectorInfo info, SectorStrategyProfile profile){
        // Garrison snapshot is captured directly from foreground live state without touching SectorStrategyProfile JSON.
    }

    @Override
    public void beforeStrategyStep(Planet planet, StrategySimulationEngine engine, float dtSeconds){
        // No pre-step resource mutations required; troop airlift launches deduct physical pad inventory on takeoff.
    }

    @Override
    public void afterStrategyStep(Planet planet, StrategySimulationEngine engine, float dtSeconds){
        SectorTroopAirliftManager.get().stepPlanet(planet, dtSeconds);
    }
}
