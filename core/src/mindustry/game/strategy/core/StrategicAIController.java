package mindustry.game.strategy.core;

import java.util.*;

/**
 * Reserved strategic AI hook for future enemy offensive behavior and cross-sector
 * troop mobilization. Implementations inspect the planet graph and emit
 * TroopDispatchOrder objects into the shared StrategySimulationEngine.
 */
public interface StrategicAIController{

    /**
     * Called periodically on the strategic clock.
     *
     * @param elapsedSeconds total simulation time elapsed
     * @param dtSeconds      delta time since last AI tick
     * @param sectors        read-only view of all sector profiles on the planet
     * @param outDispatches  list to append newly issued troop dispatches to
     */
    void evaluate(double elapsedSeconds, float dtSeconds, Map<Integer, SectorStrategyProfile> sectors, List<TroopDispatchOrder> outDispatches);
}
