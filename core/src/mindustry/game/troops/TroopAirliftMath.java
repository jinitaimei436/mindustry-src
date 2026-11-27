package mindustry.game.troops;

import java.io.*;
import java.util.*;

/**
 * Pure-Java mathematical and rules engine for the Serpulo Cross-Sector Troop Airlift System (v3.9).
 *
 * Zero Arc/Mindustry dependencies so it can be exhaustively verified via standalone JUnit 5 tests
 * and remains 100% compatible with Android minSdk = 21 (no Comparator.comparing*, List.of, Map.of,
 * List.sort, Map.getOrDefault, or Math.floorMod).
 */
public final class TroopAirliftMath{

    private TroopAirliftMath(){
    }

    // ==================== COMMAND POINTS ====================

    /** Base sector command points with no cores present. */
    public static final int BASE_NO_CORE_CP = 20;
    /** Additional command points per Core: Shard (additive, no diminishing returns). */
    public static final int CP_PER_SHARD = 20;
    /** Additional command points per Core: Foundation (additive, no diminishing returns). */
    public static final int CP_PER_FOUNDATION = 30;
    /** Additional command points per Core: Nucleus (additive, no diminishing returns). */
    public static final int CP_PER_NUCLEUS = 40;

    /** Arrival countdown banner trigger threshold in seconds. */
    public static final float ARRIVAL_BANNER_SECONDS = 60f;
    /** Landing deployment debuff duration in seconds (speed -70%, disarm). */
    public static final float LANDING_DEPLOY_SECONDS = 4f;
    /** Orbital trauma debuff duration in seconds (heal -50%, armor -20%). */
    public static final float ORBITAL_TRAUMA_SECONDS = 45f;

    /** Minimum clamped speed for 3-factor travel time calculation. */
    public static final float MIN_CLAMPED_SPEED = 0.30f;
    /** Maximum clamped speed for 3-factor travel time calculation. */
    public static final float MAX_CLAMPED_SPEED = 2.70f;
    /** Hop time numerator: T_hop = 36 / sqrt(clamp(v_min, 0.30, 2.70)). */
    public static final float HOP_TIME_NUMERATOR = 36f;

    /** Regular search radius (in tiles) around friendly cores for ground/naval landing. */
    public static final int REGULAR_DROP_RADIUS_TILES = 18;
    /** Beachhead search radius (in tiles) around map-edge default spawn for ground/naval landing. */
    public static final int BEACHHEAD_DROP_RADIUS_TILES = 22;

    /**
     * Computes sector Command Point cap using strictly additive multi-core stacking.
     * No core: 20; +20/Shard, +30/Foundation, +40/Nucleus.
     */
    public static int computeSectorCommandCap(int shardCount, int foundationCount, int nucleusCount){
        int s = Math.max(0, shardCount);
        int f = Math.max(0, foundationCount);
        int n = Math.max(0, nucleusCount);
        return BASE_NO_CORE_CP + s * CP_PER_SHARD + f * CP_PER_FOUNDATION + n * CP_PER_NUCLEUS;
    }

    /**
     * Command point occupation for a unit tier.
     * Tier 0 = player core units (Alpha/Beta/Gamma) -> 0 CP;
     * T1 = 1, T2 = 2, T3 = 4, T4 = 10, T5 = 20.
     */
    public static int commandPointsForTier(int tier){
        switch(tier){
            case 0: return 0;
            case 1: return 1;
            case 2: return 2;
            case 3: return 4;
            case 4: return 10;
            case 5: return 20;
            default: return tier < 0 ? 0 : 20;
        }
    }

    // ==================== LAUNCH PAD SPECS ====================

    public enum PadTier{
        LOW(
            "low-troop-launch-pad",
            "低级兵员发射台",
            3, 1, 3,
            20, 10,
            true, true, false,
            240f, 10f, 45f,
            25f, 1.25f,
            new String[]{"silicon", "graphite"},
            new int[]{15, 10}
        ),
        HIGH(
            "high-troop-launch-pad",
            "高级兵员发射台",
            4, 1, 4,
            40, 20,
            true, true, false,
            600f, 12f, 75f,
            20f, 1.00f,
            new String[]{"silicon", "titanium", "plastanium"},
            new int[]{20, 15, 6}
        ),
        SPECIAL(
            "special-troop-launch-pad",
            "特种兵员发射台",
            5, 5, 5,
            20, 0,
            false, false, true,
            2500f, 15f, 150f,
            40f, 1.15f,
            new String[]{"silicon", "plastanium", "surge-alloy", "phase-fabric"},
            new int[]{600, 300, 120, 60}
        );

        public final String blockName;
        public final String displayName;
        public final int footprintSize;
        public final int minUnitTier;
        public final int maxUnitTier;
        public final int regularCapCp;
        public final int beachheadCapCp;
        public final boolean allowFormation;
        public final boolean allowBeachhead;
        /** If true, baseItemAmounts are fixed per launch rather than multiplied by payload CP. */
        public final boolean fixedLaunchCost;
        public final float chargePowerPerSec;
        public final float chargeSeconds;
        public final float cooldownSeconds;
        public final float prepSeconds;
        public final float propFactor;
        public final String[] costItemNames;
        public final int[] baseItemAmounts;

        PadTier(
            String blockName,
            String displayName,
            int footprintSize,
            int minUnitTier,
            int maxUnitTier,
            int regularCapCp,
            int beachheadCapCp,
            boolean allowFormation,
            boolean allowBeachhead,
            boolean fixedLaunchCost,
            float chargePowerPerSec,
            float chargeSeconds,
            float cooldownSeconds,
            float prepSeconds,
            float propFactor,
            String[] costItemNames,
            int[] baseItemAmounts
        ){
            this.blockName = blockName;
            this.displayName = displayName;
            this.footprintSize = footprintSize;
            this.minUnitTier = minUnitTier;
            this.maxUnitTier = maxUnitTier;
            this.regularCapCp = regularCapCp;
            this.beachheadCapCp = beachheadCapCp;
            this.allowFormation = allowFormation;
            this.allowBeachhead = allowBeachhead;
            this.fixedLaunchCost = fixedLaunchCost;
            this.chargePowerPerSec = chargePowerPerSec;
            this.chargeSeconds = chargeSeconds;
            this.cooldownSeconds = cooldownSeconds;
            this.prepSeconds = prepSeconds;
            this.propFactor = propFactor;
            this.costItemNames = costItemNames;
            this.baseItemAmounts = baseItemAmounts;
        }

        public boolean acceptsUnitTier(int tier){
            return tier >= minUnitTier && tier <= maxUnitTier;
        }

        public boolean acceptsItemName(String itemName){
            if(itemName == null) return false;
            for(int i = 0; i < costItemNames.length; i++){
                if(costItemNames[i].equals(itemName)) return true;
            }
            return false;
        }

        public int capForMode(LaunchMode mode){
            if(mode == LaunchMode.BEACHHEAD){
                return allowBeachhead ? beachheadCapCp : 0;
            }
            return regularCapCp;
        }

        public static PadTier fromOrdinal(int ord){
            PadTier[] vals = values();
            if(ord < 0 || ord >= vals.length) return LOW;
            return vals[ord];
        }
    }

    public enum LaunchMode{
        REGULAR,
        BEACHHEAD;

        public float costMultiplier(){
            return this == BEACHHEAD ? 2.0f : 1.0f;
        }

        public float timeMultiplier(){
            return this == BEACHHEAD ? 2.0f : 1.0f;
        }

        public static LaunchMode fromOrdinal(int ord){
            return ord == 1 ? BEACHHEAD : REGULAR;
        }
    }

    public enum RouteMode{
        SAFE,
        FAST;

        public static RouteMode fromOrdinal(int ord){
            return ord == 1 ? FAST : SAFE;
        }
    }

    // ==================== COST & TRAVEL TIME FORMULAS ====================

    /**
     * Super-linear distance cost multiplier for route hop count D >= 1:
     * M_dist(D) = 1 + 0.35 * (D - 1) + 0.05 * (D - 1)^2.
     */
    public static double distanceMultiplier(int hops){
        int d = Math.max(1, hops);
        double dm1 = d - 1;
        return 1.0 + 0.35 * dm1 + 0.05 * dm1 * dm1;
    }

    /**
     * Computes required material amounts for launching a given payload CP over {@code hops} hops.
     * Returns an ordered map of item internal name -> integer quantity required.
     */
    public static LinkedHashMap<String, Integer> computeLaunchCost(
        PadTier pad,
        int payloadCp,
        int hops,
        LaunchMode mode
    ){
        LinkedHashMap<String, Integer> out = new LinkedHashMap<String, Integer>();
        if(pad == null) return out;
        int effectiveCp = Math.max(0, payloadCp);
        if(!pad.fixedLaunchCost && effectiveCp <= 0){
            for(int i = 0; i < pad.costItemNames.length; i++){
                out.put(pad.costItemNames[i], 0);
            }
            return out;
        }
        double distMult = distanceMultiplier(hops);
        double modeMult = mode == LaunchMode.BEACHHEAD ? 2.0 : 1.0;
        double cpScale = pad.fixedLaunchCost ? 1.0 : effectiveCp;

        for(int i = 0; i < pad.costItemNames.length; i++){
            double raw = pad.baseItemAmounts[i] * cpScale * distMult * modeMult;
            int qty = (int)Math.round(raw);
            if(qty < 1 && raw > 0.0) qty = 1;
            out.put(pad.costItemNames[i], qty);
        }
        return out;
    }

    /**
     * Base single-hop travel duration (seconds):
     * T_hop = 36 / sqrt(clamp(v_min, 0.30, 2.70)).
     */
    public static float computeHopSeconds(float minUnitSpeed){
        float clamped = Math.max(MIN_CLAMPED_SPEED, Math.min(MAX_CLAMPED_SPEED, minUnitSpeed));
        return (float)(HOP_TIME_NUMERATOR / Math.sqrt(clamped));
    }

    /**
     * Total 3-factor airlift travel time (seconds):
     * T_total = (T_prep + D * T_hop * K_pad) * M_mode.
     */
    public static float computeTotalTravelSeconds(PadTier pad, int hops, float minUnitSpeed, LaunchMode mode){
        if(pad == null) return 0f;
        int d = Math.max(1, hops);
        float tHop = computeHopSeconds(minUnitSpeed);
        float modeMult = mode == LaunchMode.BEACHHEAD ? 2.0f : 1.0f;
        return (pad.prepSeconds + d * tHop * pad.propFactor) * modeMult;
    }

    /** Computes minimum unit speed across a payload list (defaults to MIN_CLAMPED_SPEED if empty). */
    public static float computeMinPayloadSpeed(List<TroopUnitSnapshot> units){
        if(units == null || units.isEmpty()) return MIN_CLAMPED_SPEED;
        float min = Float.MAX_VALUE;
        for(int i = 0; i < units.size(); i++){
            TroopUnitSnapshot u = units.get(i);
            if(u != null && u.speed > 0f && u.speed < min){
                min = u.speed;
            }
        }
        return min == Float.MAX_VALUE ? MIN_CLAMPED_SPEED : min;
    }

    /** Computes total Command Points of a payload list. */
    public static int computePayloadCp(List<TroopUnitSnapshot> units){
        if(units == null || units.isEmpty()) return 0;
        int total = 0;
        for(int i = 0; i < units.size(); i++){
            TroopUnitSnapshot u = units.get(i);
            if(u != null && u.alive){
                total += u.commandPoints;
            }
        }
        return total;
    }

    /**
     * Validates whether a boarded payload is legal for the given pad and launch mode.
     * Returns null if valid, or a Chinese error reason if invalid.
     */
    public static String validatePayload(PadTier pad, LaunchMode mode, List<TroopUnitSnapshot> units){
        if(pad == null) return "无效的发射台类型";
        if(units == null || units.isEmpty()) return "发射台舱内尚未装入部队";
        if(mode == LaunchMode.BEACHHEAD && !pad.allowBeachhead){
            return pad.displayName + "禁止执行抢滩登陆";
        }
        if(pad == PadTier.SPECIAL){
            if(units.size() != 1){
                return "特种兵员发射台固定只能搭载 1 台 T5 单位";
            }
            TroopUnitSnapshot only = units.get(0);
            if(only.tier != 5){
                return "特种兵员发射台仅允许搭载 T5 单位";
            }
            if(only.formationGroupId > 0){
                return "特种兵员发射台禁止搭载编队单位";
            }
            return null;
        }
        int totalCp = 0;
        for(int i = 0; i < units.size(); i++){
            TroopUnitSnapshot u = units.get(i);
            if(u == null || !u.alive) continue;
            if(!pad.acceptsUnitTier(u.tier)){
                return pad.displayName + "不支持搭载 T" + u.tier + " 单位";
            }
            totalCp += u.commandPoints;
        }
        int cap = pad.capForMode(mode);
        if(totalCp <= 0) return "发射台舱内无有效作战单位";
        if(totalCp > cap){
            return (mode == LaunchMode.BEACHHEAD ? "抢滩" : "常规")
                + "载荷超限（当前 " + totalCp + " / 上限 " + cap + " 点）";
        }
        return null;
    }

    // ==================== 6-TIER THREAT & AA DAMAGE ====================

    /**
     * 6-tier threat levels aligned strictly with {@code Sector.java}:
     * - Unreasonable: preset != null && preset.difficulty == 13
     * - Low < 0.25, Medium [0.25, 0.50), High [0.50, 0.75), Extreme [0.75, 1.00), Eradication >= 1.00.
     */
    public enum ThreatTier{
        LOW("低危", 0.08f, 0.20f),
        MEDIUM("中危", 0.14f, 0.15f),
        HIGH("高危", 0.20f, 0.10f),
        EXTREME("极高", 0.28f, 0.00f),
        ERADICATION("毁灭", 0.38f, 0.00f),
        UNREASONABLE("乱码", 0.55f, 0.00f);

        public final String displayName;
        /** Anti-air damage ratio r in damage = (0.6 * maxHp + 0.4 * curHp) * r. */
        public final float damageRatio;
        /** Minimum HP floor fraction of maxHp (0f means no floor: HP <= 0 -> shot down). */
        public final float floorRatio;

        ThreatTier(String displayName, float damageRatio, float floorRatio){
            this.displayName = displayName;
            this.damageRatio = damageRatio;
            this.floorRatio = floorRatio;
        }

        public boolean canShootDown(){
            return floorRatio <= 0f;
        }
    }

    /** Classifies a sector's threat value + unreasonable preset flag into one of the 6 exact tiers. */
    public static ThreatTier classifyThreat(float threat, boolean isUnreasonable){
        if(isUnreasonable) return ThreatTier.UNREASONABLE;
        if(threat >= 1.00f) return ThreatTier.ERADICATION;
        if(threat >= 0.75f) return ThreatTier.EXTREME;
        if(threat >= 0.50f) return ThreatTier.HIGH;
        if(threat >= 0.25f) return ThreatTier.MEDIUM;
        return ThreatTier.LOW;
    }

    /**
     * Applies one hostile/contested sector's anti-air hit to a unit snapshot in-place.
     * Formula: damage = (0.6 * maxHp + 0.4 * curHp) * r.
     * Low/Medium/High enforce a HP floor of 20%/15%/10% maxHp (never healing already-lower HP).
     * Extreme/Eradication/Unreasonable have no floor: HP <= 0 shoots the unit down.
     *
     * @return true if the unit survived this AA volley, false if shot down.
     */
    public static boolean applyAaDamageToUnit(TroopUnitSnapshot unit, ThreatTier tier){
        if(unit == null || !unit.alive || tier == null) return false;
        float maxHp = Math.max(1f, unit.maxHealth);
        float curHp = Math.max(0f, unit.health);
        float dmg = (0.6f * maxHp + 0.4f * curHp) * tier.damageRatio;
        float nextHp = curHp - dmg;

        if(tier.floorRatio > 0f){
            float floorHp = Math.min(curHp, maxHp * tier.floorRatio);
            if(nextHp < floorHp){
                nextHp = floorHp;
            }
        }

        unit.orbitalDamaged = true;
        if(nextHp <= 0f){
            unit.health = 0f;
            unit.alive = false;
            return false;
        }
        unit.health = nextHp;
        return true;
    }

    /**
     * Evaluates one hostile/contested hop against all living units in a mission payload.
     * @return number of units shot down on this hop.
     */
    public static int resolveHopAirDefense(List<TroopUnitSnapshot> units, ThreatTier tier){
        if(units == null || tier == null) return 0;
        int shotDown = 0;
        for(int i = 0; i < units.size(); i++){
            TroopUnitSnapshot u = units.get(i);
            if(u != null && u.alive){
                if(!applyAaDamageToUnit(u, tier)){
                    shotDown++;
                }
            }
        }
        return shotDown;
    }

    // ==================== ROUTE BFS & RISK PREVIEW ====================

    /** Lightweight sector descriptor for pure-Java graph routing and AA evaluation. */
    public static class SectorNodeInfo{
        public final int sectorId;
        public final boolean hasFriendlyCore;
        public final boolean hasEnemyBase;
        public final boolean underAttack;
        public final float threat;
        public final boolean unreasonable;
        public final int[] neighbors;

        public SectorNodeInfo(
            int sectorId,
            boolean hasFriendlyCore,
            boolean hasEnemyBase,
            boolean underAttack,
            float threat,
            boolean unreasonable,
            int[] neighbors
        ){
            this.sectorId = sectorId;
            this.hasFriendlyCore = hasFriendlyCore;
            this.hasEnemyBase = hasEnemyBase;
            this.underAttack = underAttack;
            this.threat = threat;
            this.unreasonable = unreasonable;
            this.neighbors = neighbors != null ? neighbors : new int[0];
        }

        /** Safe intermediate/target node for Safe Route BFS: captured and not currently attacked. */
        public boolean isSafeForRouting(){
            return hasFriendlyCore && !underAttack;
        }

        /** Triggers AA defense when overflown (enemy base or currently under attack). */
        public boolean triggersAirDefense(){
            return hasEnemyBase || underAttack;
        }

        public ThreatTier threatTier(){
            return classifyThreat(threat, unreasonable);
        }
    }

    /**
     * Finds shortest route [originSectorId, ..., targetSectorId] using BFS.
     * If {@code routeMode == RouteMode.SAFE}, only sectors satisfying {@link SectorNodeInfo#isSafeForRouting()}
     * may be traversed (destination must also be safe). Returns null if unreachable.
     */
    public static int[] findRoute(
        Map<Integer, SectorNodeInfo> graph,
        int originSectorId,
        int targetSectorId,
        RouteMode routeMode
    ){
        if(graph == null || originSectorId == targetSectorId) return null;
        SectorNodeInfo origin = graph.get(Integer.valueOf(originSectorId));
        SectorNodeInfo target = graph.get(Integer.valueOf(targetSectorId));
        if(origin == null || target == null) return null;
        if(routeMode == RouteMode.SAFE && !target.isSafeForRouting()){
            return null;
        }

        HashMap<Integer, Integer> parent = new HashMap<Integer, Integer>();
        ArrayDeque<Integer> queue = new ArrayDeque<Integer>();
        Integer startKey = Integer.valueOf(originSectorId);
        parent.put(startKey, Integer.valueOf(-1));
        queue.add(startKey);

        boolean found = false;
        while(!queue.isEmpty()){
            int cur = queue.poll().intValue();
            if(cur == targetSectorId){
                found = true;
                break;
            }
            SectorNodeInfo node = graph.get(Integer.valueOf(cur));
            if(node == null) continue;
            for(int i = 0; i < node.neighbors.length; i++){
                int nxt = node.neighbors[i];
                Integer nxtKey = Integer.valueOf(nxt);
                if(parent.containsKey(nxtKey)) continue;
                SectorNodeInfo nxtNode = graph.get(nxtKey);
                if(nxtNode == null) continue;
                if(routeMode == RouteMode.SAFE && !nxtNode.isSafeForRouting()){
                    continue;
                }
                parent.put(nxtKey, Integer.valueOf(cur));
                queue.add(nxtKey);
            }
        }

        if(!found) return null;

        ArrayList<Integer> rev = new ArrayList<Integer>();
        int step = targetSectorId;
        while(step != -1){
            rev.add(Integer.valueOf(step));
            Integer p = parent.get(Integer.valueOf(step));
            step = p == null ? -1 : p.intValue();
        }
        int n = rev.size();
        int[] route = new int[n];
        for(int i = 0; i < n; i++){
            route[i] = rev.get(n - 1 - i).intValue();
        }
        return route;
    }

    /** Checks whether {@code targetSectorId} is directly adjacent to {@code originSectorId} (5 or 6 neighbors). */
    public static boolean isAdjacent(Map<Integer, SectorNodeInfo> graph, int originSectorId, int targetSectorId){
        if(graph == null || originSectorId == targetSectorId) return false;
        SectorNodeInfo origin = graph.get(Integer.valueOf(originSectorId));
        if(origin == null) return false;
        for(int i = 0; i < origin.neighbors.length; i++){
            if(origin.neighbors[i] == targetSectorId) return true;
        }
        return false;
    }

    public static class RouteRiskPreview{
        public int hops;
        public int hostileSectorCount;
        public float predictedHpLossRatio;
        public int predictedCasualties;
        public int totalUnits;
        public boolean hasShootdownRisk;
        public ThreatTier highestThreat;
    }

    /**
     * Simulates AA exposure along {@code route} (indices 1..route.length-1) on a deep copy of {@code units}
     * to power the pre-launch risk preview UI.
     */
    public static RouteRiskPreview previewRouteRisk(
        Map<Integer, SectorNodeInfo> graph,
        int[] route,
        List<TroopUnitSnapshot> units
    ){
        RouteRiskPreview preview = new RouteRiskPreview();
        if(route == null || route.length < 2) return preview;
        preview.hops = route.length - 1;

        ArrayList<TroopUnitSnapshot> sim = new ArrayList<TroopUnitSnapshot>();
        float initialTotalHp = 0f;
        if(units != null){
            for(int i = 0; i < units.size(); i++){
                TroopUnitSnapshot u = units.get(i);
                if(u != null && u.alive){
                    TroopUnitSnapshot copy = u.copy();
                    sim.add(copy);
                    initialTotalHp += Math.max(0f, copy.health);
                }
            }
        }
        preview.totalUnits = sim.size();

        for(int idx = 1; idx < route.length; idx++){
            SectorNodeInfo node = graph == null ? null : graph.get(Integer.valueOf(route[idx]));
            if(node != null && node.triggersAirDefense()){
                preview.hostileSectorCount++;
                ThreatTier tier = node.threatTier();
                if(preview.highestThreat == null || tier.ordinal() > preview.highestThreat.ordinal()){
                    preview.highestThreat = tier;
                }
                if(tier.canShootDown()){
                    preview.hasShootdownRisk = true;
                }
                resolveHopAirDefense(sim, tier);
            }
        }

        float finalTotalHp = 0f;
        int dead = 0;
        for(int i = 0; i < sim.size(); i++){
            TroopUnitSnapshot u = sim.get(i);
            if(!u.alive || u.health <= 0f){
                dead++;
            }else{
                finalTotalHp += u.health;
            }
        }
        preview.predictedCasualties = dead;
        if(dead > 0){
            preview.hasShootdownRisk = true;
        }
        if(initialTotalHp > 0.0001f){
            preview.predictedHpLossRatio = Math.max(0f, Math.min(1f, 1f - (finalTotalHp / initialTotalHp)));
        }
        return preview;
    }

    // ==================== BIDIRECTIONAL CP OVERFLOW CULLING ====================

    /** Candidate unit record for bidirectional Command Point overflow culling. */
    public static class CullCandidate{
        /** Live Unit.id for existing units, or payload index for arriving units. */
        public final int key;
        public final int tier;
        public final int commandPoints;
        public final boolean inFormation;
        public final float healthRatio;
        public final boolean playerControlled;

        public CullCandidate(int key, int tier, int commandPoints, boolean inFormation, float healthRatio, boolean playerControlled){
            this.key = key;
            this.tier = tier;
            this.commandPoints = Math.max(0, commandPoints);
            this.inFormation = inFormation;
            this.healthRatio = Math.max(0f, Math.min(1f, healthRatio));
            this.playerControlled = playerControlled;
        }
    }

    public static class OverflowCullResult{
        /** Existing unit keys (Unit.id) to destroy. */
        public final ArrayList<Integer> culledExistingKeys = new ArrayList<Integer>();
        /** Arriving payload indices to destroy. */
        public final ArrayList<Integer> culledArrivingKeys = new ArrayList<Integer>();
        public int remainingExistingCp;
        public int remainingArrivingCp;
    }

    /**
     * Comparator for CP overflow destruction priority (earlier = destroyed FIRST):
     * 1. Lowest unit tier ascending (T1 -> T5).
     * 2. Same tier: player-controlled unit ALWAYS protected last.
     * 3. Same tier: non-formation solo units BEFORE formation members.
     * 4. Same tier: lowest current HP% first.
     * 5. Deterministic tie-breaker: key ascending.
     */
    public static final Comparator<CullCandidate> CULL_PRIORITY_COMPARATOR = new Comparator<CullCandidate>(){
        @Override
        public int compare(CullCandidate a, CullCandidate b){
            int c = Integer.compare(a.tier, b.tier);
            if(c != 0) return c;
            // Player-controlled unit ranks LAST among same-tier units.
            c = Boolean.compare(a.playerControlled, b.playerControlled);
            if(c != 0) return c;
            // Non-formation solo units culled BEFORE formation members.
            c = Boolean.compare(a.inFormation, b.inFormation);
            if(c != 0) return c;
            // Lowest HP percentage culled first.
            c = Float.compare(a.healthRatio, b.healthRatio);
            if(c != 0) return c;
            return Integer.compare(a.key, b.key);
        }
    };

    /**
     * Resolves bidirectional Command Point overflow on landing:
     * - If existingCp + arrivingCp <= sectorCap -> cull nothing.
     * - First cull lowest-priority existing units (cp > 0) until existingCp + arrivingCp <= sectorCap.
     * - If arrivingCp ALONE still exceeds sectorCap (after all CP-consuming existing units are cleared),
     *   cull lowest-priority arriving units until remainingArrivingCp <= sectorCap.
     * Core units (cp == 0) are never culled.
     */
    public static OverflowCullResult resolveOverflowCull(
        List<CullCandidate> existingUnits,
        List<CullCandidate> arrivingUnits,
        int sectorCap
    ){
        OverflowCullResult result = new OverflowCullResult();
        int cap = Math.max(0, sectorCap);

        ArrayList<CullCandidate> existList = new ArrayList<CullCandidate>();
        int existCp = 0;
        if(existingUnits != null){
            for(int i = 0; i < existingUnits.size(); i++){
                CullCandidate c = existingUnits.get(i);
                if(c != null && c.commandPoints > 0){
                    existList.add(c);
                    existCp += c.commandPoints;
                }
            }
        }

        ArrayList<CullCandidate> arriveList = new ArrayList<CullCandidate>();
        int arriveCp = 0;
        if(arrivingUnits != null){
            for(int i = 0; i < arrivingUnits.size(); i++){
                CullCandidate c = arrivingUnits.get(i);
                if(c != null && c.commandPoints > 0){
                    arriveList.add(c);
                    arriveCp += c.commandPoints;
                }
            }
        }

        if(existCp + arriveCp > cap){
            // Phase 1: cull existing units first.
            Collections.sort(existList, CULL_PRIORITY_COMPARATOR);
            for(int i = 0; i < existList.size() && existCp + arriveCp > cap; i++){
                CullCandidate victim = existList.get(i);
                result.culledExistingKeys.add(Integer.valueOf(victim.key));
                existCp -= victim.commandPoints;
            }

            // Phase 2: if arriving squad itself exceeds cap, cull arriving units.
            if(existCp + arriveCp > cap){
                Collections.sort(arriveList, CULL_PRIORITY_COMPARATOR);
                for(int i = 0; i < arriveList.size() && existCp + arriveCp > cap; i++){
                    CullCandidate victim = arriveList.get(i);
                    result.culledArrivingKeys.add(Integer.valueOf(victim.key));
                    arriveCp -= victim.commandPoints;
                }
            }
        }

        result.remainingExistingCp = existCp;
        result.remainingArrivingCp = arriveCp;
        return result;
    }

    // ==================== PERSISTENT DATA STRUCTURES ====================

    /** Serializable snapshot of a boarded / in-flight / pending-drop unit. */
    public static class TroopUnitSnapshot{
        public String unitTypeName = "";
        public int tier = 1;
        public int commandPoints = 1;
        public float health = 100f;
        public float maxHealth = 100f;
        public float speed = 1f;
        public boolean flying = false;
        public boolean naval = false;
        /** > 0 if belonging to a TacticalFormation preserved across transit; 0 if solo. */
        public int formationGroupId = 0;
        public boolean orbitalDamaged = false;
        public boolean alive = true;

        public TroopUnitSnapshot(){
        }

        public TroopUnitSnapshot(
            String unitTypeName,
            int tier,
            float health,
            float maxHealth,
            float speed,
            boolean flying,
            boolean naval,
            int formationGroupId
        ){
            this.unitTypeName = unitTypeName == null ? "" : unitTypeName;
            this.tier = tier;
            this.commandPoints = commandPointsForTier(tier);
            this.maxHealth = Math.max(1f, maxHealth);
            this.health = Math.max(0f, Math.min(this.maxHealth, health));
            this.speed = Math.max(0.01f, speed);
            this.flying = flying;
            this.naval = naval;
            this.formationGroupId = Math.max(0, formationGroupId);
            this.alive = this.health > 0f;
        }

        public TroopUnitSnapshot copy(){
            TroopUnitSnapshot c = new TroopUnitSnapshot(
                unitTypeName, tier, health, maxHealth, speed, flying, naval, formationGroupId
            );
            c.commandPoints = commandPoints;
            c.orbitalDamaged = orbitalDamaged;
            c.alive = alive;
            return c;
        }

        public float healthRatio(){
            return maxHealth <= 0f ? 0f : Math.max(0f, Math.min(1f, health / maxHealth));
        }

        public void write(DataOutput out) throws IOException{
            out.writeUTF(unitTypeName == null ? "" : unitTypeName);
            out.writeInt(tier);
            out.writeInt(commandPoints);
            out.writeFloat(health);
            out.writeFloat(maxHealth);
            out.writeFloat(speed);
            out.writeBoolean(flying);
            out.writeBoolean(naval);
            out.writeInt(formationGroupId);
            out.writeBoolean(orbitalDamaged);
            out.writeBoolean(alive);
        }

        public static TroopUnitSnapshot read(DataInput in) throws IOException{
            TroopUnitSnapshot s = new TroopUnitSnapshot();
            s.unitTypeName = in.readUTF();
            s.tier = in.readInt();
            s.commandPoints = in.readInt();
            s.health = in.readFloat();
            s.maxHealth = in.readFloat();
            s.speed = in.readFloat();
            s.flying = in.readBoolean();
            s.naval = in.readBoolean();
            s.formationGroupId = in.readInt();
            s.orbitalDamaged = in.readBoolean();
            s.alive = in.readBoolean();
            return s;
        }
    }

    /** Serializable in-flight cross-sector airlift mission with locked route. */
    public static class TroopAirliftMission{
        public int missionId;
        public String planetName = "serpulo";
        public int originSectorId;
        public int targetSectorId;
        public PadTier padTier = PadTier.LOW;
        public LaunchMode launchMode = LaunchMode.REGULAR;
        public RouteMode routeMode = RouteMode.SAFE;
        /** Locked sector ID sequence [originSectorId, hop1, ..., targetSectorId]. */
        public int[] lockedRoute = new int[0];
        /** Index into lockedRoute of the next hop whose AA defense has not been checked yet (starts at 1). */
        public int nextAaHopIndex = 1;
        public float elapsedSeconds = 0f;
        public float totalDurationSeconds = 30f;
        public boolean arrivalBannerTriggered = false;
        public final ArrayList<TroopUnitSnapshot> units = new ArrayList<TroopUnitSnapshot>();

        public int hops(){
            return Math.max(1, lockedRoute.length - 1);
        }

        public float remainingSeconds(){
            return Math.max(0f, totalDurationSeconds - elapsedSeconds);
        }

        public float progress(){
            if(totalDurationSeconds <= 0.0001f) return 1f;
            return Math.max(0f, Math.min(1f, elapsedSeconds / totalDurationSeconds));
        }

        public int livingUnitCount(){
            int c = 0;
            for(int i = 0; i < units.size(); i++){
                if(units.get(i).alive) c++;
            }
            return c;
        }

        public int livingCp(){
            return computePayloadCp(units);
        }

        /**
         * Returns the hop index (1 .. hops) whose air defense check threshold has been reached
         * at current progress, or -1 if none pending yet.
         */
        public int pollDueAaHopIndex(){
            int totalHops = hops();
            if(nextAaHopIndex > totalHops || lockedRoute.length < 2) return -1;
            float threshold = (float)nextAaHopIndex / (float)totalHops;
            if(progress() + 1e-4f >= threshold){
                int due = nextAaHopIndex;
                nextAaHopIndex++;
                return due;
            }
            return -1;
        }

        /** Returns true if mission should display the 60s arrival HUD banner now. */
        public boolean shouldShowArrivalBanner(){
            return remainingSeconds() <= ARRIVAL_BANNER_SECONDS && livingUnitCount() > 0;
        }

        public void write(DataOutput out) throws IOException{
            out.writeInt(missionId);
            out.writeUTF(planetName == null ? "serpulo" : planetName);
            out.writeInt(originSectorId);
            out.writeInt(targetSectorId);
            out.writeByte(padTier.ordinal());
            out.writeByte(launchMode.ordinal());
            out.writeByte(routeMode.ordinal());
            out.writeInt(lockedRoute.length);
            for(int i = 0; i < lockedRoute.length; i++){
                out.writeInt(lockedRoute[i]);
            }
            out.writeInt(nextAaHopIndex);
            out.writeFloat(elapsedSeconds);
            out.writeFloat(totalDurationSeconds);
            out.writeBoolean(arrivalBannerTriggered);
            out.writeInt(units.size());
            for(int i = 0; i < units.size(); i++){
                units.get(i).write(out);
            }
        }

        public static TroopAirliftMission read(DataInput in) throws IOException{
            TroopAirliftMission m = new TroopAirliftMission();
            m.missionId = in.readInt();
            m.planetName = in.readUTF();
            m.originSectorId = in.readInt();
            m.targetSectorId = in.readInt();
            m.padTier = PadTier.fromOrdinal(in.readByte());
            m.launchMode = LaunchMode.fromOrdinal(in.readByte());
            m.routeMode = RouteMode.fromOrdinal(in.readByte());
            int rLen = Math.max(0, in.readInt());
            m.lockedRoute = new int[rLen];
            for(int i = 0; i < rLen; i++){
                m.lockedRoute[i] = in.readInt();
            }
            m.nextAaHopIndex = in.readInt();
            m.elapsedSeconds = in.readFloat();
            m.totalDurationSeconds = in.readFloat();
            m.arrivalBannerTriggered = in.readBoolean();
            int uLen = Math.max(0, in.readInt());
            for(int i = 0; i < uLen; i++){
                m.units.add(TroopUnitSnapshot.read(in));
            }
            return m;
        }
    }

    /** Batch of troops that arrived at an offline/background sector and awaits physical drop on entry. */
    public static class PendingArrivalBatch{
        public int originSectorId;
        public LaunchMode launchMode = LaunchMode.REGULAR;
        public final ArrayList<TroopUnitSnapshot> units = new ArrayList<TroopUnitSnapshot>();

        public int livingCp(){
            return computePayloadCp(units);
        }

        public int livingCount(){
            int c = 0;
            for(int i = 0; i < units.size(); i++){
                if(units.get(i).alive) c++;
            }
            return c;
        }

        public void write(DataOutput out) throws IOException{
            out.writeInt(originSectorId);
            out.writeByte(launchMode.ordinal());
            out.writeInt(units.size());
            for(int i = 0; i < units.size(); i++){
                units.get(i).write(out);
            }
        }

        public static PendingArrivalBatch read(DataInput in) throws IOException{
            PendingArrivalBatch b = new PendingArrivalBatch();
            b.originSectorId = in.readInt();
            b.launchMode = LaunchMode.fromOrdinal(in.readByte());
            int len = Math.max(0, in.readInt());
            for(int i = 0; i < len; i++){
                b.units.add(TroopUnitSnapshot.read(in));
            }
            return b;
        }
    }

    /** Persisted garrison telemetry & water-terrain cache for PlanetDialog's Airlift View. */
    public static class SectorGarrisonSnapshot{
        public int sectorId;
        public int shardCores;
        public int foundationCores;
        public int nucleusCores;
        public int commandCap = BASE_NO_CORE_CP;
        public int usedCp;
        public int livingUnits;
        public int formationCount;
        /** True if this sector currently holds friendly beachhead troops with no friendly core. */
        public boolean beachheadActive;
        /** -1 = unknown (unvisited), 0 = no deep water, 1 = has deep water. */
        public byte deepWaterState = -1;
        /** Count per tier [0..5] for details display. */
        public final int[] tierCounts = new int[6];

        public SectorGarrisonSnapshot(){
        }

        public SectorGarrisonSnapshot(int sectorId){
            this.sectorId = sectorId;
        }

        public void recomputeCap(){
            commandCap = computeSectorCommandCap(shardCores, foundationCores, nucleusCores);
        }

        public void write(DataOutput out) throws IOException{
            out.writeInt(sectorId);
            out.writeInt(shardCores);
            out.writeInt(foundationCores);
            out.writeInt(nucleusCores);
            out.writeInt(commandCap);
            out.writeInt(usedCp);
            out.writeInt(livingUnits);
            out.writeInt(formationCount);
            out.writeBoolean(beachheadActive);
            out.writeByte(deepWaterState);
            for(int i = 0; i < 6; i++){
                out.writeInt(tierCounts[i]);
            }
        }

        public static SectorGarrisonSnapshot read(DataInput in) throws IOException{
            SectorGarrisonSnapshot s = new SectorGarrisonSnapshot();
            s.sectorId = in.readInt();
            s.shardCores = in.readInt();
            s.foundationCores = in.readInt();
            s.nucleusCores = in.readInt();
            s.commandCap = in.readInt();
            s.usedCp = in.readInt();
            s.livingUnits = in.readInt();
            s.formationCount = in.readInt();
            s.beachheadActive = in.readBoolean();
            s.deepWaterState = in.readByte();
            for(int i = 0; i < 6; i++){
                s.tierCounts[i] = in.readInt();
            }
            return s;
        }
    }

    // ==================== PERSISTED PLANET AIRLIFT STATE ====================

    public static final int STREAM_VERSION = 1;

    /** All persistent cross-sector troop airlift data for a single planet. */
    public static class PlanetAirliftState{
        public int nextMissionId = 1;
        public final ArrayList<TroopAirliftMission> activeMissions = new ArrayList<TroopAirliftMission>();
        public final HashMap<Integer, ArrayList<PendingArrivalBatch>> pendingArrivals = new HashMap<Integer, ArrayList<PendingArrivalBatch>>();
        public final HashMap<Integer, SectorGarrisonSnapshot> garrisons = new HashMap<Integer, SectorGarrisonSnapshot>();

        public SectorGarrisonSnapshot getOrCreateGarrison(int sectorId){
            Integer key = Integer.valueOf(sectorId);
            SectorGarrisonSnapshot g = garrisons.get(key);
            if(g == null){
                g = new SectorGarrisonSnapshot(sectorId);
                garrisons.put(key, g);
            }
            return g;
        }

        public void queuePendingArrival(int targetSectorId, PendingArrivalBatch batch){
            if(batch == null || batch.livingCount() <= 0) return;
            Integer key = Integer.valueOf(targetSectorId);
            ArrayList<PendingArrivalBatch> list = pendingArrivals.get(key);
            if(list == null){
                list = new ArrayList<PendingArrivalBatch>();
                pendingArrivals.put(key, list);
            }
            list.add(batch);
        }

        public List<PendingArrivalBatch> getPendingArrivals(int sectorId){
            ArrayList<PendingArrivalBatch> list = pendingArrivals.get(Integer.valueOf(sectorId));
            return list == null ? Collections.<PendingArrivalBatch>emptyList() : list;
        }

        public ArrayList<PendingArrivalBatch> pollPendingArrivals(int sectorId){
            ArrayList<PendingArrivalBatch> removed = pendingArrivals.remove(Integer.valueOf(sectorId));
            return removed == null ? new ArrayList<PendingArrivalBatch>() : removed;
        }

        public boolean hasPendingBeachhead(int sectorId){
            ArrayList<PendingArrivalBatch> list = pendingArrivals.get(Integer.valueOf(sectorId));
            if(list == null) return false;
            for(int i = 0; i < list.size(); i++){
                PendingArrivalBatch b = list.get(i);
                if(b.launchMode == LaunchMode.BEACHHEAD && b.livingCount() > 0){
                    return true;
                }
            }
            return false;
        }

        public boolean hasBeachheadPresence(int sectorId){
            if(hasPendingBeachhead(sectorId)) return true;
            SectorGarrisonSnapshot g = garrisons.get(Integer.valueOf(sectorId));
            return g != null && g.beachheadActive && g.livingUnits > 0;
        }

        public void writeToStream(DataOutput out) throws IOException{
            out.writeInt(STREAM_VERSION);
            out.writeInt(nextMissionId);

            out.writeInt(activeMissions.size());
            for(int i = 0; i < activeMissions.size(); i++){
                activeMissions.get(i).write(out);
            }

            out.writeInt(pendingArrivals.size());
            for(Map.Entry<Integer, ArrayList<PendingArrivalBatch>> e : pendingArrivals.entrySet()){
                out.writeInt(e.getKey().intValue());
                ArrayList<PendingArrivalBatch> batches = e.getValue();
                out.writeInt(batches.size());
                for(int i = 0; i < batches.size(); i++){
                    batches.get(i).write(out);
                }
            }

            out.writeInt(garrisons.size());
            for(Map.Entry<Integer, SectorGarrisonSnapshot> e : garrisons.entrySet()){
                e.getValue().write(out);
            }
        }

        public void readFromStream(DataInput in) throws IOException{
            activeMissions.clear();
            pendingArrivals.clear();
            garrisons.clear();

            int version = in.readInt();
            if(version != STREAM_VERSION) return;

            nextMissionId = Math.max(1, in.readInt());

            int mCount = Math.max(0, in.readInt());
            for(int i = 0; i < mCount; i++){
                TroopAirliftMission m = TroopAirliftMission.read(in);
                activeMissions.add(m);
                if(m.missionId >= nextMissionId){
                    nextMissionId = m.missionId + 1;
                }
            }

            int pCount = Math.max(0, in.readInt());
            for(int i = 0; i < pCount; i++){
                int secId = in.readInt();
                int bCount = Math.max(0, in.readInt());
                ArrayList<PendingArrivalBatch> list = new ArrayList<PendingArrivalBatch>(bCount);
                for(int j = 0; j < bCount; j++){
                    list.add(PendingArrivalBatch.read(in));
                }
                if(!list.isEmpty()){
                    pendingArrivals.put(Integer.valueOf(secId), list);
                }
            }

            int gCount = Math.max(0, in.readInt());
            for(int i = 0; i < gCount; i++){
                SectorGarrisonSnapshot snap = SectorGarrisonSnapshot.read(in);
                garrisons.put(Integer.valueOf(snap.sectorId), snap);
            }
        }
    }
}
