package mindustry.game.troops;

import arc.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.ai.formation.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.game.troops.TroopAirliftMath.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.storage.CoreBlock.*;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

import static mindustry.Vars.*;

/**
 * Central campaign manager for the V3.9 Serpulo Cross-Sector Troop Airlift System.
 *
 * Responsibilities:
 * - Persists in-flight airlift missions, offline pending-arrival batches, and sector garrison
 *   snapshots in {@code Core.settings} (Base64 binary stream).
 * - Steps all active airlift missions once per second via {@link mindustry.game.strategy.CrossSectorTroopExtension}.
 * - Evaluates locked-route 6-tier anti-air fire against live sector state on each hop.
 * - Handles 60s arrival banner notifications, core-loss "编队迷失" squad destruction,
 *   terrain-validated physical drop, 4s/45s landing debuffs, FormationManager reconstruction,
 *   bidirectional Command Point overflow culling, and coreless beachhead auto-possession.
 */
public class SectorTroopAirliftManager{
    private static final SectorTroopAirliftManager instance = new SectorTroopAirliftManager();
    private static final String SETTINGS_KEY = "v39-sector-troop-airlift";
    private static final int PERSIST_VERSION = 1;

    private final ObjectMap<String, PlanetAirliftState> planetStates = new ObjectMap<>();

    private boolean initialized = false;
    private boolean dirty = false;
    private int uiRevision = 1;
    private boolean deferForegroundEntry = false;

    /** Transient recent broadcast alert for HUD banner (e.g., "编队迷失"). */
    private String recentAlertText = "";
    private float recentAlertSeconds = 0f;

    public static SectorTroopAirliftManager get(){
        return instance;
    }

    private SectorTroopAirliftManager(){
    }

    public void setDeferForegroundEntry(boolean defer){
        this.deferForegroundEntry = defer;
    }

    public void init(){
        if(initialized) return;
        initialized = true;
        load();

        Events.on(WorldLoadEvent.class, e -> {
            if(!deferForegroundEntry && state != null && state.isCampaign() && state.hasSector()){
                onSectorEnteredForeground(state.getSector());
            }
        });

        Events.on(SaveLoadEvent.class, e -> {
            if(!deferForegroundEntry && !e.isMap && state != null && state.isCampaign() && state.hasSector()){
                onSectorEnteredForeground(state.getSector());
            }
        });

        Events.on(SaveWriteEvent.class, e -> {
            if(state != null && state.isCampaign()){
                if(state.hasSector()){
                    captureLiveSectorSnapshot(state.getSector());
                }
                saveIfDirty(true);
            }
        });

        Events.on(SectorLoseEvent.class, e -> {
            if(e.sector != null){
                onSectorLost(e.sector);
            }
        });
    }

    public int getUiRevision(){
        return uiRevision;
    }

    private void markChanged(){
        uiRevision++;
        dirty = true;
    }

    public PlanetAirliftState getPlanetState(@Nullable Planet planet){
        if(planet == null) return new PlanetAirliftState();
        return getPlanetState(planet.name);
    }

    public PlanetAirliftState getPlanetState(String planetName){
        PlanetAirliftState ps = planetStates.get(planetName);
        if(ps == null){
            ps = new PlanetAirliftState();
            planetStates.put(planetName, ps);
        }
        return ps;
    }

    // ==================== UNIT TIER & CP CLASSIFICATION ====================

    /**
     * Classifies a UnitType into its tier:
     * 0 = player core unit (Alpha/Beta/Gamma/Evoke/Incite/Emanate) or internal/non-combat -> 0 CP;
     * 1..5 = T1..T5 combat/support units.
     */
    public static int classifyUnitTier(@Nullable UnitType type){
        if(type == null || type.internal) return 0;
        if(type == UnitTypes.block || type == UnitTypes.missile
            || type == UnitTypes.assemblyDrone || type == UnitTypes.manifold
            || type == UnitTypes.dummy){
            return 0;
        }
        if(type == UnitTypes.alpha || type == UnitTypes.beta || type == UnitTypes.gamma
            || type == UnitTypes.evoke || type == UnitTypes.incite || type == UnitTypes.emanate){
            return 0;
        }

        // T1
        if(type == UnitTypes.dagger || type == UnitTypes.nova || type == UnitTypes.crawler
            || type == UnitTypes.flare || type == UnitTypes.mono
            || type == UnitTypes.risso || type == UnitTypes.retusa
            || type == UnitTypes.stell || type == UnitTypes.elude || type == UnitTypes.merui){
            return 1;
        }
        // T2
        if(type == UnitTypes.mace || type == UnitTypes.pulsar || type == UnitTypes.atrax
            || type == UnitTypes.horizon || type == UnitTypes.poly
            || type == UnitTypes.minke || type == UnitTypes.oxynoe
            || type == UnitTypes.locus || type == UnitTypes.avert || type == UnitTypes.cleroi){
            return 2;
        }
        // T3
        if(type == UnitTypes.fortress || type == UnitTypes.quasar || type == UnitTypes.spiroct
            || type == UnitTypes.zenith || type == UnitTypes.mega
            || type == UnitTypes.bryde || type == UnitTypes.cyerce
            || type == UnitTypes.precept || type == UnitTypes.obviate || type == UnitTypes.anthicus){
            return 3;
        }
        // T4
        if(type == UnitTypes.scepter || type == UnitTypes.vela || type == UnitTypes.arkyid
            || type == UnitTypes.antumbra || type == UnitTypes.quad
            || type == UnitTypes.sei || type == UnitTypes.aegires
            || type == UnitTypes.vanquish || type == UnitTypes.quell || type == UnitTypes.tecta){
            return 4;
        }
        // T5
        if(type == UnitTypes.reign || type == UnitTypes.corvus || type == UnitTypes.toxopid
            || type == UnitTypes.eclipse || type == UnitTypes.oct
            || type == UnitTypes.omura || type == UnitTypes.navanax
            || type == UnitTypes.conquer || type == UnitTypes.disrupt || type == UnitTypes.collaris){
            return 5;
        }

        // Fallback for custom/modded unit types by max health
        if(type.health >= 9000f) return 5;
        if(type.health >= 2400f) return 4;
        if(type.health >= 900f) return 3;
        if(type.health >= 350f) return 2;
        return 1;
    }

    public static int commandPointsOf(@Nullable UnitType type){
        return TroopAirliftMath.commandPointsForTier(classifyUnitTier(type));
    }

    /** Creates a serializable snapshot of a live unit, preserving exact HP and FormationManager membership. */
    public static TroopUnitSnapshot snapshotUnit(Unit unit){
        int tier = classifyUnitTier(unit.type);
        TacticalFormation f = FormationManager.get().getFormationOf(unit);
        int groupId = f != null ? f.id : 0;
        boolean flying = unit.type.flying || unit.isFlying();
        boolean naval = unit.type.naval;
        return new TroopUnitSnapshot(
            unit.type.name,
            tier,
            unit.health,
            unit.maxHealth,
            unit.type.speed,
            flying,
            naval,
            groupId
        );
    }

    // ==================== SECTOR GRAPH & GARRISON TELEMETRY ====================

    /** Builds the pure-Java adjacency & live-status graph for a planet (5- and 6-neighbor safe). */
    public HashMap<Integer, SectorNodeInfo> buildPlanetGraph(@Nullable Planet planet){
        HashMap<Integer, SectorNodeInfo> map = new HashMap<Integer, SectorNodeInfo>();
        if(planet == null || planet.sectors == null) return map;

        for(int i = 0; i < planet.sectors.size; i++){
            Sector s = planet.sectors.get(i);
            if(s == null) continue;
            Seq<Sector> near = s.near();
            int[] neighbors = new int[near.size];
            for(int j = 0; j < near.size; j++){
                neighbors[j] = near.get(j).id;
            }
            boolean unreasonable = s.preset != null && s.preset.difficulty == 13;
            SectorNodeInfo info = new SectorNodeInfo(
                s.id,
                s.hasBase(),
                s.hasEnemyBase(),
                s.isAttacked(),
                s.threat,
                unreasonable,
                neighbors
            );
            map.put(Integer.valueOf(s.id), info);
        }
        return map;
    }

    /** Computes the live Command Point cap of the active foreground sector from actual placed cores. */
    public int computeLiveSectorCommandCap(){
        if(state == null || state.rules == null || state.rules.defaultTeam == null){
            return TroopAirliftMath.BASE_NO_CORE_CP;
        }
        Seq<CoreBuild> cores = state.rules.defaultTeam.cores();
        int shards = 0, foundations = 0, nuclei = 0;
        for(int i = 0; i < cores.size; i++){
            CoreBuild core = cores.get(i);
            if(core == null || !core.isValid()) continue;
            if(core.block == Blocks.coreNucleus){
                nuclei++;
            }else if(core.block == Blocks.coreFoundation){
                foundations++;
            }else{
                shards++;
            }
        }
        return TroopAirliftMath.computeSectorCommandCap(shards, foundations, nuclei);
    }

    /** Counts live friendly combat units' total CP in the foreground sector. */
    public int computeLiveSectorUsedCp(){
        if(state == null || state.rules == null || state.rules.defaultTeam == null || Groups.unit == null){
            return 0;
        }
        Team team = state.rules.defaultTeam;
        int total = 0;
        for(Unit u : Groups.unit){
            if(u != null && u.isValid() && !u.dead && u.team == team){
                total += commandPointsOf(u.type);
            }
        }
        return total;
    }

    /** Captures live cores, garrison CP, formations, beachhead flag, and deep-water terrain state. */
    public SectorGarrisonSnapshot captureLiveSectorSnapshot(@Nullable Sector sector){
        if(sector == null || sector.planet == null){
            return new SectorGarrisonSnapshot(-1);
        }
        PlanetAirliftState ps = getPlanetState(sector.planet);
        SectorGarrisonSnapshot snap = ps.getOrCreateGarrison(sector.id);
        if(!sector.isBeingPlayed() || state == null || state.rules == null || state.rules.defaultTeam == null){
            return snap;
        }

        Team team = state.rules.defaultTeam;
        Seq<CoreBuild> cores = team.cores();
        int shards = 0, foundations = 0, nuclei = 0;
        for(int i = 0; i < cores.size; i++){
            CoreBuild core = cores.get(i);
            if(core == null || !core.isValid()) continue;
            if(core.block == Blocks.coreNucleus){
                nuclei++;
            }else if(core.block == Blocks.coreFoundation){
                foundations++;
            }else{
                shards++;
            }
        }
        snap.shardCores = shards;
        snap.foundationCores = foundations;
        snap.nucleusCores = nuclei;
        snap.recomputeCap();

        for(int i = 0; i < 6; i++) snap.tierCounts[i] = 0;
        int usedCp = 0;
        int living = 0;
        int combatUnits = 0;
        if(Groups.unit != null){
            for(Unit u : Groups.unit){
                if(u == null || !u.isValid() || u.dead || u.team != team) continue;
                int tier = classifyUnitTier(u.type);
                if(tier >= 0 && tier < 6){
                    snap.tierCounts[tier]++;
                }
                int cp = TroopAirliftMath.commandPointsForTier(tier);
                usedCp += cp;
                living++;
                if(tier >= 1) combatUnits++;
            }
        }
        snap.usedCp = usedCp;
        snap.livingUnits = living;

        Seq<TacticalFormation> forms = new Seq<>();
        FormationManager.get().getTeamFormations(team, forms);
        snap.formationCount = forms.size;

        int totalCores = shards + foundations + nuclei;
        snap.beachheadActive = (totalCores == 0 && (combatUnits > 0 || ps.hasPendingBeachhead(sector.id)));

        if(world != null && world.tiles != null && !world.isGenerating()){
            boolean foundDeep = false;
            for(int x = 0; x < world.width() && !foundDeep; x++){
                for(int y = 0; y < world.height(); y++){
                    Tile tile = world.tile(x, y);
                    if(tile != null && !tile.solid() && tile.floor().isLiquid && tile.floor().isDeep()){
                        foundDeep = true;
                        break;
                    }
                }
            }
            snap.deepWaterState = (byte)(foundDeep ? 1 : 0);
        }

        markChanged();
        return snap;
    }

    /** Returns best-available garrison snapshot for PlanetDialog Airlift View (including pending arrivals). */
    public SectorGarrisonSnapshot getSectorGarrison(@Nullable Sector sector){
        if(sector == null || sector.planet == null) return new SectorGarrisonSnapshot(-1);
        if(sector.isBeingPlayed()){
            return captureLiveSectorSnapshot(sector);
        }
        PlanetAirliftState ps = getPlanetState(sector.planet);
        SectorGarrisonSnapshot snap = ps.garrisons.get(Integer.valueOf(sector.id));
        if(snap != null){
            return snap;
        }
        SectorGarrisonSnapshot fallback = new SectorGarrisonSnapshot(sector.id);
        if(sector.hasBase()){
            Block coreType = sector.info.bestCoreType;
            if(coreType == Blocks.coreNucleus){
                fallback.nucleusCores = 1;
            }else if(coreType == Blocks.coreFoundation){
                fallback.foundationCores = 1;
            }else{
                fallback.shardCores = 1;
            }
            fallback.recomputeCap();
        }else{
            fallback.recomputeCap();
        }
        fallback.beachheadActive = ps.hasPendingBeachhead(sector.id);
        return fallback;
    }

    /** Returns true if sector is known to have NO deep water (for non-blocking pre-launch naval warning). */
    public boolean isKnownWithoutDeepWater(@Nullable Sector sector){
        if(sector == null || sector.planet == null) return false;
        SectorGarrisonSnapshot snap = getSectorGarrison(sector);
        return snap.deepWaterState == 0;
    }

    /** Returns true if sector currently has friendly beachhead troops (live or pending) without a friendly core. */
    public boolean hasFriendlyBeachhead(@Nullable Sector sector){
        if(sector == null || sector.planet == null) return false;
        if(sector.hasBase()) return false;
        PlanetAirliftState ps = getPlanetState(sector.planet);
        if(ps.hasBeachheadPresence(sector.id)) return true;
        if(sector.isBeingPlayed() && state != null && state.rules != null && state.rules.defaultTeam != null && Groups.unit != null){
            Team team = state.rules.defaultTeam;
            for(Unit u : Groups.unit){
                if(u != null && u.isValid() && !u.dead && u.team == team && classifyUnitTier(u.type) >= 1){
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Used by {@link mindustry.core.Logic} to prevent instant gameOver in a coreless beachhead sector
     * as long as at least one friendly combat unit is alive or a beachhead arrival is pending.
     */
    public boolean shouldAllowCorelessBeachheadSurvival(){
        if(state == null || !state.isCampaign() || !state.hasSector()) return false;
        Sector sec = state.getSector();
        if(sec == null || sec.planet == null) return false;
        PlanetAirliftState ps = getPlanetState(sec.planet);
        if(ps.hasPendingBeachhead(sec.id)) return true;
        if(state.rules == null || state.rules.defaultTeam == null || Groups.unit == null) return false;
        Team team = state.rules.defaultTeam;
        for(Unit u : Groups.unit){
            if(u != null && u.isValid() && !u.dead && u.team == team && classifyUnitTier(u.type) >= 1){
                return true;
            }
        }
        return false;
    }

    /**
     * Used by {@link mindustry.core.Control} when entering a sector: returns true if the sector
     * should enter in coreless beachhead mode (no auto-spawned core, auto-possess highest-tier unit).
     */
    public boolean isEnteringCorelessBeachhead(@Nullable Sector sector){
        if(sector == null || sector.planet == null) return false;
        if(sector.hasBase()) return false;
        return getPlanetState(sector.planet).hasBeachheadPresence(sector.id);
    }

    // ==================== MISSION DISPATCH ====================

    /**
     * Dispatches a cross-sector troop airlift mission with a locked route.
     * Caller (TroopLaunchPad) has already verified & consumed conveyor materials, power charge, and cooldown.
     *
     * @return created mission on success, or null if invalid.
     */
    public @Nullable TroopAirliftMission dispatchMission(
        Sector origin,
        Sector target,
        PadTier padTier,
        LaunchMode launchMode,
        RouteMode routeMode,
        int[] lockedRoute,
        List<TroopUnitSnapshot> payloadSnapshots
    ){
        if(origin == null || target == null || origin.planet == null || origin.planet != target.planet) return null;
        if(!origin.hasBase()) return null;
        if(lockedRoute == null || lockedRoute.length < 2) return null;
        if(TroopAirliftMath.validatePayload(padTier, launchMode, payloadSnapshots) != null) return null;

        int hops = lockedRoute.length - 1;
        float minSpeed = TroopAirliftMath.computeMinPayloadSpeed(payloadSnapshots);
        float totalDuration = TroopAirliftMath.computeTotalTravelSeconds(padTier, hops, minSpeed, launchMode);

        PlanetAirliftState ps = getPlanetState(origin.planet);
        TroopAirliftMission mission = new TroopAirliftMission();
        mission.missionId = ps.nextMissionId++;
        mission.planetName = origin.planet.name;
        mission.originSectorId = origin.id;
        mission.targetSectorId = target.id;
        mission.padTier = padTier;
        mission.launchMode = launchMode;
        mission.routeMode = routeMode;
        mission.lockedRoute = new int[lockedRoute.length];
        System.arraycopy(lockedRoute, 0, mission.lockedRoute, 0, lockedRoute.length);
        mission.nextAaHopIndex = 1;
        mission.elapsedSeconds = 0f;
        mission.totalDurationSeconds = Math.max(1f, totalDuration);

        for(int i = 0; i < payloadSnapshots.size(); i++){
            TroopUnitSnapshot snap = payloadSnapshots.get(i);
            if(snap != null && snap.alive){
                mission.units.add(snap.copy());
            }
        }
        if(mission.units.isEmpty()) return null;

        if(mission.shouldShowArrivalBanner()){
            mission.arrivalBannerTriggered = true;
        }

        ps.activeMissions.add(mission);
        markChanged();
        save(true);
        return mission;
    }

    // ==================== 1-SECOND GLOBAL STEPPING ====================

    /** Called once per second per active planet from {@link mindustry.game.strategy.CrossSectorTroopExtension}. */
    public void stepPlanet(@Nullable Planet planet, float dtSeconds){
        if(planet == null || dtSeconds <= 0f) return;

        if(recentAlertSeconds > 0f){
            recentAlertSeconds = Math.max(0f, recentAlertSeconds - dtSeconds);
            if(recentAlertSeconds <= 0f){
                recentAlertText = "";
            }
        }

        PlanetAirliftState ps = getPlanetState(planet);
        boolean changed = false;

        // Keep coreless beachhead auto-possession healthy if player's current unit died while others survive.
        if(state != null && state.isCampaign() && state.isPlaying() && state.hasSector()){
            Sector active = state.getSector();
            if(active != null && active.planet == planet && state.rules.defaultTeam.cores().isEmpty()){
                if(player != null && (player.dead() || player.unit() == null || player.unit().dead)){
                    autoPossessHighestTierFriendlyUnit();
                }
            }
        }

        for(int i = ps.activeMissions.size() - 1; i >= 0; i--){
            TroopAirliftMission m = ps.activeMissions.get(i);
            m.elapsedSeconds += dtSeconds;
            changed = true;

            // 1. Evaluate locked-route hop AA defense dynamically against current sector state
            int dueHop;
            boolean wipedOut = false;
            while((dueHop = m.pollDueAaHopIndex()) >= 0){
                if(dueHop < m.lockedRoute.length){
                    int hopSectorId = m.lockedRoute[dueHop];
                    Sector hopSec = (hopSectorId >= 0 && hopSectorId < planet.sectors.size)
                        ? planet.sectors.get(hopSectorId) : null;
                    if(hopSec != null && (hopSec.hasEnemyBase() || hopSec.isAttacked())){
                        boolean unreasonable = hopSec.preset != null && hopSec.preset.difficulty == 13;
                        ThreatTier threatTier = TroopAirliftMath.classifyThreat(hopSec.threat, unreasonable);
                        int shotDown = TroopAirliftMath.resolveHopAirDefense(m.units, threatTier);
                        if(m.livingUnitCount() <= 0){
                            wipedOut = true;
                            ps.activeMissions.remove(i);
                            broadcastAlert("空投编队飞越 " + hopSec.name() + "（" + threatTier.displayName + "防空）时被全歼！");
                            break;
                        }else if(shotDown > 0 && !headless && ui != null && ui.hudfrag != null){
                            ui.hudfrag.showToast("空投编队途经 " + hopSec.name() + " 遭防空拦截，击落 " + shotDown + " 台");
                        }
                    }
                }
            }
            if(wipedOut) continue;

            // 2. 60s arrival banner notification
            if(!m.arrivalBannerTriggered && m.shouldShowArrivalBanner()){
                m.arrivalBannerTriggered = true;
            }

            // 3. Mission arrival
            if(m.elapsedSeconds >= m.totalDurationSeconds){
                ps.activeMissions.remove(i);
                handleMissionArrival(planet, ps, m);
            }
        }

        if(changed){
            markChanged();
        }
    }

    private void handleMissionArrival(Planet planet, PlanetAirliftState ps, TroopAirliftMission m){
        Sector target = (m.targetSectorId >= 0 && m.targetSectorId < planet.sectors.size)
            ? planet.sectors.get(m.targetSectorId) : null;

        // Regular transfer core-loss check: if target lost its last friendly core mid-flight -> "编队迷失"
        if(m.launchMode == LaunchMode.REGULAR && (target == null || !target.hasBase())){
            String targetName = target != null ? target.name() : ("区块 #" + m.targetSectorId);
            broadcastAlert("编队迷失：目标区块 " + targetName + " 核心已沦陷，整支空投编队已销毁");
            return;
        }

        PendingArrivalBatch batch = new PendingArrivalBatch();
        batch.originSectorId = m.originSectorId;
        batch.launchMode = m.launchMode;
        for(int i = 0; i < m.units.size(); i++){
            TroopUnitSnapshot u = m.units.get(i);
            if(u != null && u.alive){
                batch.units.add(u.copy());
            }
        }
        if(batch.livingCount() <= 0) return;

        if(target != null && target.isBeingPlayed() && state != null && state.isPlaying()){
            deliverBatchToForeground(target, batch);
        }else if(target != null){
            ps.queuePendingArrival(target.id, batch);
            SectorGarrisonSnapshot g = ps.getOrCreateGarrison(target.id);
            if(m.launchMode == LaunchMode.BEACHHEAD && !target.hasBase()){
                g.beachheadActive = true;
                g.livingUnits += batch.livingCount();
                g.usedCp += batch.livingCp();
            }
            if(!headless && ui != null && ui.hudfrag != null){
                ui.hudfrag.showToast("跨区编队已抵达 " + target.name() + "（" + batch.livingCount() + " 台），切入该区块后展开");
            }
        }
    }

    // ==================== FOREGROUND ENTRY, PHYSICAL DROP & AUTO-POSSESSION ====================

    /** Called on WorldLoadEvent / SaveLoadEvent in campaign to flush pending drops and possess beachhead leader. */
    public void onSectorEnteredForeground(@Nullable Sector sector){
        if(sector == null || sector.planet == null || state == null || !state.isCampaign()) return;
        if(world == null || world.tiles == null || world.isGenerating()) return;

        PlanetAirliftState ps = getPlanetState(sector.planet);
        ArrayList<PendingArrivalBatch> pending = ps.pollPendingArrivals(sector.id);
        boolean hadBeachheadDrop = false;

        for(int i = 0; i < pending.size(); i++){
            PendingArrivalBatch batch = pending.get(i);
            if(batch.launchMode == LaunchMode.REGULAR && !sector.hasBase()){
                broadcastAlert("编队迷失：目标区块 " + sector.name() + " 核心已沦陷，待降落编队已销毁");
                continue;
            }
            if(batch.launchMode == LaunchMode.BEACHHEAD){
                hadBeachheadDrop = true;
            }
            deliverBatchToForeground(sector, batch);
        }

        // If entering a coreless beachhead sector (either fresh drop or reloaded save), auto-possess highest-tier unit.
        if(state.rules != null && state.rules.defaultTeam != null && state.rules.defaultTeam.cores().isEmpty()){
            if(hadBeachheadDrop || ps.hasBeachheadPresence(sector.id) || hasLivingCombatUnits(state.rules.defaultTeam)){
                Core.app.post(this::autoPossessHighestTierFriendlyUnit);
            }
        }

        captureLiveSectorSnapshot(sector);
        saveIfDirty(false);
    }

    private boolean hasLivingCombatUnits(Team team){
        if(Groups.unit == null || team == null) return false;
        for(Unit u : Groups.unit){
            if(u != null && u.isValid() && !u.dead && u.team == team && classifyUnitTier(u.type) >= 1){
                return true;
            }
        }
        return false;
    }

    /**
     * Executes physical terrain drop, bidirectional CP overflow culling, landing debuffs,
     * and TacticalFormation reconstruction in the currently played sector.
     */
    public void deliverBatchToForeground(Sector sector, PendingArrivalBatch batch){
        if(sector == null || batch == null || batch.livingCount() <= 0) return;
        if(state == null || state.rules == null || state.rules.defaultTeam == null || world == null || world.tiles == null) return;

        Team team = state.rules.defaultTeam;
        int sectorCap = computeLiveSectorCommandCap();

        // 1. Collect existing friendly units for bidirectional CP overflow check
        ArrayList<CullCandidate> existingCandidates = new ArrayList<CullCandidate>();
        IntMap<Unit> existingById = new IntMap<>();
        if(Groups.unit != null){
            for(Unit u : Groups.unit){
                if(u == null || !u.isValid() || u.dead || u.team != team) continue;
                int tier = classifyUnitTier(u.type);
                int cp = TroopAirliftMath.commandPointsForTier(tier);
                if(cp <= 0) continue;
                boolean inForm = FormationManager.get().getFormationOf(u) != null;
                boolean isPlayerCtrl = u.isPlayer() || (player != null && player.unit() == u);
                existingCandidates.add(new CullCandidate(u.id, tier, cp, inForm, u.healthf(), isPlayerCtrl));
                existingById.put(u.id, u);
            }
        }

        // 2. Collect arriving units
        ArrayList<CullCandidate> arrivingCandidates = new ArrayList<CullCandidate>();
        for(int i = 0; i < batch.units.size(); i++){
            TroopUnitSnapshot snap = batch.units.get(i);
            if(snap == null || !snap.alive) continue;
            arrivingCandidates.add(new CullCandidate(
                i,
                snap.tier,
                snap.commandPoints,
                snap.formationGroupId > 0,
                snap.healthRatio(),
                false
            ));
        }

        // 3. Resolve bidirectional CP overflow cull
        OverflowCullResult cull = TroopAirliftMath.resolveOverflowCull(existingCandidates, arrivingCandidates, sectorCap);
        for(int i = 0; i < cull.culledExistingKeys.size(); i++){
            Unit victim = existingById.get(cull.culledExistingKeys.get(i).intValue());
            if(victim != null && !victim.dead){
                Fx.blastExplosion.at(victim.x, victim.y);
                victim.remove();
            }
        }

        IntSet culledArrivingSet = new IntSet();
        for(int i = 0; i < cull.culledArrivingKeys.size(); i++){
            culledArrivingSet.add(cull.culledArrivingKeys.get(i).intValue());
        }

        // 4. Prepare drop anchors
        Seq<Tile> anchors = new Seq<>();
        boolean isBeachhead = batch.launchMode == LaunchMode.BEACHHEAD || team.cores().isEmpty();
        if(!isBeachhead){
            CoreBuild primary = team.core();
            if(primary != null && primary.tile != null){
                anchors.add(primary.tile);
            }
            Seq<CoreBuild> cores = team.cores();
            for(int i = 0; i < cores.size; i++){
                CoreBuild c = cores.get(i);
                if(c != null && c != primary && c.tile != null){
                    anchors.add(c.tile);
                }
            }
        }
        if(anchors.isEmpty()){
            Tile beachTile = findBeachheadSpawnTile(sector);
            if(beachTile != null){
                anchors.add(beachTile);
            }
        }
        int searchRadius = isBeachhead
            ? TroopAirliftMath.BEACHHEAD_DROP_RADIUS_TILES
            : TroopAirliftMath.REGULAR_DROP_RADIUS_TILES;

        // 5. Spawn each surviving, non-culled unit at a validated terrain position
        IntMap<Seq<Unit>> formationGroups = new IntMap<>();
        int spawnedCount = 0;
        int terrainDestroyed = 0;
        Tile fallbackAnchor = anchors.any() ? anchors.first() : world.tile(world.width() / 2, world.height() / 2);

        for(int i = 0; i < batch.units.size(); i++){
            TroopUnitSnapshot snap = batch.units.get(i);
            if(snap == null || !snap.alive || culledArrivingSet.contains(i)) continue;
            UnitType type = content.unit(snap.unitTypeName);
            if(type == null) continue;

            Vec2 dropPos = findDropPosition(type, snap, anchors, searchRadius, i);
            if(dropPos == null){
                // No legal terrain spot within radius -> non-friendly-fire explosion & destroy
                float ex = fallbackAnchor != null ? fallbackAnchor.worldx() : world.unitWidth() / 2f;
                float ey = fallbackAnchor != null ? fallbackAnchor.worldy() : world.unitHeight() / 2f;
                Fx.massiveExplosion.at(ex, ey);
                Sounds.explosion.at(ex, ey);
                terrainDestroyed++;
                continue;
            }

            Unit unit = type.create(team);
            unit.set(dropPos.x, dropPos.y);
            unit.rotation = Mathf.random(360f);
            unit.health = Math.max(1f, Math.min(unit.maxHealth, snap.health));
            unit.add();

            Fx.spawn.at(unit.x, unit.y);

            // Apply mandatory 4s Landing Deploy debuff
            if(StatusEffects.landingDeploy != null){
                unit.apply(StatusEffects.landingDeploy, TroopAirliftMath.LANDING_DEPLOY_SECONDS * 60f);
            }
            // Apply 45s Orbital Trauma debuff to survivors damaged by AA fire
            if(snap.orbitalDamaged && StatusEffects.orbitalTrauma != null){
                unit.apply(StatusEffects.orbitalTrauma, TroopAirliftMath.ORBITAL_TRAUMA_SECONDS * 60f);
            }

            if(snap.formationGroupId > 0){
                Seq<Unit> grp = formationGroups.get(snap.formationGroupId);
                if(grp == null){
                    grp = new Seq<>();
                    formationGroups.put(snap.formationGroupId, grp);
                }
                grp.add(unit);
            }
            spawnedCount++;
        }

        // 6. Reconstruct TacticalFormation structures for grouped survivors
        for(Seq<Unit> grp : formationGroups.values()){
            if(grp.size >= 2){
                FormationManager.get().createFormationFromUnits(team, grp, false);
            }
        }

        // 7. Auto-possess highest-tier friendly unit when landing a coreless beachhead
        if(team.cores().isEmpty() && spawnedCount > 0){
            autoPossessHighestTierFriendlyUnit();
        }

        captureLiveSectorSnapshot(sector);

        // Notify player
        if(!headless && ui != null && ui.hudfrag != null){
            StringBuilder msg = new StringBuilder();
            msg.append("空投部队已着陆：").append(spawnedCount).append(" 台");
            if(cull.culledExistingKeys.size() > 0 || cull.culledArrivingKeys.size() > 0){
                msg.append("（超指挥上限清算 ").append(cull.culledExistingKeys.size() + cull.culledArrivingKeys.size()).append(" 台）");
            }
            if(terrainDestroyed > 0){
                msg.append("（无合适地形坠毁 ").append(terrainDestroyed).append(" 台）");
            }
            ui.hudfrag.showToast(msg.toString());
        }
    }

    /**
     * Finds a terrain-valid drop coordinate for {@code snap}.
     * - Air: direct drop near primary anchor.
     * - Naval: spiral search for deep liquid tile without solid block.
     * - Ground: spiral search for solid-free, non-deep-liquid tile.
     */
    private @Nullable Vec2 findDropPosition(UnitType type, TroopUnitSnapshot snap, Seq<Tile> anchors, int radiusTiles, int salt){
        if(anchors == null || anchors.isEmpty() || world == null || world.tiles == null) return null;

        if(snap.flying || type.flying){
            Tile a = anchors.first();
            float ang = (salt * 137.5f) % 360f;
            float dist = (salt % 5) * tilesize * 0.9f;
            float wx = Mathf.clamp(a.worldx() + Angles.trnsx(ang, dist), tilesize * 2f, world.unitWidth() - tilesize * 2f);
            float wy = Mathf.clamp(a.worldy() + Angles.trnsy(ang, dist), tilesize * 2f, world.unitHeight() - tilesize * 2f);
            return new Vec2(wx, wy);
        }

        boolean needDeepWater = snap.naval || type.naval;
        for(int aIdx = 0; aIdx < anchors.size; aIdx++){
            Tile center = anchors.get(aIdx);
            if(center == null) continue;
            int cx = center.x;
            int cy = center.y;

            // Spiral ring search from r = 1 .. radiusTiles (try offset angle by salt to avoid stacking)
            for(int r = 1; r <= radiusTiles; r++){
                int perimeter = r * 8;
                int startStep = (salt * 3) % perimeter;
                for(int s = 0; s < perimeter; s++){
                    int step = (startStep + s) % perimeter;
                    int dx, dy;
                    int side = step / (2 * r);
                    int off = step % (2 * r);
                    if(side == 0){
                        dx = -r + off; dy = r;
                    }else if(side == 1){
                        dx = r; dy = r - off;
                    }else if(side == 2){
                        dx = r - off; dy = -r;
                    }else{
                        dx = -r; dy = -r + off;
                    }
                    Tile t = world.tile(cx + dx, cy + dy);
                    if(isValidLandingTile(t, needDeepWater)){
                        return new Vec2(t.worldx(), t.worldy());
                    }
                }
            }
        }
        return null;
    }

    private boolean isValidLandingTile(@Nullable Tile tile, boolean needDeepWater){
        if(tile == null) return false;
        if(tile.x < 2 || tile.y < 2 || tile.x >= world.width() - 2 || tile.y >= world.height() - 2) return false;
        if(tile.solid()) return false;
        if(tile.build != null && tile.build.block.solid) return false;
        if(needDeepWater){
            return tile.floor().isLiquid && tile.floor().isDeep();
        }
        return !tile.floor().isDeep() && (!tile.floor().isLiquid || tile.floor().drownTime <= 0f);
    }

    /** Locates the map-edge default spawn tile for coreless beachhead landing. */
    private @Nullable Tile findBeachheadSpawnTile(Sector sector){
        if(world == null || world.tiles == null) return null;
        if(sector != null && sector.info.spawnPosition != 0){
            Tile sp = world.tile(sector.info.spawnPosition);
            if(sp != null) return sp;
        }
        // Scan map edge border (6 tiles inward) away from enemy cores
        int margin = Math.min(8, Math.max(3, Math.min(world.width(), world.height()) / 10));
        Seq<CoreBuild> enemyCores = state != null && state.rules != null && state.rules.waveTeam != null
            ? state.rules.waveTeam.cores() : null;
        Tile best = null;
        float bestDist2 = -1f;
        int w = world.width();
        int h = world.height();
        int step = Math.max(2, Math.min(w, h) / 24);
        for(int x = margin; x < w - margin; x += step){
            for(int pass = 0; pass < 2; pass++){
                int y = pass == 0 ? margin : (h - 1 - margin);
                Tile t = world.tile(x, y);
                if(t == null || t.solid()) continue;
                float d2 = dist2ToNearestCore(t, enemyCores);
                if(d2 > bestDist2){
                    bestDist2 = d2;
                    best = t;
                }
            }
        }
        for(int y = margin; y < h - margin; y += step){
            for(int pass = 0; pass < 2; pass++){
                int x = pass == 0 ? margin : (w - 1 - margin);
                Tile t = world.tile(x, y);
                if(t == null || t.solid()) continue;
                float d2 = dist2ToNearestCore(t, enemyCores);
                if(d2 > bestDist2){
                    bestDist2 = d2;
                    best = t;
                }
            }
        }
        return best != null ? best : world.tile(margin, margin);
    }

    private float dist2ToNearestCore(Tile tile, @Nullable Seq<CoreBuild> cores){
        if(cores == null || cores.isEmpty()) return 1f;
        float minD2 = Float.MAX_VALUE;
        for(int i = 0; i < cores.size; i++){
            CoreBuild c = cores.get(i);
            if(c == null) continue;
            float dx = tile.worldx() - c.x;
            float dy = tile.worldy() - c.y;
            float d2 = dx * dx + dy * dy;
            if(d2 < minD2) minD2 = d2;
        }
        return minD2;
    }

    /** Auto-possesses the highest-tier living friendly combat unit in a coreless beachhead sector. */
    public boolean autoPossessHighestTierFriendlyUnit(){
        if(player == null || state == null || state.rules == null || state.rules.defaultTeam == null || Groups.unit == null){
            return false;
        }
        Team team = state.rules.defaultTeam;
        Unit best = null;
        int bestTier = -1;
        float bestMaxHp = -1f;
        float bestHp = -1f;
        for(Unit u : Groups.unit){
            if(u == null || !u.isValid() || u.dead || u.team != team || !u.playerControllable()) continue;
            int tier = classifyUnitTier(u.type);
            if(tier < 1) continue;
            if(tier > bestTier
                || (tier == bestTier && u.maxHealth > bestMaxHp)
                || (tier == bestTier && u.maxHealth == bestMaxHp && u.health > bestHp)){
                bestTier = tier;
                bestMaxHp = u.maxHealth;
                bestHp = u.health;
                best = u;
            }
        }
        if(best == null) return false;
        if(player.unit() != best){
            player.unit(best);
            if(!headless && Core.camera != null){
                Core.camera.position.set(best.x, best.y);
            }
            if(!headless && ui != null && ui.hudfrag != null){
                ui.hudfrag.showToast("已自动接管抢滩最高阶单位：" + best.type.localizedName + "（T" + bestTier + "）");
            }
        }
        return true;
    }

    // ==================== HUD BANNER & PLANET VIEW QUERIES ====================

    /** Returns active incoming missions for {@code sector} whose remaining time is <= 60s. */
    public ArrayList<TroopAirliftMission> getIncomingBannerMissions(@Nullable Sector sector){
        ArrayList<TroopAirliftMission> out = new ArrayList<TroopAirliftMission>();
        if(sector == null || sector.planet == null) return out;
        PlanetAirliftState ps = getPlanetState(sector.planet);
        for(int i = 0; i < ps.activeMissions.size(); i++){
            TroopAirliftMission m = ps.activeMissions.get(i);
            if(m.targetSectorId == sector.id && m.shouldShowArrivalBanner()){
                out.add(m);
            }
        }
        Collections.sort(out, new Comparator<TroopAirliftMission>(){
            @Override
            public int compare(TroopAirliftMission a, TroopAirliftMission b){
                return Float.compare(a.remainingSeconds(), b.remainingSeconds());
            }
        });
        return out;
    }

    /** Returns all active missions on {@code planet}. */
    public List<TroopAirliftMission> getActiveMissions(@Nullable Planet planet){
        if(planet == null) return Collections.<TroopAirliftMission>emptyList();
        return getPlanetState(planet).activeMissions;
    }

    /** Returns active missions heading into or departing from {@code sector}. */
    public ArrayList<TroopAirliftMission> getMissionsInvolvingSector(@Nullable Sector sector){
        ArrayList<TroopAirliftMission> out = new ArrayList<TroopAirliftMission>();
        if(sector == null || sector.planet == null) return out;
        PlanetAirliftState ps = getPlanetState(sector.planet);
        for(int i = 0; i < ps.activeMissions.size(); i++){
            TroopAirliftMission m = ps.activeMissions.get(i);
            if(m.originSectorId == sector.id || m.targetSectorId == sector.id){
                out.add(m);
            }
        }
        return out;
    }

    public String getRecentAlertText(){
        return recentAlertSeconds > 0f ? recentAlertText : "";
    }

    private void broadcastAlert(String text){
        recentAlertText = text == null ? "" : text;
        recentAlertSeconds = 8f;
        if(!headless && ui != null && ui.hudfrag != null && !recentAlertText.isEmpty()){
            ui.hudfrag.showToast(recentAlertText);
        }
    }

    public void onSectorLost(@Nullable Sector sector){
        if(sector == null || sector.planet == null) return;
        PlanetAirliftState ps = getPlanetState(sector.planet);
        SectorGarrisonSnapshot snap = ps.garrisons.get(Integer.valueOf(sector.id));
        if(snap != null){
            snap.shardCores = 0;
            snap.foundationCores = 0;
            snap.nucleusCores = 0;
            snap.recomputeCap();
            snap.usedCp = 0;
            snap.livingUnits = 0;
            snap.formationCount = 0;
            snap.beachheadActive = false;
            for(int i = 0; i < 6; i++) snap.tierCounts[i] = 0;
            markChanged();
        }
    }

    // ==================== PERSISTENCE ====================

    public void saveIfDirty(boolean flushDisk){
        if(dirty){
            save(flushDisk);
        }
    }

    public void save(boolean flushDisk){
        if(Core.settings == null) return;
        if(net != null && net.isConnectedClient()) return;
        try{
            byte[] bytes = serializeAllStates();
            Core.settings.put(SETTINGS_KEY, new String(Base64Coder.encode(bytes)));
            dirty = false;
            if(flushDisk){
                Core.settings.manualSave();
            }
        }catch(Throwable t){
            Log.err("Failed to save V3.9 cross-sector troop airlift state", t);
        }
    }

    public void load(){
        if(Core.settings == null) return;
        String encoded = Core.settings.getString(SETTINGS_KEY, "");
        if(encoded == null || encoded.isEmpty()) return;
        try{
            byte[] bytes = Base64Coder.decode(encoded);
            applyAllStates(bytes);
        }catch(Throwable t){
            Log.err("Failed to load V3.9 cross-sector troop airlift state", t);
        }
    }

    public byte[] serializeAllStates() throws IOException{
        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        try(DataOutputStream out = new DataOutputStream(baos)){
            out.writeInt(PERSIST_VERSION);
            out.writeInt(planetStates.size);
            for(ObjectMap.Entry<String, PlanetAirliftState> entry : planetStates.entries()){
                out.writeUTF(entry.key);
                entry.value.writeToStream(out);
            }
            out.flush();
        }
        return baos.toByteArray();
    }

    public void applyAllStates(byte[] data) throws IOException{
        if(data == null || data.length == 0) return;
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))){
            int ver = in.readInt();
            if(ver != PERSIST_VERSION) return;
            int count = Math.max(0, in.readInt());
            planetStates.clear();
            for(int i = 0; i < count; i++){
                String planetName = in.readUTF();
                PlanetAirliftState ps = new PlanetAirliftState();
                ps.readFromStream(in);
                planetStates.put(planetName, ps);
            }
            uiRevision++;
        }
    }
}
