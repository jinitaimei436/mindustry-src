package mindustry.game.strategy.core;

import java.util.*;

/**
 * Directed logistics edge connecting two sectors on a planet.
 * Designed with optional capacity, latency, priority, and energy cost fields
 * so future gameplay rules can enable them without changing the core schema.
 */
public class LogisticsRoute{
    public int fromSectorId = -1;
    public int toSectorId = -1;

    /** Specific item name to transport, or null / "*" to allow any exportable item. */
    public String itemName = "*";

    /** Maximum throughput in items per second. */
    public float maxItemsPerSec = 10f;

    /** Optional transport delay in seconds (0 = instant continuous stream). */
    public float transitSeconds = 0f;

    /** Higher priority routes receive upstream supply first. */
    public int priority = 0;

    /** Optional power cost per exported item (reserved for future fuel/energy rules). */
    public float powerCostPerItem = 0f;

    public boolean enabled = true;

    /** Last solved total actual flow rate in items/sec across all items on this route. */
    public transient float lastFlowPerSec = 0f;

    /** Last solved per-item actual flow rates in items/sec (supports wildcard "*" routes). */
    public transient HashMap<String, Float> lastItemFlows = new HashMap<>();

    public LogisticsRoute(){
    }

    public LogisticsRoute(int fromSectorId, int toSectorId, String itemName, float maxItemsPerSec){
        this.fromSectorId = fromSectorId;
        this.toSectorId = toSectorId;
        this.itemName = itemName == null ? "*" : itemName;
        this.maxItemsPerSec = maxItemsPerSec;
    }

    public boolean acceptsItem(String item){
        return enabled && ("*".equals(itemName) || itemName.equals(item));
    }

    /** Represents a batch of items currently in transit when transitSeconds > 0. */
    public static class InTransitShipment{
        public int fromSectorId;
        public int toSectorId;
        public String itemName;
        public float amount;
        public float remainingSeconds;

        public InTransitShipment(){
        }

        public InTransitShipment(int fromSectorId, int toSectorId, String itemName, float amount, float remainingSeconds){
            this.fromSectorId = fromSectorId;
            this.toSectorId = toSectorId;
            this.itemName = itemName;
            this.amount = amount;
            this.remainingSeconds = remainingSeconds;
        }
    }
}
