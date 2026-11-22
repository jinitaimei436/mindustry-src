package mindustry.game.strategy.core;

import java.util.*;

/**
 * Persistent lightweight representation of a sector's production graph, storage buffers,
 * logistics ports, and adjacency topology for background real-time simulation.
 */
public class SectorStrategyProfile{
    public int sectorId = -1;
    public boolean hasBase = false;
    public boolean enemyBase = false;
    public boolean underAttack = false;

    /** True when the player is currently playing this sector in the foreground. */
    public transient boolean foregroundActive = false;

    /**
     * When true (e.g. foreground sector in v8 LandingPad mode), only explicit importDemands
     * pull items from upstream and deliveries are handled externally by LandingPads rather than
     * injected directly into core storage.
     */
    public transient boolean requireExplicitImportDemand = false;

    /** Live measured export rates from an active foreground sector's physical LaunchPads (items/sec). */
    public transient HashMap<String, Float> foregroundExportRates = new HashMap<>();

    /** Fractional accumulator for items delivered from background sectors into a foreground core. */
    public transient HashMap<String, Float> foregroundPendingDelivery = new HashMap<>();

    /** Core item storage capacity for each item type. */
    public int storageCapacity = 4000;

    /**
     * High-precision item stock (float) to avoid integer truncation when stepping
     * every 1 second at fractional rates (e.g. 0.3 items/sec).
     */
    public HashMap<String, Float> storedItems = new HashMap<>();

    /** Compressed production, consumption, and power nodes compiled from map buildings. */
    public ArrayList<ProductionNode> nodes = new ArrayList<>();

    /** Maximum export throughput (items/sec) by item name (or "*" for shared launch pad cap). */
    public HashMap<String, Float> exportCapacities = new HashMap<>();

    /** Desired import rate (items/sec) requested by landing pads or logistics receivers. */
    public HashMap<String, Float> importDemands = new HashMap<>();

    /** Default launch destination sector id (-1 if unset). */
    public int defaultDestinationSectorId = -1;

    /** Adjacent sector ids on the planet mesh (used by logistics distance & troop dispatch). */
    public ArrayList<Integer> adjacentSectorIds = new ArrayList<>();

    /** Garrisoned units ready for defense or cross-sector dispatch (reserved for strategic AI). */
    public HashMap<String, Integer> garrisonUnits = new HashMap<>();

    // --- Runtime solved readouts (not required for persistence) ---

    /** Solved power satisfaction ratio in [0, 1]. */
    public transient float solvedPowerSatisfaction = 1f;

    /** Solved net item rate into core storage (items/sec, may be negative). */
    public transient HashMap<String, Float> solvedNetRates = new HashMap<>();

    /** Solved gross local production rate (items/sec). */
    public transient HashMap<String, Float> solvedGrossProduction = new HashMap<>();

    /** Solved actual export rate leaving this sector (items/sec). */
    public transient HashMap<String, Float> solvedExportRates = new HashMap<>();

    /** Solved actual import rate arriving at this sector (items/sec). */
    public transient HashMap<String, Float> solvedImportRates = new HashMap<>();

    /** Human-readable bottleneck summary for UI diagnostics (e.g. "low-power", "missing:coal"). */
    public transient String bottleneckTag = "";

    public SectorStrategyProfile(){
    }

    public SectorStrategyProfile(int sectorId){
        this.sectorId = sectorId;
    }

    public float getStored(String itemName){
        return storedItems.getOrDefault(itemName, 0f);
    }

    public void setStored(String itemName, float amount){
        float clamped = Math.max(0f, Math.min(storageCapacity, amount));
        storedItems.put(itemName, clamped);
    }

    public float addStored(String itemName, float delta){
        float before = getStored(itemName);
        float after = Math.max(0f, Math.min(storageCapacity, before + delta));
        storedItems.put(itemName, after);
        return after - before;
    }

    public boolean isItemFull(String itemName){
        return storageCapacity > 0 && getStored(itemName) >= storageCapacity - 0.001f;
    }

    public float getNetRate(String itemName){
        return solvedNetRates.getOrDefault(itemName, 0f);
    }

    public float getExportRate(String itemName){
        return solvedExportRates.getOrDefault(itemName, 0f);
    }

    public float getImportRate(String itemName){
        return solvedImportRates.getOrDefault(itemName, 0f);
    }
}
