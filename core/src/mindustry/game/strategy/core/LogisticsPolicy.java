package mindustry.game.strategy.core;

import java.util.*;

/**
 * Pluggable policy interface decoupling gameplay rules from the underlying
 * graph solver and simulation engine.
 */
public interface LogisticsPolicy{

    /**
     * Distributes an upstream sector's available export supply of a single item
     * across competing outgoing routes.
     *
     * @param itemName        item being distributed
     * @param availableSupply total exportable rate (items/sec) from the source sector
     * @param routes          candidate outgoing routes that accept this item
     * @param routeDemands    desired rate (items/sec) for each route (parallel to routes)
     * @return allocated flow rate (items/sec) for each route (parallel to routes)
     */
    float[] allocateSupply(String itemName, float availableSupply, List<LogisticsRoute> routes, float[] routeDemands);

    /**
     * Computes power satisfaction ratio in [0, 1] given total produced and consumed power.
     */
    default float computePowerSatisfaction(float producedPerSec, float consumedPerSec){
        if(consumedPerSec <= 1e-5f) return 1f;
        if(producedPerSec <= 0f) return 0f;
        return Math.min(1f, producedPerSec / consumedPerSec);
    }

    /**
     * Whether a production node should throttle down when all of its outputs are full
     * and not being consumed or exported.
     */
    default boolean enableStorageBackpressure(){
        return true;
    }

    /**
     * Whether sectors under enemy attack freeze background production and exports.
     */
    default boolean freezeUnderAttack(){
        return true;
    }
}
