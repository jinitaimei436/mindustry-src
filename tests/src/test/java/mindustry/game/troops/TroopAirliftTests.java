package mindustry.game.troops;

import mindustry.core.*;
import mindustry.game.troops.TroopAirliftMath.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-Java JUnit 5 unit tests for the v3.9 Serpulo Cross-Sector Troop Airlift System:
 * version tag, multi-core additive Command Points, 3-tier launch pad rules & validation,
 * super-linear distance cost scaling & beachhead modifiers, 3-factor travel time formula,
 * Safe vs Fast BFS routing (5- and 6-neighbor compatible), locked-route mid-flight AA evaluation,
 * 6-tier threat classification & HP floors/shootdown, bidirectional CP overflow culling,
 * and binary persistence roundtrip.
 */
public class TroopAirliftTests{

    @Test
    void versionForkTagIsV39(){
        assertEquals("v3.9", Version.forkVersion);
    }

    @Test
    void sectorCommandPointCapStacksAdditivelyAcrossMultipleCores(){
        assertEquals(20, TroopAirliftMath.computeSectorCommandCap(0, 0, 0));
        assertEquals(40, TroopAirliftMath.computeSectorCommandCap(1, 0, 0));
        assertEquals(50, TroopAirliftMath.computeSectorCommandCap(0, 1, 0));
        assertEquals(60, TroopAirliftMath.computeSectorCommandCap(0, 0, 1));

        // Multi-core full additive stacking with zero diminishing returns:
        // 20 base + 2*20 Shard + 1*30 Foundation + 2*40 Nucleus = 170
        assertEquals(170, TroopAirliftMath.computeSectorCommandCap(2, 1, 2));

        // Negative counts safely clamped to 0
        assertEquals(20, TroopAirliftMath.computeSectorCommandCap(-3, -1, -2));
    }

    @Test
    void unitTierCommandPointsMatchSpecification(){
        assertEquals(0, TroopAirliftMath.commandPointsForTier(0));
        assertEquals(1, TroopAirliftMath.commandPointsForTier(1));
        assertEquals(2, TroopAirliftMath.commandPointsForTier(2));
        assertEquals(4, TroopAirliftMath.commandPointsForTier(3));
        assertEquals(10, TroopAirliftMath.commandPointsForTier(4));
        assertEquals(20, TroopAirliftMath.commandPointsForTier(5));
    }

    @Test
    void padTierSpecsAndPayloadValidationEnforceCapsTiersAndSpecialRestrictions(){
        // Spec constants verification
        assertEquals(3, PadTier.LOW.footprintSize);
        assertEquals(20, PadTier.LOW.regularCapCp);
        assertEquals(10, PadTier.LOW.beachheadCapCp);
        assertEquals(240f, PadTier.LOW.chargePowerPerSec, 1e-4f);
        assertEquals(10f, PadTier.LOW.chargeSeconds, 1e-4f);
        assertEquals(45f, PadTier.LOW.cooldownSeconds, 1e-4f);
        assertEquals(25f, PadTier.LOW.prepSeconds, 1e-4f);
        assertEquals(1.25f, PadTier.LOW.propFactor, 1e-4f);

        assertEquals(4, PadTier.HIGH.footprintSize);
        assertEquals(40, PadTier.HIGH.regularCapCp);
        assertEquals(20, PadTier.HIGH.beachheadCapCp);
        assertEquals(600f, PadTier.HIGH.chargePowerPerSec, 1e-4f);
        assertEquals(12f, PadTier.HIGH.chargeSeconds, 1e-4f);
        assertEquals(75f, PadTier.HIGH.cooldownSeconds, 1e-4f);
        assertEquals(20f, PadTier.HIGH.prepSeconds, 1e-4f);
        assertEquals(1.00f, PadTier.HIGH.propFactor, 1e-4f);

        assertEquals(5, PadTier.SPECIAL.footprintSize);
        assertEquals(20, PadTier.SPECIAL.regularCapCp);
        assertEquals(0, PadTier.SPECIAL.beachheadCapCp);
        assertFalse(PadTier.SPECIAL.allowFormation);
        assertFalse(PadTier.SPECIAL.allowBeachhead);
        assertEquals(2500f, PadTier.SPECIAL.chargePowerPerSec, 1e-4f);
        assertEquals(15f, PadTier.SPECIAL.chargeSeconds, 1e-4f);
        assertEquals(150f, PadTier.SPECIAL.cooldownSeconds, 1e-4f);
        assertEquals(40f, PadTier.SPECIAL.prepSeconds, 1e-4f);
        assertEquals(1.15f, PadTier.SPECIAL.propFactor, 1e-4f);

        // Low pad: accepts T3 formation up to 20 CP regular, 10 CP beachhead; rejects T4
        ArrayList<TroopUnitSnapshot> lowPayload = new ArrayList<TroopUnitSnapshot>();
        for(int i = 0; i < 3; i++){
            lowPayload.add(new TroopUnitSnapshot("fortress", 3, 900f, 900f, 0.43f, false, false, 7));
        }
        assertNull(TroopAirliftMath.validatePayload(PadTier.LOW, LaunchMode.REGULAR, lowPayload));
        assertNotNull(TroopAirliftMath.validatePayload(PadTier.LOW, LaunchMode.BEACHHEAD, lowPayload));

        ArrayList<TroopUnitSnapshot> t4Payload = new ArrayList<TroopUnitSnapshot>();
        t4Payload.add(new TroopUnitSnapshot("scepter", 4, 9000f, 9000f, 0.36f, false, false, 0));
        assertNotNull(TroopAirliftMath.validatePayload(PadTier.LOW, LaunchMode.REGULAR, t4Payload));
        assertNull(TroopAirliftMath.validatePayload(PadTier.HIGH, LaunchMode.REGULAR, t4Payload));
        assertNull(TroopAirliftMath.validatePayload(PadTier.HIGH, LaunchMode.BEACHHEAD, t4Payload));

        // Special pad: fixed 1 solo T5, forbids formations, forbids beachhead, forbids T4
        ArrayList<TroopUnitSnapshot> soloT5 = new ArrayList<TroopUnitSnapshot>();
        soloT5.add(new TroopUnitSnapshot("reign", 5, 24000f, 24000f, 0.4f, false, false, 0));
        assertNull(TroopAirliftMath.validatePayload(PadTier.SPECIAL, LaunchMode.REGULAR, soloT5));
        assertNotNull(TroopAirliftMath.validatePayload(PadTier.SPECIAL, LaunchMode.BEACHHEAD, soloT5));

        ArrayList<TroopUnitSnapshot> formedT5 = new ArrayList<TroopUnitSnapshot>();
        formedT5.add(new TroopUnitSnapshot("reign", 5, 24000f, 24000f, 0.4f, false, false, 3));
        assertNotNull(TroopAirliftMath.validatePayload(PadTier.SPECIAL, LaunchMode.REGULAR, formedT5));

        ArrayList<TroopUnitSnapshot> twoT5 = new ArrayList<TroopUnitSnapshot>();
        twoT5.add(new TroopUnitSnapshot("reign", 5, 24000f, 24000f, 0.4f, false, false, 0));
        twoT5.add(new TroopUnitSnapshot("toxopid", 5, 22000f, 22000f, 0.5f, false, false, 0));
        assertNotNull(TroopAirliftMath.validatePayload(PadTier.SPECIAL, LaunchMode.REGULAR, twoT5));
    }

    @Test
    void superLinearDistanceMultiplierAndLaunchCostsMatchSpecification(){
        assertEquals(1.00, TroopAirliftMath.distanceMultiplier(1), 1e-6);
        assertEquals(1.40, TroopAirliftMath.distanceMultiplier(2), 1e-6);
        assertEquals(1.90, TroopAirliftMath.distanceMultiplier(3), 1e-6);
        assertEquals(3.20, TroopAirliftMath.distanceMultiplier(5), 1e-6);

        // Low pad, 10 CP, 1 hop Regular: 150 silicon + 100 graphite
        LinkedHashMap<String, Integer> low1 = TroopAirliftMath.computeLaunchCost(PadTier.LOW, 10, 1, LaunchMode.REGULAR);
        assertEquals(150, low1.get("silicon").intValue());
        assertEquals(100, low1.get("graphite").intValue());

        // Low pad, 10 CP, 1 hop Beachhead (x2.0): 300 silicon + 200 graphite
        LinkedHashMap<String, Integer> lowBeach = TroopAirliftMath.computeLaunchCost(PadTier.LOW, 10, 1, LaunchMode.BEACHHEAD);
        assertEquals(300, lowBeach.get("silicon").intValue());
        assertEquals(200, lowBeach.get("graphite").intValue());

        // High pad, 20 CP, 3 hops Regular (M_dist = 1.90):
        // silicon: 20*20*1.9 = 760, titanium: 15*20*1.9 = 570, plastanium: 6*20*1.9 = 228
        LinkedHashMap<String, Integer> high3 = TroopAirliftMath.computeLaunchCost(PadTier.HIGH, 20, 3, LaunchMode.REGULAR);
        assertEquals(760, high3.get("silicon").intValue());
        assertEquals(570, high3.get("titanium").intValue());
        assertEquals(228, high3.get("plastanium").intValue());

        // Special pad, fixed cost scaled by M_dist(2) = 1.40:
        // 600*1.4=840, 300*1.4=420, 120*1.4=168, 60*1.4=84
        LinkedHashMap<String, Integer> special2 = TroopAirliftMath.computeLaunchCost(PadTier.SPECIAL, 20, 2, LaunchMode.REGULAR);
        assertEquals(840, special2.get("silicon").intValue());
        assertEquals(420, special2.get("plastanium").intValue());
        assertEquals(168, special2.get("surge-alloy").intValue());
        assertEquals(84, special2.get("phase-fabric").intValue());
    }

    @Test
    void threeFactorTravelTimeClampsSpeedAndAppliesPrepPadAndModeModifiers(){
        // v = 1.0 -> T_hop = 36 / sqrt(1.0) = 36.0s
        assertEquals(36.0f, TroopAirliftMath.computeHopSeconds(1.0f), 1e-4f);

        // Speed below 0.30 clamps to 0.30
        float expectedMinClamp = (float)(36.0 / Math.sqrt(0.30));
        assertEquals(expectedMinClamp, TroopAirliftMath.computeHopSeconds(0.05f), 1e-4f);
        assertEquals(expectedMinClamp, TroopAirliftMath.computeHopSeconds(0.30f), 1e-4f);

        // Speed above 2.70 clamps to 2.70
        float expectedMaxClamp = (float)(36.0 / Math.sqrt(2.70));
        assertEquals(expectedMaxClamp, TroopAirliftMath.computeHopSeconds(4.50f), 1e-4f);
        assertEquals(expectedMaxClamp, TroopAirliftMath.computeHopSeconds(2.70f), 1e-4f);

        // High pad (prep=20, K=1.0), D=2, v_min=1.0 -> Regular = 20 + 2*36*1.0 = 92s
        assertEquals(92.0f, TroopAirliftMath.computeTotalTravelSeconds(PadTier.HIGH, 2, 1.0f, LaunchMode.REGULAR), 1e-4f);

        // Low pad (prep=25, K=1.25), D=1, v_min=1.0 -> Beachhead = (25 + 1*36*1.25) * 2.0 = 140s
        assertEquals(140.0f, TroopAirliftMath.computeTotalTravelSeconds(PadTier.LOW, 1, 1.0f, LaunchMode.BEACHHEAD), 1e-4f);

        // Special pad (prep=40, K=1.15), D=3, v_min=0.4 -> 40 + 3*(36/sqrt(0.4))*1.15
        float expectedSpecial = 40f + 3f * (float)(36.0 / Math.sqrt(0.4)) * 1.15f;
        assertEquals(expectedSpecial, TroopAirliftMath.computeTotalTravelSeconds(PadTier.SPECIAL, 3, 0.4f, LaunchMode.REGULAR), 1e-3f);
    }

    @Test
    void safeAndFastBfsRoutingSupportPentagonAndHexagonAdjacency(){
        // Graph layout:
        // Sector 1 (Pentagon with 5 neighbors: 2, 3, 4, 5, 6)
        // Fast path 1 -> 2 (enemy) -> 7 (safe) = 2 hops
        // Safe path 1 -> 3 (safe) -> 8 (safe) -> 7 (safe) = 3 hops
        // Sector 4 is friendly but underAttack -> blocked for Safe BFS
        HashMap<Integer, SectorNodeInfo> graph = new HashMap<Integer, SectorNodeInfo>();
        graph.put(Integer.valueOf(1), new SectorNodeInfo(1, true, false, false, 0.1f, false, new int[]{2, 3, 4, 5, 6}));
        graph.put(Integer.valueOf(2), new SectorNodeInfo(2, false, true, false, 0.8f, false, new int[]{1, 7}));
        graph.put(Integer.valueOf(3), new SectorNodeInfo(3, true, false, false, 0.2f, false, new int[]{1, 8}));
        graph.put(Integer.valueOf(4), new SectorNodeInfo(4, true, false, true, 0.6f, false, new int[]{1, 7}));
        graph.put(Integer.valueOf(5), new SectorNodeInfo(5, false, false, false, 0.1f, false, new int[]{1}));
        graph.put(Integer.valueOf(6), new SectorNodeInfo(6, false, false, false, 0.1f, false, new int[]{1}));
        graph.put(Integer.valueOf(7), new SectorNodeInfo(7, true, false, false, 0.2f, false, new int[]{2, 4, 8}));
        graph.put(Integer.valueOf(8), new SectorNodeInfo(8, true, false, false, 0.2f, false, new int[]{3, 7}));

        assertTrue(TroopAirliftMath.isAdjacent(graph, 1, 2));
        assertTrue(TroopAirliftMath.isAdjacent(graph, 1, 6));
        assertFalse(TroopAirliftMath.isAdjacent(graph, 1, 7));

        int[] safe = TroopAirliftMath.findRoute(graph, 1, 7, RouteMode.SAFE);
        assertArrayEquals(new int[]{1, 3, 8, 7}, safe);

        int[] fast = TroopAirliftMath.findRoute(graph, 1, 7, RouteMode.FAST);
        assertNotNull(fast);
        assertEquals(3, fast.length);
        assertEquals(1, fast[0]);
        assertEquals(7, fast[2]);

        // If safe relay 8 falls under attack, Safe BFS returns null while Fast BFS still succeeds
        graph.put(Integer.valueOf(8), new SectorNodeInfo(8, true, false, true, 0.3f, false, new int[]{3, 7}));
        assertNull(TroopAirliftMath.findRoute(graph, 1, 7, RouteMode.SAFE));
        assertNotNull(TroopAirliftMath.findRoute(graph, 1, 7, RouteMode.FAST));
    }

    @Test
    void sixTierThreatClassificationAaDamageFloorsShootdownAndMidFlightLockedRoute(){
        assertEquals(ThreatTier.LOW, TroopAirliftMath.classifyThreat(0.0f, false));
        assertEquals(ThreatTier.LOW, TroopAirliftMath.classifyThreat(0.249f, false));
        assertEquals(ThreatTier.MEDIUM, TroopAirliftMath.classifyThreat(0.25f, false));
        assertEquals(ThreatTier.HIGH, TroopAirliftMath.classifyThreat(0.50f, false));
        assertEquals(ThreatTier.EXTREME, TroopAirliftMath.classifyThreat(0.75f, false));
        assertEquals(ThreatTier.ERADICATION, TroopAirliftMath.classifyThreat(1.00f, false));
        assertEquals(ThreatTier.UNREASONABLE, TroopAirliftMath.classifyThreat(0.10f, true));

        // Exact formula check: maxHp=1000, curHp=500, MEDIUM (r=0.14) -> dmg = (600 + 200)*0.14 = 112 -> hp = 388
        TroopUnitSnapshot u = new TroopUnitSnapshot("mace", 2, 500f, 1000f, 0.5f, false, false, 0);
        assertTrue(TroopAirliftMath.applyAaDamageToUnit(u, ThreatTier.MEDIUM));
        assertEquals(388f, u.health, 1e-3f);
        assertTrue(u.orbitalDamaged);
        assertTrue(u.alive);

        // Low/Med/High floor enforcement: repeated LOW volleys never drop below 20% maxHp (200)
        TroopUnitSnapshot floored = new TroopUnitSnapshot("fortress", 3, 220f, 1000f, 0.4f, false, false, 0);
        for(int i = 0; i < 10; i++){
            assertTrue(TroopAirliftMath.applyAaDamageToUnit(floored, ThreatTier.LOW));
        }
        assertEquals(200f, floored.health, 1e-3f);
        assertTrue(floored.alive);

        // Floor must never heal a unit already below the floor
        TroopUnitSnapshot alreadyLow = new TroopUnitSnapshot("dagger", 1, 50f, 1000f, 0.5f, false, false, 0);
        assertTrue(TroopAirliftMath.applyAaDamageToUnit(alreadyLow, ThreatTier.LOW));
        assertEquals(50f, alreadyLow.health, 1e-3f);

        // Extreme / Eradication / Unreasonable have no floor and can shoot down in-flight
        TroopUnitSnapshot doomed = new TroopUnitSnapshot("flare", 1, 100f, 100f, 3.0f, true, false, 0);
        // Hit 1: 100 - (60 + 40) * 0.55 = 45.0
        assertTrue(TroopAirliftMath.applyAaDamageToUnit(doomed, ThreatTier.UNREASONABLE));
        assertEquals(45f, doomed.health, 1e-3f);
        // Hit 2: 45 - (60 + 18) * 0.55 = 2.1
        assertTrue(TroopAirliftMath.applyAaDamageToUnit(doomed, ThreatTier.UNREASONABLE));
        assertEquals(2.1f, doomed.health, 1e-3f);
        // Hit 3: 2.1 - (60 + 0.84) * 0.55 < 0 -> shot down
        assertFalse(TroopAirliftMath.applyAaDamageToUnit(doomed, ThreatTier.UNREASONABLE));
        assertFalse(doomed.alive);
        assertEquals(0f, doomed.health, 1e-4f);

        // Locked Safe-Route mission polls hop thresholds even if a safe relay turns hostile mid-flight
        TroopAirliftMission mission = new TroopAirliftMission();
        mission.lockedRoute = new int[]{1, 3, 8, 7};
        mission.totalDurationSeconds = 90f;
        mission.units.add(new TroopUnitSnapshot("scepter", 4, 9000f, 9000f, 0.36f, false, false, 0));

        mission.elapsedSeconds = 20f;
        assertEquals(-1, mission.pollDueAaHopIndex());
        assertFalse(mission.shouldShowArrivalBanner()); // 70s remaining

        mission.elapsedSeconds = 30f; // progress 1/3 -> hop index 1 (sector 3) due; remaining 60s -> banner!
        assertEquals(1, mission.pollDueAaHopIndex());
        assertEquals(-1, mission.pollDueAaHopIndex());
        assertTrue(mission.shouldShowArrivalBanner());

        // Suppose sector 8 turns under-attack mid-flight at progress 2/3 -> AA triggers on locked route
        mission.elapsedSeconds = 60f;
        assertEquals(2, mission.pollDueAaHopIndex());
        TroopAirliftMath.resolveHopAirDefense(mission.units, ThreatTier.HIGH);
        assertTrue(mission.units.get(0).orbitalDamaged);
        assertTrue(mission.units.get(0).health < 9000f);
    }

    @Test
    void bidirectionalCommandPointOverflowCullingRespectsStrictPriorityOrder(){
        // Existing units (total 12 CP + 0 CP core unit):
        // key 10: T0 core unit (0 CP) -> MUST NEVER BE CULLED
        // key 11: T1 solo 80% HP (1 CP)
        // key 12: T1 solo 40% HP (1 CP) -> culled 1st (lowest tier, solo, lowest HP%)
        // key 13: T1 formation 20% HP (1 CP) -> culled 3rd (formation beats solo at same tier)
        // key 14: T1 player-controlled 10% HP (1 CP) -> culled 4th (player-controlled last at same tier)
        // key 15: T3 formation 50% HP (4 CP)
        // key 16: T3 solo 90% HP (4 CP) -> culled 5th if needed
        ArrayList<CullCandidate> existing = new ArrayList<CullCandidate>();
        existing.add(new CullCandidate(10, 0, 0, false, 0.10f, true));
        existing.add(new CullCandidate(11, 1, 1, false, 0.80f, false));
        existing.add(new CullCandidate(12, 1, 1, false, 0.40f, false));
        existing.add(new CullCandidate(13, 1, 1, true, 0.20f, false));
        existing.add(new CullCandidate(14, 1, 1, false, 0.10f, true));
        existing.add(new CullCandidate(15, 3, 4, true, 0.50f, false));
        existing.add(new CullCandidate(16, 3, 4, false, 0.90f, false));

        // Arriving: 10 CP (1 T4). Sector cap = 19 -> total 22 -> need to shed 3 CP from existing.
        ArrayList<CullCandidate> arriving = new ArrayList<CullCandidate>();
        arriving.add(new CullCandidate(0, 4, 10, false, 1.0f, false));

        OverflowCullResult res = TroopAirliftMath.resolveOverflowCull(existing, arriving, 19);
        assertEquals(3, res.culledExistingKeys.size());
        assertEquals(12, res.culledExistingKeys.get(0).intValue()); // T1 solo 40%
        assertEquals(11, res.culledExistingKeys.get(1).intValue()); // T1 solo 80%
        assertEquals(13, res.culledExistingKeys.get(2).intValue()); // T1 formation 20%
        assertTrue(res.culledArrivingKeys.isEmpty());
        assertEquals(9, res.remainingExistingCp);
        assertEquals(10, res.remainingArrivingCp);

        // Phase 2: Arriving alone (24 CP) exceeds coreless beachhead sector cap (20 CP):
        // all existing culled first, then lowest-tier arriving unit culled.
        ArrayList<CullCandidate> smallExisting = new ArrayList<CullCandidate>();
        smallExisting.add(new CullCandidate(1, 1, 1, false, 1.0f, false));

        ArrayList<CullCandidate> bigArriving = new ArrayList<CullCandidate>();
        bigArriving.add(new CullCandidate(0, 4, 10, false, 1.0f, false));
        bigArriving.add(new CullCandidate(1, 4, 10, false, 1.0f, false));
        bigArriving.add(new CullCandidate(2, 3, 4, false, 0.6f, false));

        OverflowCullResult res2 = TroopAirliftMath.resolveOverflowCull(smallExisting, bigArriving, 20);
        assertEquals(1, res2.culledExistingKeys.size());
        assertEquals(1, res2.culledArrivingKeys.size());
        assertEquals(2, res2.culledArrivingKeys.get(0).intValue()); // T3 culled before T4s
        assertEquals(0, res2.remainingExistingCp);
        assertEquals(20, res2.remainingArrivingCp);
    }

    @Test
    void planetAirliftStateBinaryRoundtripPreservesMissionsPendingAndGarrisons() throws IOException{
        PlanetAirliftState original = new PlanetAirliftState();
        original.nextMissionId = 9;

        TroopAirliftMission m = new TroopAirliftMission();
        m.missionId = 8;
        m.planetName = "serpulo";
        m.originSectorId = 15;
        m.targetSectorId = 42;
        m.padTier = PadTier.HIGH;
        m.launchMode = LaunchMode.REGULAR;
        m.routeMode = RouteMode.FAST;
        m.lockedRoute = new int[]{15, 22, 42};
        m.nextAaHopIndex = 2;
        m.elapsedSeconds = 41.5f;
        m.totalDurationSeconds = 95.0f;
        m.arrivalBannerTriggered = true;
        TroopUnitSnapshot u = new TroopUnitSnapshot("vela", 4, 6400f, 8200f, 0.44f, false, false, 12);
        u.orbitalDamaged = true;
        m.units.add(u);
        original.activeMissions.add(m);

        PendingArrivalBatch batch = new PendingArrivalBatch();
        batch.originSectorId = 15;
        batch.launchMode = LaunchMode.BEACHHEAD;
        batch.units.add(new TroopUnitSnapshot("zenith", 3, 700f, 700f, 1.8f, true, false, 0));
        original.queuePendingArrival(77, batch);

        SectorGarrisonSnapshot g = original.getOrCreateGarrison(15);
        g.shardCores = 1;
        g.foundationCores = 1;
        g.nucleusCores = 0;
        g.recomputeCap();
        g.usedCp = 18;
        g.livingUnits = 6;
        g.formationCount = 2;
        g.deepWaterState = 1;
        g.tierCounts[3] = 4;
        g.tierCounts[1] = 2;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        original.writeToStream(dos);
        dos.flush();

        PlanetAirliftState restored = new PlanetAirliftState();
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        restored.readFromStream(dis);

        assertEquals(9, restored.nextMissionId);
        assertEquals(1, restored.activeMissions.size());
        TroopAirliftMission rm = restored.activeMissions.get(0);
        assertEquals(8, rm.missionId);
        assertEquals(PadTier.HIGH, rm.padTier);
        assertEquals(RouteMode.FAST, rm.routeMode);
        assertArrayEquals(new int[]{15, 22, 42}, rm.lockedRoute);
        assertEquals(2, rm.nextAaHopIndex);
        assertEquals(41.5f, rm.elapsedSeconds, 1e-4f);
        assertTrue(rm.arrivalBannerTriggered);
        assertEquals(1, rm.units.size());
        assertEquals("vela", rm.units.get(0).unitTypeName);
        assertTrue(rm.units.get(0).orbitalDamaged);
        assertEquals(12, rm.units.get(0).formationGroupId);

        assertTrue(restored.hasPendingBeachhead(77));
        assertTrue(restored.hasBeachheadPresence(77));
        assertEquals(1, restored.getPendingArrivals(77).size());

        SectorGarrisonSnapshot rg = restored.garrisons.get(Integer.valueOf(15));
        assertNotNull(rg);
        assertEquals(70, rg.commandCap); // 20 + 20 + 30
        assertEquals(18, rg.usedCp);
        assertEquals(1, rg.deepWaterState);
        assertEquals(4, rg.tierCounts[3]);
    }
}
