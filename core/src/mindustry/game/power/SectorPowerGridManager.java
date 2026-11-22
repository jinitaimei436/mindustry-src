package mindustry.game.power;

import arc.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.content.*;
import mindustry.game.EventType.*;
import mindustry.game.coop.*;
import mindustry.game.power.SectorPowerGridMath.*;
import mindustry.gen.*;
import mindustry.type.*;
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
        boolean changed = topo.setHubActive(sector.id, true);
        if(hub != null && hub.power != null && hub.power.graph != null){
            PowerGraph g = hub.power.graph;
            topo.updateSectorTelemetry(
                sector.id,
                Math.max(0f, g.getLastScaledPowerIn()),
                Math.max(0f, g.getLastScaledPowerOut()),
                g.getBatteryStored(),
                g.getTotalBatteryCapacity(),
                Time.millis()
            );
        }
        if(changed){
            markChanged();
            saveIfAuthoritative();
            notifyNetworkChange();
        }
    }

    public void onHubRemoved(@Nullable Sector sector){
        if(sector == null) return;
        PlanetPowerTopology topo = getTopology(sector.planet);
        boolean changed = topo.setHubActive(sector.id, false);
        if(changed){
            markChanged();
            saveIfAuthoritative();
            notifyNetworkChange();
        }
    }

    public void onSectorLost(@Nullable Sector sector){
        if(sector == null) return;
        onHubRemoved(sector);
    }

    /**
     * Scans the active sector for the default team's Surge Sector Power Hub so pre-existing
     * or freshly loaded saves immediately synchronize hub presence & auto-clean broken links.
     */
    public void syncCurrentSectorHubStatus(){
        if(state == null || !state.isCampaign() || !state.hasSector() || Blocks.surgeSectorPowerHub == null) return;
        Sector sec = state.getSector();
        if(!sec.hasBase()){
            onHubRemoved(sec);
            return;
        }
        Seq<Building> hubs = state.rules.defaultTeam.data().getBuildings(Blocks.surgeSectorPowerHub);
        if(hubs != null && hubs.size > 0 && hubs.first() instanceof SurgeSectorPowerHubBuild build){
            onHubActive(sec, build);
        }else{
            onHubRemoved(sec);
        }
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
            saveIfAuthoritative();
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
            saveIfAuthoritative();
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

        return topo.resolveActiveSectorTick(
            sec.id,
            localProducedTick,
            localNeededTick,
            localStored,
            localCapacity,
            dt,
            Time.millis()
        );
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

        topo.stepBackgroundComponents(activeSimIds, dtSeconds);

        if(pruned){
            markChanged();
            notifyNetworkChange();
        }
        if(dirty){
            saveIfAuthoritative();
        }
    }

    // ==================== PERSISTENCE & V2 MULTIPLAYER SYNC ====================

    private void saveIfAuthoritative(){
        if(net != null && net.isConnectedClient()) return;
        save();
    }

    public void save(){
        if(Core.settings == null) return;
        if(net != null && net.isConnectedClient()) return;
        try{
            byte[] bytes = serializeAllTopologies();
            Core.settings.put(SETTINGS_KEY, new String(Base64Coder.encode(bytes)));
            dirty = false;
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
