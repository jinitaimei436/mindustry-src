package mindustry.game.logistics;

import arc.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.content.*;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.game.coop.*;
import mindustry.game.logistics.SectorConveyorMath.*;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.distribution.SectorConveyorPort.*;

import java.io.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * Central campaign manager for the V2.8 Global Resource View & Cross-Sector Conveyor system
 * ("全球资源视野与区块间传送带").
 *
 * Bridges {@link PlanetConveyorTopology} with live {@link SectorConveyorPortBuild} buildings,
 * PlanetDialog's Resource View ("资源视野"), persistent campaign settings, and V2 co-op sync.
 */
public class SectorConveyorManager{
    private static final SectorConveyorManager instance = new SectorConveyorManager();
    private static final String SETTINGS_KEY = "v28-sector-conveyor-grid";
    private static final int PERSIST_VERSION = 1;

    private final ObjectMap<String, PlanetConveyorTopology> planetTopologies = new ObjectMap<>();

    private boolean initialized = false;
    private int uiRevision = 1;
    private boolean dirty = false;

    public static SectorConveyorManager get(){
        return instance;
    }

    private SectorConveyorManager(){
    }

    public void init(){
        if(initialized) return;
        initialized = true;
        load();

        Events.on(WorldLoadEvent.class, e -> {
            if(state != null && state.isCampaign() && state.hasSector()){
                syncCurrentSectorPorts();
            }
        });

        Events.on(SaveLoadEvent.class, e -> {
            if(!e.isMap && state != null && state.isCampaign() && state.hasSector()){
                syncCurrentSectorPorts();
            }
        });

        Events.on(SaveWriteEvent.class, e -> {
            if(state != null && state.isCampaign()){
                saveIfAuthoritative(true);
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

    public PlanetConveyorTopology getTopology(@Nullable Planet planet){
        if(planet == null) return new PlanetConveyorTopology();
        return getTopology(planet.name);
    }

    public PlanetConveyorTopology getTopology(String planetName){
        PlanetConveyorTopology topo = planetTopologies.get(planetName);
        if(topo == null){
            topo = new PlanetConveyorTopology();
            planetTopologies.put(planetName, topo);
        }
        return topo;
    }

    // ==================== PORT LIFECYCLE ====================

    public boolean hasAnyPort(@Nullable Sector sector){
        if(sector == null || !sector.hasBase()) return false;
        return getTopology(sector.planet).hasAnyPort(sector.id);
    }

    public int allocatePortNumber(@Nullable Sector sector, boolean isSender){
        if(sector == null) return 1;
        return getTopology(sector.planet).allocatePortNumber(sector.id, isSender);
    }

    public @Nullable SectorPortRecord registerLivePort(@Nullable Sector sector, int portNumber, boolean isSender, int tier){
        if(sector == null || portNumber <= 0) return null;
        PlanetConveyorTopology topo = getTopology(sector.planet);
        boolean existed = topo.getPort(sector.id, isSender, portNumber) != null;
        SectorPortRecord rec = topo.registerPort(sector.id, portNumber, isSender, tier);
        if(rec != null && !existed){
            markChanged();
            saveIfAuthoritative(true);
            notifyNetworkChange();
        }
        return rec;
    }

    public void unregisterLivePort(@Nullable Sector sector, int portNumber, boolean isSender){
        if(sector == null || portNumber <= 0) return;
        PlanetConveyorTopology topo = getTopology(sector.planet);
        if(topo.unregisterPort(sector.id, portNumber, isSender)){
            markChanged();
            saveIfAuthoritative(true);
            notifyNetworkChange();
        }
    }

    public void onSectorLost(@Nullable Sector sector){
        if(sector == null) return;
        PlanetConveyorTopology topo = getTopology(sector.planet);
        if(topo.clearSector(sector.id)){
            markChanged();
            saveIfAuthoritative(true);
            notifyNetworkChange();
        }
    }

    public @Nullable SectorPortRecord getPort(@Nullable Sector sector, boolean isSender, int portNumber){
        if(sector == null || portNumber <= 0) return null;
        return getTopology(sector.planet).getPort(sector.id, isSender, portNumber);
    }

    public @Nullable SectorConveyorLink getLinkForPort(@Nullable Sector sector, boolean isSender, int portNumber){
        if(sector == null || portNumber <= 0) return null;
        PlanetConveyorTopology topo = getTopology(sector.planet);
        return isSender ? topo.getLinkForSender(sector.id, portNumber) : topo.getLinkForReceiver(sector.id, portNumber);
    }

    /**
     * Scans the active sector for all default-team cross-sector conveyor ports, ensures each
     * has a valid unique port number, reconciles topology, and syncs live receiver buffers.
     * Scans {@code world.tiles} directly so it remains accurate even during {@link WorldLoadEvent}
     * before {@link mindustry.ai.BlockIndexer} populates team building indices.
     */
    public void syncCurrentSectorPorts(){
        if(state == null || !state.isCampaign() || !state.hasSector()) return;
        if(world == null || world.tiles == null || world.isGenerating()) return;
        Sector sec = state.getSector();
        if(!sec.hasBase()){
            onSectorLost(sec);
            return;
        }

        PlanetConveyorTopology topo = getTopology(sec.planet);
        Set<Long> liveKeys = new HashSet<>();
        boolean changed = false;
        Team defaultTeam = state.rules.defaultTeam;

        for(Tile tile : world.tiles){
            if(tile != null && tile.build instanceof SectorConveyorPortBuild build
                && build.tile == tile && build.team == defaultTeam && build.isValid()
                && build.block instanceof SectorConveyorPort blk){
                if(build.portNumber <= 0){
                    int assigned = topo.allocatePortNumber(sec.id, blk.isSender);
                    if(assigned > 0){
                        build.portNumber = assigned;
                        changed = true;
                    }
                }
                if(build.portNumber > 0){
                    boolean existed = topo.getPort(sec.id, blk.isSender, build.portNumber) != null;
                    SectorPortRecord rec = topo.registerPort(sec.id, build.portNumber, blk.isSender, blk.portTier);
                    if(rec != null){
                        liveKeys.add(SectorConveyorMath.packPortKey(sec.id, blk.isSender, build.portNumber));
                        build.syncItemModuleFromRecord(rec);
                        if(!existed) changed = true;
                    }
                }
            }
        }

        if(topo.reconcileSectorPorts(sec.id, liveKeys)){
            changed = true;
        }

        if(changed){
            markChanged();
            saveIfAuthoritative(true);
            notifyNetworkChange();
        }
    }

    // ==================== 1-TO-1 BINDING & QUERIES ====================

    /** Lists all currently unlinked counterpart ports on sectors directly adjacent to {@code sector}. */
    public List<SectorPortRecord> getAvailableAdjacentTargets(@Nullable Sector sector, boolean lookingForReceiver){
        List<SectorPortRecord> out = new ArrayList<>();
        if(sector == null || !sector.hasBase()) return out;
        PlanetConveyorTopology topo = getTopology(sector.planet);

        for(Sector neighbor : sector.near()){
            if(!neighbor.hasBase()) continue;
            List<SectorPortRecord> candidates = topo.getSectorPorts(neighbor.id, !lookingForReceiver);
            for(SectorPortRecord rec : candidates){
                if(!topo.isPortLinked(rec.sectorId, rec.isSender, rec.portNumber)){
                    out.add(rec);
                }
            }
        }
        out.sort((a, b) -> {
            int c = Integer.compare(a.sectorId, b.sectorId);
            return c != 0 ? c : Integer.compare(a.portNumber, b.portNumber);
        });
        return out;
    }

    /**
     * Player-initiated 1-to-1 bind from PlanetDialog Resource View.
     * Routes through Host in V2 multiplayer; executes directly in SP/Host.
     *
     * @return {@code 1} bound, {@code 2} requested over network, {@code 0} invalid.
     */
    public int requestBind(@Nullable Planet planet, int senderSectorId, int senderPortNumber, int receiverSectorId, int receiverPortNumber){
        if(planet == null || senderSectorId < 0 || receiverSectorId < 0 || senderSectorId == receiverSectorId) return 0;
        if(senderSectorId >= planet.sectors.size || receiverSectorId >= planet.sectors.size) return 0;

        Sector sSec = planet.sectors.get(senderSectorId);
        Sector rSec = planet.sectors.get(receiverSectorId);
        if(!sSec.hasBase() || !rSec.hasBase() || !sSec.near().contains(rSec)) return 0;

        PlanetConveyorTopology topo = getTopology(planet);
        if(!topo.canBind(senderSectorId, senderPortNumber, receiverSectorId, receiverPortNumber, true)){
            return 0;
        }

        if(net != null && net.isConnectedClient()){
            MultiSectorCoordinator.get().requestConveyorLinkChange(planet.name, true, senderSectorId, senderPortNumber, receiverSectorId, receiverPortNumber);
            return 2;
        }

        if(topo.bind(senderSectorId, senderPortNumber, receiverSectorId, receiverPortNumber, true)){
            markChanged();
            saveIfAuthoritative(true);
            notifyNetworkChange();
            return 1;
        }
        return 0;
    }

    /**
     * Player-initiated unbind from PlanetDialog Resource View.
     *
     * @return {@code -1} unbound, {@code 2} requested over network, {@code 0} not linked.
     */
    public int requestUnbind(@Nullable Planet planet, int sectorId, int portNumber, boolean isSender){
        if(planet == null || sectorId < 0 || portNumber <= 0) return 0;
        PlanetConveyorTopology topo = getTopology(planet);
        SectorConveyorLink link = isSender
            ? topo.getLinkForSender(sectorId, portNumber)
            : topo.getLinkForReceiver(sectorId, portNumber);
        if(link == null) return 0;

        if(net != null && net.isConnectedClient()){
            MultiSectorCoordinator.get().requestConveyorLinkChange(
                planet.name, false, link.senderSectorId, link.senderPortNumber, link.receiverSectorId, link.receiverPortNumber
            );
            return 2;
        }

        if(topo.unbindSender(link.senderSectorId, link.senderPortNumber)){
            markChanged();
            saveIfAuthoritative(true);
            notifyNetworkChange();
            return -1;
        }
        return 0;
    }

    /** Authoritative Host-side handler invoked by {@link MultiSectorCoordinator}. */
    public int applyLinkChangeAuthoritative(String planetName, boolean bindOp, int sSecId, int sPort, int rSecId, int rPort){
        if(content == null || planetName == null) return 0;
        Planet planet = content.planet(planetName);
        if(planet == null) return 0;
        PlanetConveyorTopology topo = getTopology(planet);

        if(bindOp){
            if(sSecId < 0 || sSecId >= planet.sectors.size || rSecId < 0 || rSecId >= planet.sectors.size) return 0;
            Sector sSec = planet.sectors.get(sSecId);
            Sector rSec = planet.sectors.get(rSecId);
            if(!sSec.hasBase() || !rSec.hasBase() || !sSec.near().contains(rSec)) return 0;
            if(topo.bind(sSecId, sPort, rSecId, rPort, true)){
                markChanged();
                saveIfAuthoritative(true);
                return 1;
            }
            return 0;
        }else{
            if(topo.unbindSender(sSecId, sPort)){
                markChanged();
                saveIfAuthoritative(true);
                return -1;
            }
            return 0;
        }
    }

    // ==================== LIVE SIMULATION & BACKGROUND STEPPING ====================

    public int stepLiveSender(@Nullable Sector sector, int portNumber, float dtSeconds, float efficiency){
        if(sector == null || portNumber <= 0 || dtSeconds <= 0f) return 0;
        PlanetConveyorTopology topo = getTopology(sector.planet);
        int moved = topo.stepSenderTransfer(sector.id, portNumber, dtSeconds, efficiency);
        if(moved > 0){
            dirty = true;
        }
        return moved;
    }

    public void stepLiveReceiverWindow(@Nullable Sector sector, int portNumber, float dtSeconds){
        if(sector == null || portNumber <= 0 || dtSeconds <= 0f) return;
        SectorPortRecord rec = getPort(sector, false, portNumber);
        if(rec != null){
            rec.advanceRateWindow(dtSeconds);
        }
    }

    /**
     * Called once per second from {@link mindustry.game.Universe#update()} on SP/Host.
     * Prunes ports on lost sectors and advances background-only sender links (with full backpressure).
     */
    public void updateGlobalSecond(@Nullable Planet planet, float dtSeconds){
        if(planet == null || dtSeconds <= 0f) return;
        PlanetConveyorTopology topo = getTopology(planet);

        boolean pruned = false;
        for(int secId : new ArrayList<>(topo.getPortSectorIds())){
            if(secId < 0 || secId >= planet.sectors.size || !planet.sectors.get(secId).hasBase()){
                if(topo.clearSector(secId)){
                    pruned = true;
                }
            }
        }

        // Only exclude this process's own active sector (which steps at 60 FPS in SectorConveyorPortBuild.updateTile).
        // Detached guest sectors are stepped authoritatively here on the Host so cross-sector items arrive reliably.
        Set<Integer> localActiveIds = new HashSet<>();
        if(state != null && state.hasSector() && state.getSector().planet == planet && state.isGame()){
            localActiveIds.add(state.getSector().id);
        }

        topo.stepBackgroundLinks(localActiveIds, dtSeconds);
        uiRevision++;

        if(pruned){
            markChanged();
            notifyNetworkChange();
        }
        if(dirty){
            saveIfAuthoritative(false);
        }
    }

    // ==================== PERSISTENCE & V2 MULTIPLAYER SYNC ====================

    private void saveIfAuthoritative(boolean flushDisk){
        if(net != null && net.isConnectedClient()) return;
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
            Log.err("Failed to save V2.8 cross-sector conveyor grid", t);
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
            Log.err("Failed to load V2.8 cross-sector conveyor grid", t);
        }
    }

    public byte[] serializeAllTopologies() throws IOException{
        ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
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
                PlanetConveyorTopology topo = new PlanetConveyorTopology();
                topo.readFromStream(in);
                planetTopologies.put(planetName, topo);
            }
            uiRevision++;
        }
    }

    /** Serializes active sector's conveyor port states for detached V2 guests to report to Host. */
    public byte[] serializeLocalSectorState(@Nullable Sector sector) throws IOException{
        ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
        try(DataOutputStream out = new DataOutputStream(baos)){
            out.writeUTF(sector != null && sector.planet != null ? sector.planet.name : "");
            out.writeInt(sector != null ? sector.id : -1);
            if(sector != null){
                List<SectorPortRecord> list = getTopology(sector.planet).getAllSectorPorts(sector.id);
                out.writeInt(list.size());
                for(SectorPortRecord p : list){
                    out.writeInt(p.portNumber);
                    out.writeBoolean(p.isSender);
                    out.writeByte(p.tier);
                    out.writeBoolean(p.backpressurePaused);
                    out.writeInt(p.lastItemId);
                    out.writeFloat(p.recentTotalRate);
                    out.writeInt(p.fifo.size());
                    for(int itemId : p.fifo){
                        out.writeInt(itemId);
                    }
                    out.writeInt(p.itemRatesPerSec.size());
                    for(java.util.Map.Entry<Integer, Float> e : p.itemRatesPerSec.entrySet()){
                        out.writeInt(e.getKey());
                        out.writeFloat(e.getValue());
                    }
                }
            }else{
                out.writeInt(0);
            }
            out.flush();
        }
        return baos.toByteArray();
    }

    /** Applies a detached V2 guest's live sector port state onto the authoritative Host. */
    public boolean applyRemoteSectorState(byte[] data, String expectedPlanet, int expectedSectorId) throws IOException{
        if(data == null || data.length == 0) return false;
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))){
            String planetName = in.readUTF();
            int sectorId = in.readInt();
            if(!planetName.equals(expectedPlanet) || sectorId != expectedSectorId) return false;

            PlanetConveyorTopology topo = getTopology(planetName);
            int count = Math.max(0, in.readInt());
            Set<Long> liveKeys = new HashSet<>();
            for(int i = 0; i < count; i++){
                int portNum = in.readInt();
                boolean isSender = in.readBoolean();
                int tier = in.readByte();
                boolean paused = in.readBoolean();
                int lastItem = in.readInt();
                float totalRate = in.readFloat();

                int fifoLen = Math.max(0, in.readInt());
                List<Integer> fifoItems = new ArrayList<>(fifoLen);
                for(int j = 0; j < fifoLen; j++){
                    fifoItems.add(in.readInt());
                }
                int rateLen = Math.max(0, in.readInt());
                java.util.Map<Integer, Float> rates = new LinkedHashMap<>();
                for(int j = 0; j < rateLen; j++){
                    int iid = in.readInt();
                    float rv = in.readFloat();
                    if(rv > 0.001f) rates.put(iid, rv);
                }

                SectorPortRecord rec = topo.registerPort(sectorId, portNum, isSender, tier);
                if(rec != null){
                    liveKeys.add(SectorConveyorMath.packPortKey(sectorId, isSender, portNum));
                    rec.backpressurePaused = paused;
                    rec.lastItemId = lastItem;
                    rec.recentTotalRate = totalRate;
                    // For senders, remote guest is authoritative over its input queue;
                    // for receivers, only sync rates so Host-delivered cross-sector items aren't overwritten.
                    if(isSender){
                        rec.replaceFifo(fifoItems);
                    }
                    rec.itemRatesPerSec.clear();
                    rec.itemRatesPerSec.putAll(rates);
                }
            }
            if(topo.reconcileSectorPorts(sectorId, liveKeys)){
                markChanged();
            }
            return true;
        }
    }

    private void notifyNetworkChange(){
        if(net != null && net.server()){
            MultiSectorCoordinator.get().broadcastConveyorSnapshot();
        }
    }

    /** Formats a human-readable item flow string for PlanetDialog Resource View detail panels. */
    public static String formatItemFlowSummary(@Nullable SectorPortRecord rec){
        if(rec == null) return "无物品流动";
        if(!rec.itemRatesPerSec.isEmpty() && content != null){
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for(java.util.Map.Entry<Integer, Float> e : rec.itemRatesPerSec.entrySet()){
                Item item = content.item(e.getKey());
                if(item == null) continue;
                if(shown > 0) sb.append("、");
                sb.append(item.localizedName).append(" ").append(Strings.autoFixed(e.getValue(), 1)).append("个/秒");
                shown++;
                if(shown >= 3) break;
            }
            if(shown > 0) return sb.toString();
        }
        if(rec.lastItemId >= 0 && content != null){
            Item item = content.item(rec.lastItemId);
            if(item != null){
                return item.localizedName + " 0个/秒";
            }
        }
        return "空闲 (0个/秒)";
    }
}
