package mindustry.game.power;

import arc.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.game.coop.*;
import mindustry.game.power.SectorPowerGridMath.*;
import mindustry.game.strategy.core.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.power.SurgeSectorPowerHub.*;

import java.io.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * Central campaign manager for the V2.5 Cross-Sector Power Grid ("跨区块电力系统").
 *
 * Bridges {@link PlanetPowerTopology} with Mindustry's live {@link PowerGraph} simulation,
 * {@link SurgeSectorPowerHub} placement/destruction lifecycle, PlanetDialog's Power View,
 * persistent campaign settings, and V2 multi-sector co-op synchronization.
 */
public class SectorPowerGridManager{
    private static final SectorPowerGridManager instance = new SectorPowerGridManager();
    private static final String SETTINGS_KEY = "v25-sector-power-grid";
    private static final int PERSIST_VERSION = 1;

    private final ObjectMap<String, PlanetPowerTopology> planetTopologies = new ObjectMap<>();

    private boolean initialized = false;
    private int uiRevision = 1;
    private boolean dirty = false;

    public static SectorPowerGridManager get(){
        return instance;
    }

    private SectorPowerGridManager(){
    }

    public void init(){
        if(initialized) return;
        initialized = true;
        load();

        Events.on(WorldLoadEvent.class, e -> {
            if(state != null && state.isCampaign() && state.hasSector()){
                syncCurrentSectorHubStatus();
            }
        });

        Events.on(SaveLoadEvent.class, e -> {
            if(!e.isMap && state != null && state.isCampaign() && state.hasSector()){
                syncCurrentSectorHubStatus();
            }
        });

        Events.on(SaveWriteEvent.class, e -> {
            if(state != null && state.isCampaign()){
                if(state.hasSector() && state.getSector().hasBase() && !hasHub(state.getSector())){
                    captureLiveLocalPower(state.getSector());
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

    public PlanetPowerTopology getTopology(@Nullable Planet planet){
        if(planet == null) return new PlanetPowerTopology();
        return getTopology(planet.name);
    }

    public PlanetPowerTopology getTopology(String planetName){
        PlanetPowerTopology topo = planetTopologies.get(planetName);
        if(topo == null){
            topo = new PlanetPowerTopology();
            planetTopologies.put(planetName, topo);
        }
        return topo;
    }

    // ==================== HUB LIFECYCLE ====================

    public boolean hasHub(@Nullable Sector sector){
        if(sector == null || !sector.hasBase()) return false;
        return getTopology(sector.planet).hasHub(sector.id);
    }

    public void onHubActive(@Nullable Sector sector, @Nullable SurgeSectorPowerHubBuild hub){
        if(sector == null) return;
        PlanetPowerTopology topo = getTopology(sector.planet);
        boolean existed = topo.hasHub(sector.id);
        boolean changed = topo.setHubActive(sector.id, true);
        if(hub != null && hub.power != null && hub.power.graph != null){
            PowerGraph g = hub.power.graph;
            g.ensureSectorHub(hub);
            float liveIn = Math.max(0f, g.getLastScaledPowerIn());
            float liveOut = Math.max(0f, g.getLastScaledPowerOut());
            float stored = g.getBatteryStored();
            float cap = g.getTotalBatteryCapacity();
            SectorPowerNode existing = topo.getNode(sector.id);
            // Preserve persisted production/consumption telemetry on tick 0 right after loading a save
            // before PowerGraph.update() has run its first tick.
            float prodToStore = (liveIn <= 0.0001f && liveOut <= 0.0001f && existed && existing != null)
                ? existing.produced
                : liveIn;
            float needToStore = (liveIn <= 0.0001f && liveOut <= 0.0001f && existed && existing != null)
                ? existing.needed
                : liveOut;
            topo.updateSectorTelemetry(
                sector.id,
                prodToStore,
                needToStore,
                stored,
                cap,
                Time.millis()
            );
        }
        if(changed){
            markChanged();
            saveIfAuthoritative(true);
            notifyNetworkChange();
        }
    }

    public void onHubRemoved(@Nullable Sector sector){
        if(sector == null) return;
        PlanetPowerTopology topo = getTopology(sector.planet);
        boolean changed = topo.setHubActive(sector.id, false);
        if(changed){
            markChanged();
            saveIfAuthoritative(true);
            notifyNetworkChange();
        }
    }

    public void onSectorLost(@Nullable Sector sector){
        if(sector == null) return;
        if(getTopology(sector.planet).removeLocalSnapshot(sector.id)){
            dirty = true;
        }
        onHubRemoved(sector);
    }

    /**
     * Locates a valid SurgeSectorPowerHubBuild belonging to the default team in the currently loaded world.
     * Falls back to scanning {@code world.tiles} directly because {@link WorldLoadEvent} listeners
     * registered by Universe execute before {@link mindustry.ai.BlockIndexer} populates team building indices.
     */
    private @Nullable SurgeSectorPowerHubBuild findLiveHubInWorld(){
        if(state == null || state.rules == null || state.rules.defaultTeam == null || Blocks.surgeSectorPowerHub == null){
            return null;
        }
        Team defaultTeam = state.rules.defaultTeam;
        Seq<Building> hubs = defaultTeam.data().getBuildings(Blocks.surgeSectorPowerHub);
        if(hubs != null && hubs.size > 0){
            for(int i = 0; i < hubs.size; i++){
                if(hubs.get(i) instanceof SurgeSectorPowerHubBuild build && build.isValid()){
                    return build;
                }
            }
        }
        if(world != null && world.tiles != null){
            for(Tile tile : world.tiles){
                if(tile != null && tile.build instanceof SurgeSectorPowerHubBuild build
                    && build.tile == tile && build.team == defaultTeam && build.isValid()){
                    return build;
                }
            }
        }
        return null;
    }

    /**
     * Scans the active sector for the default team's Surge Sector Power Hub so pre-existing
     * or freshly loaded saves immediately synchronize hub presence & auto-clean broken links.
     */
    public void syncCurrentSectorHubStatus(){
        if(state == null || !state.isCampaign() || !state.hasSector() || Blocks.surgeSectorPowerHub == null) return;
        if(world == null || world.tiles == null || world.isGenerating()) return;
        Sector sec = state.getSector();
        if(!sec.hasBase()){
            onHubRemoved(sec);
            return;
        }
        SurgeSectorPowerHubBuild build = findLiveHubInWorld();
        if(build != null){
            onHubActive(sec, build);
        }else{
            onHubRemoved(sec);
            captureLiveLocalPower(sec);
        }
    }

    /**
     * Aggregates all unique local PowerGraphs belonging to the default team in the currently
     * loaded foreground sector and persists a lightweight snapshot for non-hub Power View display.
     */
    public LocalPowerSnapshot captureLiveLocalPower(@Nullable Sector sector){
        if(sector == null || !sector.hasBase()){
            return new LocalPowerSnapshot(sector == null ? -1 : sector.id, 0f, 0f, 0f, 0f);
        }
        PlanetPowerTopology topo = getTopology(sector.planet);
        LocalPowerSnapshot existing = topo.getLocalSnapshot(sector.id);
        if(!sector.isBeingPlayed() || state == null || state.rules == null || state.rules.defaultTeam == null){
            return existing != null ? existing : new LocalPowerSnapshot(sector.id, 0f, 0f, 0f, 0f);
        }

        IntSet seenGraphs = new IntSet();
        float prodSec = 0f;
        float needSec = 0f;
        float stored = 0f;
        float cap = 0f;

        Seq<Building> builds = state.rules.defaultTeam.data().buildings;
        for(int i = 0; i < builds.size; i++){
            Building b = builds.get(i);
            if(b == null || !b.isValid() || b.power == null || b.power.graph == null) continue;
            PowerGraph g = b.power.graph;
            if(seenGraphs.add(g.getID())){
                prodSec += Math.max(0f, g.getLastScaledPowerIn()) * 60f;
                needSec += Math.max(0f, g.getLastScaledPowerOut()) * 60f;
                stored += Math.max(0f, g.getBatteryStored());
                cap += Math.max(0f, g.getTotalBatteryCapacity());
            }
        }

        // Preserve persisted prod/need on tick 0 right after loading before PowerGraph.update() runs
        if(prodSec <= 0.0001f && needSec <= 0.0001f && existing != null && seenGraphs.size > 0){
            prodSec = existing.producedPerSec;
            needSec = existing.neededPerSec;
        }

        topo.updateLocalSnapshot(sector.id, prodSec, needSec, stored, cap);
        dirty = true;
        return topo.getLocalSnapshot(sector.id);
    }

    /**
     * Returns local power metrics (power/sec & battery energy) for any captured sector without
     * requiring a Surge Sector Power Hub. Foreground sectors sample live PowerGraphs; background
     * sectors combine live solved StrategySimulationEngine power with persisted battery snapshots.
     */
    public LocalPowerSnapshot getLocalPowerSnapshot(@Nullable Sector sector){
        if(sector == null || !sector.hasBase()){
            return new LocalPowerSnapshot(sector == null ? -1 : sector.id, 0f, 0f, 0f, 0f);
        }
        if(sector.isBeingPlayed()){
            return captureLiveLocalPower(sector);
        }

        LocalPowerSnapshot snap = getTopology(sector.planet).getLocalSnapshot(sector.id);
        SectorStrategyProfile profile = null;
        if(universe != null && universe.strategy != null && sector.planet != null){
            profile = universe.strategy.getEngine(sector.planet).sectors.get(sector.id);
        }
        if(profile == null && sector.info != null){
            profile = sector.info.strategyProfile;
        }

        boolean hasProfileNodes = profile != null && !profile.nodes.isEmpty();
        float prodSec = hasProfileNodes ? profile.solvedLocalPowerProd : (snap != null ? snap.producedPerSec : 0f);
        float needSec = hasProfileNodes ? profile.solvedLocalPowerCons : (snap != null ? snap.neededPerSec : 0f);
        float stored = snap != null ? snap.batteryStored : 0f;
        float cap = snap != null ? snap.batteryCapacity : 0f;
        return new LocalPowerSnapshot(sector.id, prodSec, needSec, stored, cap);
    }

    // ==================== LINKING & QUERIES ====================

    public boolean areLinked(@Nullable Sector a, @Nullable Sector b){
        if(a == null || b == null || a.planet != b.planet) return false;
        return getTopology(a.planet).areLinked(a.id, b.id);
    }

    public boolean canLink(@Nullable Sector a, @Nullable Sector b){
        if(a == null || b == null || a == b || a.planet != b.planet) return false;
        if(!a.hasBase() || !b.hasBase()) return false;
        boolean adjacent = a.near().contains(b);
        return getTopology(a.planet).canLink(a.id, b.id, adjacent);
    }

    public GridAggregate getGridAggregate(@Nullable Sector sector){
        if(sector == null || !hasHub(sector)){
            return new GridAggregate(new int[0], 0f, 0f, 0f, 0f, 0f);
        }
        return getTopology(sector.planet).computeAggregate(sector.id);
    }

    /**
     * Player-initiated toggle of a cross-sector power link from PlanetDialog Power View.
     * Routes through Host in V2 multiplayer; executes directly in singleplayer/host.
     *
     * @return {@code +1} linked, {@code -1} unlinked, {@code 2} requested over network, {@code 0} invalid.
     */
    public int requestToggleLink(@Nullable Planet planet, @Nullable Sector a, @Nullable Sector b){
        if(planet == null || a == null || b == null || a == b || a.planet != planet || b.planet != planet) return 0;
        if(!a.hasBase() || !b.hasBase()) return 0;
        boolean adjacent = a.near().contains(b);
        if(!adjacent) return 0;

        PlanetPowerTopology topo = getTopology(planet);
        if(!topo.areLinked(a.id, b.id) && !topo.canLink(a.id, b.id, true)){
            return 0;
        }

        if(net != null && net.isConnectedClient()){
            MultiSectorCoordinator.get().requestTogglePowerLink(planet.name, a.id, b.id);
            return 2;
        }

        int res = topo.toggleLink(a.id, b.id, true);
        if(res != 0){
            markChanged();
            saveIfAuthoritative(true);
            notifyNetworkChange();
        }
        return res;
    }

    /** Authoritative Host-side toggle handler invoked by {@link MultiSectorCoordinator}. */
    public int toggleLinkAuthoritative(String planetName, int sectorAId, int sectorBId){
        if(content == null) return 0;
        Planet planet = content.planet(planetName);
        if(planet == null || sectorAId < 0 || sectorAId >= planet.sectors.size || sectorBId < 0 || sectorBId >= planet.sectors.size){
            return 0;
        }
        Sector a = planet.sectors.get(sectorAId);
        Sector b = planet.sectors.get(sectorBId);
        if(!a.hasBase() || !b.hasBase() || !a.near().contains(b)) return 0;

        PlanetPowerTopology topo = getTopology(planet);
        int res = topo.toggleLink(a.id, b.id, true);
        if(res != 0){
            markChanged();
            saveIfAuthoritative(true);
        }
        return res;
    }

    // ==================== REAL-TIME POWERGRAPH BRIDGE ====================

    /**
     * Called every tick from {@link PowerGraph#update()} ONLY when the PowerGraph contains
     * an active {@link SurgeSectorPowerHubBuild}. Isolated local graphs never enter this method.
     *
     * Returns {@code null} if not in campaign or if this sector is not currently linked to any
     * other sector (still updates this sector's snapshot so Planet Power View stays live!).
     */
    public @Nullable PowerStepResult resolveLocalGraphTick(
        @Nullable SurgeSectorPowerHubBuild hub,
        float localProducedTick,
        float localNeededTick,
        float localStored,
        float localCapacity,
        float deltaTicks
    ){
        if(hub == null || !hub.isValid() || state == null || !state.isCampaign() || !state.hasSector()) return null;
        if(hub.team != state.rules.defaultTeam) return null;

        Sector sec = state.getSector();
        if(!sec.hasBase()) return null;

        PlanetPowerTopology topo = getTopology(sec.planet);
        float dt = Math.max(0.0001f, deltaTicks);

        // Even if standalone (1 sector in component), update snapshot so Planet Power View shows live local stats.
        int[] comp = topo.getConnectedComponent(sec.id);
        if(comp.length < 2){
            topo.updateSectorTelemetry(
                sec.id,
                Math.max(0f, localProducedTick / dt),
                Math.max(0f, localNeededTick / dt),
                localStored,
                localCapacity,
                Time.millis()
            );
            return null;
        }

        PowerStepResult res = topo.resolveActiveSectorTick(
            sec.id,
            localProducedTick,
            localNeededTick,
            localStored,
            localCapacity,
            dt,
            Time.millis()
        );
        if(res != null && Math.abs(res.remoteBatteryDelta) > 0.0001f){
            dirty = true;
        }
        return res;
    }

    /**
     * Called once per second from {@link mindustry.game.Universe#update()} on SP/Host.
     * Cleans up hubs on any sectors that lost their base and steps shared batteries
     * for background-only inter-sector grids.
     */
    public void updateGlobalSecond(@Nullable Planet planet, float dtSeconds){
        if(planet == null || dtSeconds <= 0f) return;
        PlanetPowerTopology topo = getTopology(planet);

        // Verify all registered hub sectors still have bases (defensive cleanup).
        List<Integer> hubIds = topo.getHubSectorIds();
        boolean pruned = false;
        for(int i = 0; i < hubIds.size(); i++){
            int id = hubIds.get(i);
            if(id < 0 || id >= planet.sectors.size || !planet.sectors.get(id).hasBase()){
                if(topo.setHubActive(id, false)){
                    pruned = true;
                }
            }
        }

        // Collect currently simulated sector IDs (local sector + any V2 co-op occupied sectors on this planet).
        Set<Integer> activeSimIds = new HashSet<>();
        if(state != null && state.hasSector() && state.getSector().planet == planet && state.isGame()){
            activeSimIds.add(state.getSector().id);
        }
        if(net != null && net.server()){
            for(Sector sec : planet.sectors){
                if(MultiSectorCoordinator.get().isSectorOccupied(sec)){
                    activeSimIds.add(sec.id);
                }
            }
        }

        boolean batteryChanged = topo.stepBackgroundComponentsTracked(activeSimIds, dtSeconds);
        if(batteryChanged){
            dirty = true;
        }

        if(pruned){
            markChanged();
            notifyNetworkChange();
        }
        // V3.2: Persistence is handled by Universe.save() every 10s (and immediately on structural edits/SaveWriteEvent)
    }

    // ==================== PERSISTENCE & V2 MULTIPLAYER SYNC ====================

    private void saveIfAuthoritative(boolean flushDisk){
        if(net != null && net.isConnectedClient()) return;
        save(flushDisk);
    }

    public void saveIfDirty(boolean flushDisk){
        if(!dirty) return;
        save(flushDisk);
    }

    public void save(){
        save(false);
    }

    public void save(boolean flushDisk){
        if(Core.settings == null) return;
        if(net != null && net.isConnectedClient()) return;
        try{
            byte[] bytes = serializeAllTopologies();
            Core.settings.put(SETTINGS_KEY, new String(Base64Coder.encode(bytes)));
            dirty = false;
            if(flushDisk){
                Core.settings.manualSave();
            }
        }catch(Throwable t){
            Log.err("Failed to save V2.5 cross-sector power grid", t);
        }
    }

    public void load(){
        if(Core.settings == null) return;
        String encoded = Core.settings.getString(SETTINGS_KEY, "");
        if(encoded == null || encoded.isEmpty()) return;
        try{
            byte[] bytes = Base64Coder.decode(encoded);
            applyAllTopologies(bytes);
        }catch(Throwable t){
            Log.err("Failed to load V2.5 cross-sector power grid", t);
        }
    }

    public byte[] serializeAllTopologies() throws IOException{
        ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
        try(DataOutputStream out = new DataOutputStream(baos)){
            out.writeInt(PERSIST_VERSION);
            out.writeInt(planetTopologies.size);
            for(var entry : planetTopologies.entries()){
                out.writeUTF(entry.key);
                entry.value.writeToStream(out);
            }
            out.flush();
        }
        return baos.toByteArray();
    }

    public void applyAllTopologies(byte[] data) throws IOException{
        if(data == null || data.length == 0) return;
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))){
            int ver = in.readInt();
            if(ver != PERSIST_VERSION) return;
            int count = Math.max(0, in.readInt());
            planetTopologies.clear();
            for(int i = 0; i < count; i++){
                String planetName = in.readUTF();
                PlanetPowerTopology topo = new PlanetPowerTopology();
                topo.readFromStream(in);
                planetTopologies.put(planetName, topo);
            }
            uiRevision++;
        }
    }

    /** Serializes the active sector's hub status & power telemetry for detached V2 guests to send to Host. */
    public byte[] serializeLocalSectorTelemetry(@Nullable Sector sector) throws IOException{
        ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
        try(DataOutputStream out = new DataOutputStream(baos)){
            out.writeUTF(sector != null && sector.planet != null ? sector.planet.name : "");
            out.writeInt(sector != null ? sector.id : -1);
            if(sector != null){
                PlanetPowerTopology topo = getTopology(sector.planet);
                SectorPowerNode node = topo.getNode(sector.id);
                boolean active = node != null && node.hasHub;
                out.writeBoolean(active);
                if(active){
                    out.writeFloat(node.produced);
                    out.writeFloat(node.needed);
                    out.writeFloat(node.batteryStored);
                    out.writeFloat(node.batteryCapacity);
                }
            }else{
                out.writeBoolean(false);
            }
            out.flush();
        }
        return baos.toByteArray();
    }

    /** Applies a detached V2 guest's live sector power telemetry on the authoritative Host. */
    public boolean applyRemoteSectorTelemetry(byte[] data, String expectedPlanet, int expectedSectorId) throws IOException{
        if(data == null || data.length == 0) return false;
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))){
            String planetName = in.readUTF();
            int sectorId = in.readInt();
            boolean hasHub = in.readBoolean();
            if(!planetName.equals(expectedPlanet) || sectorId != expectedSectorId) return false;

            PlanetPowerTopology topo = getTopology(planetName);
            if(hasHub){
                float prod = in.readFloat();
                float need = in.readFloat();
                float stored = in.readFloat();
                float cap = in.readFloat();
                boolean changed = !topo.hasHub(sectorId);
                topo.updateSectorTelemetry(sectorId, prod, need, stored, cap, Time.millis());
                if(changed){
                    markChanged();
                }
            }else{
                if(topo.setHubActive(sectorId, false)){
                    markChanged();
                }
            }
            return true;
        }
    }

    private void notifyNetworkChange(){
        if(net != null && net.server()){
            MultiSectorCoordinator.get().broadcastPowerGridSnapshot();
        }
    }
}
