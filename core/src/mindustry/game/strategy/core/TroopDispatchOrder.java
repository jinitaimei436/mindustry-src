package mindustry.game.strategy.core;

import java.util.*;

/**
 * Reserved strategic-layer model for cross-sector troop dispatch (friendly or enemy).
 * Shares the same planet topology and event scheduler as global logistics so future
 * offensive AI and reinforcement mechanics plug in cleanly.
 */
public class TroopDispatchOrder{
    public int fromSectorId = -1;
    public int toSectorId = -1;

    /** Team identifier (e.g. Sharded vs Crux). */
    public int teamId = 0;

    /** True if this dispatch represents an enemy attack/invasion force. */
    public boolean hostile = false;

    /** Unit counts keyed by UnitType.name (e.g. "dagger", "mace", "flare"). */
    public HashMap<String, Integer> unitCounts = new HashMap<>();

    /** Travel time remaining before arrival at the destination sector. */
    public float remainingSeconds = 0f;

    public TroopDispatchOrder(){
    }

    public TroopDispatchOrder(int fromSectorId, int toSectorId, int teamId, boolean hostile, float travelSeconds){
        this.fromSectorId = fromSectorId;
        this.toSectorId = toSectorId;
        this.teamId = teamId;
        this.hostile = hostile;
        this.remainingSeconds = travelSeconds;
    }

    public TroopDispatchOrder addUnits(String unitName, int count){
        if(count > 0){
            unitCounts.put(unitName, unitCounts.getOrDefault(unitName, 0) + count);
        }
        return this;
    }

    public int totalUnits(){
        int sum = 0;
        for(int v : unitCounts.values()) sum += v;
        return sum;
    }
}
