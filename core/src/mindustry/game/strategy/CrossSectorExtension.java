package mindustry.game.strategy;

import mindustry.game.*;
import mindustry.game.strategy.core.*;
import mindustry.type.*;

/**
 * Pluggable extension SPI for integrating cross-sector subsystems into the real-time
 * planetary settlement loop ({@link GlobalStrategyManager}).
 *
 * Built-in extensions include the V2.5 Cross-Sector Power Grid ({@link CrossSectorPowerExtension})
 * and V2.8 Cross-Sector Conveyor Network ({@link CrossSectorConveyorExtension}).
 * Future cross-sector mechanics (fluid pipelines, orbital elevators, cargo trains, unit supply
 * lines, etc.) can register an implementation via {@link GlobalStrategyManager#registerExtension}
 * without modifying the core solver or Universe tick loop.
 */
public interface CrossSectorExtension{

    /** Unique stable identifier used to prevent duplicate extension registration. */
    default String extensionId(){
        return getClass().getName();
    }

    /**
     * Invoked by {@link SectorProfileCompiler#compileCurrentSector} when snapshotting a
     * foreground sector into its {@link SectorStrategyProfile}.
     */
    default void onCompileSector(Sector sector, SectorInfo info, SectorStrategyProfile profile){
    }

    /**
     * Invoked every real-time settlement tick immediately BEFORE {@link StrategySimulationEngine#step}.
     * Use this phase to inject external power satisfaction, drain discrete incoming buffers into
     * background sector storage, and declare external export demands / import streams.
     */
    default void beforeStrategyStep(Planet planet, StrategySimulationEngine engine, float dtSeconds){
    }

    /**
     * Invoked every real-time settlement tick immediately AFTER {@link StrategySimulationEngine#step}
     * and BEFORE final inventories are synced back into {@link SectorInfo#items}.
     * Use this phase to pull real items from background sector storage into outgoing transport
     * buffers and advance background transfer channels using solved sector efficiencies.
     */
    default void afterStrategyStep(Planet planet, StrategySimulationEngine engine, float dtSeconds){
    }
}
