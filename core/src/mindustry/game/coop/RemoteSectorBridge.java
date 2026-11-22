package mindustry.game.coop;

import arc.util.*;
import mindustry.game.strategy.core.*;
import mindustry.type.*;
import mindustry.world.blocks.campaign.*;
import mindustry.world.blocks.storage.CoreBlock.*;

import java.io.*;
import java.util.*;

import static mindustry.Vars.*;

/**
 * Bridges detached guest-simulated sectors with the host's authoritative
 * {@link mindustry.game.strategy.GlobalStrategyManager}.
 *
 * Detached guests upload a lightweight strategic snapshot (core items, export
 * rates, active LandingPad demands) every ~2 seconds; the host incorporates it
 * into the planet-wide logistics solver without double-simulating background
 * production, then pushes resulting incoming item deliveries back to the guest.
 */
public class RemoteSectorBridge{
    public static final long SNAPSHOT_STALE_MS = 15_000L;

    public static final class RemoteSectorSnapshot{
        public final String planetName;
        public final int sectorId;
        public int storageCapacity;
        public boolean hasBase;
        public boolean underAttack;
        public boolean requireExplicitImportDemand;
        public final HashMap<String, Integer> coreItems = new HashMap<>();
        public final HashMap<String, Float> exportRates = new HashMap<>();
        public final HashMap<String, Float> importDemands = new HashMap<>();
        public long updatedMs;

        public RemoteSectorSnapshot(String planetName, int sectorId){
            this.planetName = planetName;
            this.sectorId = sectorId;
        }
    }

    private final HashMap<SectorOccupancy.SectorKey, RemoteSectorSnapshot> snapshots = new HashMap<>();
    private final HashMap<SectorOccupancy.SectorKey, HashMap<String, Integer>> pendingDeliveries = new HashMap<>();

    public synchronized void clear(){
        snapshots.clear();
        pendingDeliveries.clear();
    }

    public synchronized void removeSector(String planetName, int sectorId){
        SectorOccupancy.SectorKey key = new SectorOccupancy.SectorKey(planetName, sectorId);
        snapshots.remove(key);
        pendingDeliveries.remove(key);
    }

    public synchronized void putSnapshot(RemoteSectorSnapshot snap){
        if(snap == null) return;
        snapshots.put(new SectorOccupancy.SectorKey(snap.planetName, snap.sectorId), snap);
    }

    public synchronized boolean isRemoteForegroundActive(String planetName, int sectorId, long nowMs){
        RemoteSectorSnapshot snap = snapshots.get(new SectorOccupancy.SectorKey(planetName, sectorId));
        return snap != null && (nowMs - snap.updatedMs) <= SNAPSHOT_STALE_MS;
    }

    public synchronized @Nullable RemoteSectorSnapshot getSnapshot(String planetName, int sectorId){
        return snapshots.get(new SectorOccupancy.SectorKey(planetName, sectorId));
    }

    /**
     * Populates a host-side {@link SectorStrategyProfile} with live telemetry
     * reported by the detached guest currently simulating this sector.
     */
    public synchronized boolean applyRemoteProfile(SectorStrategyProfile profile, String planetName, int sectorId, long nowMs){
        RemoteSectorSnapshot snap = snapshots.get(new SectorOccupancy.SectorKey(planetName, sectorId));
        if(snap == null || (nowMs - snap.updatedMs) > SNAPSHOT_STALE_MS) return false;

        profile.hasBase = snap.hasBase;
        profile.underAttack = snap.underAttack;
        profile.foregroundActive = true;
        profile.storageCapacity = Math.max(snap.storageCapacity, 0);
        profile.requireExplicitImportDemand = snap.requireExplicitImportDemand;

        profile.storedItems.clear();
        for(Map.Entry<String, Integer> entry : snap.coreItems.entrySet()){
            profile.setStored(entry.getKey(), entry.getValue());
        }

        profile.foregroundExportRates.clear();
        profile.foregroundExportRates.putAll(snap.exportRates);

        profile.importDemands.clear();
        for(Map.Entry<String, Float> entry : snap.importDemands.entrySet()){
            if(entry.getValue() != null && entry.getValue() > 0f){
                profile.importDemands.put(entry.getKey(), entry.getValue());
            }
        }
        return true;
    }

    /** Queues an incoming cross-sector logistics delivery to be pushed to the detached guest. */
    public synchronized void queueDelivery(String planetName, int sectorId, String itemName, int amount){
        if(planetName == null || itemName == null || amount <= 0) return;
        SectorOccupancy.SectorKey key = new SectorOccupancy.SectorKey(planetName, sectorId);
        HashMap<String, Integer> map = pendingDeliveries.computeIfAbsent(key, k -> new HashMap<>());
        map.put(itemName, map.getOrDefault(itemName, 0) + amount);
    }

    /** Drains queued deliveries for the specified remote sector. */
    public synchronized Map<String, Integer> drainDeliveries(String planetName, int sectorId){
        SectorOccupancy.SectorKey key = new SectorOccupancy.SectorKey(planetName, sectorId);
        HashMap<String, Integer> removed = pendingDeliveries.remove(key);
        return removed == null ? Collections.emptyMap() : removed;
    }

    /** Serializes the currently active local sector on a detached guest for upload to host. */
    public static byte[] serializeLocalStrategySnapshot(Sector sector){
        try{
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            DataOutputStream out = new DataOutputStream(baos);

            out.writeUTF(sector.planet.name);
            out.writeInt(sector.id);
            boolean legacyPads = sector.planet.campaignRules.legacyLaunchPads;
            CoreBuild core = state.rules.defaultTeam != null ? state.rules.defaultTeam.core() : null;

            out.writeBoolean(core != null || sector.hasBase());
            out.writeBoolean(sector.isAttacked());
            out.writeInt(core != null ? core.storageCapacity : sector.info.storageCapacity);
            out.writeBoolean(!legacyPads);

            // Core items
            List<Item> items = new ArrayList<>();
            for(Item item : content.items()){
                int amt = core != null ? core.items.get(item) : sector.info.items.get(item);
                if(amt > 0) items.add(item);
            }
            out.writeInt(items.size());
            for(Item item : items){
                out.writeUTF(item.name);
                out.writeInt(core != null ? core.items.get(item) : sector.info.items.get(item));
            }

            // Export rates (non-legacy mode)
            HashMap<String, Float> exports = new HashMap<>();
            if(!legacyPads){
                sector.info.export.each((item, stat) -> {
                    if(stat != null && stat.mean > 1e-4f){
                        exports.put(item.name, stat.mean);
                    }
                });
            }
            out.writeInt(exports.size());
            for(Map.Entry<String, Float> e : exports.entrySet()){
                out.writeUTF(e.getKey());
                out.writeFloat(e.getValue());
            }

            // LandingPad import demands (non-legacy mode)
            HashMap<String, Float> demands = new HashMap<>();
            if(!legacyPads && state.rules.defaultTeam != null){
                for(var build : state.rules.defaultTeam.data().buildings){
                    if(build == null || !build.enabled) continue;
                    if(build instanceof LandingPad.LandingPadBuild pad && build.block instanceof LandingPad block && pad.config != null){
                        float cycleTicks = Math.max(1f, block.cooldownTime + block.arrivalDuration);
                        float ratePerSec = (60f / cycleTicks) * block.itemCapacity;
                        demands.put(pad.config.name, demands.getOrDefault(pad.config.name, 0f) + ratePerSec);
                    }
                }
            }
            out.writeInt(demands.size());
            for(Map.Entry<String, Float> e : demands.entrySet()){
                out.writeUTF(e.getKey());
                out.writeFloat(e.getValue());
            }

            out.flush();
            return baos.toByteArray();
        }catch(IOException e){
            throw new RuntimeException("Failed to serialize local strategy snapshot", e);
        }
    }

    public static RemoteSectorSnapshot deserializeSnapshot(byte[] data, long nowMs) throws IOException{
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))){
            String planetName = in.readUTF();
            int sectorId = in.readInt();
            RemoteSectorSnapshot snap = new RemoteSectorSnapshot(planetName, sectorId);
            snap.hasBase = in.readBoolean();
            snap.underAttack = in.readBoolean();
            snap.storageCapacity = in.readInt();
            snap.requireExplicitImportDemand = in.readBoolean();

            int itemCount = in.readInt();
            for(int i = 0; i < itemCount; i++){
                snap.coreItems.put(in.readUTF(), in.readInt());
            }

            int exportCount = in.readInt();
            for(int i = 0; i < exportCount; i++){
                snap.exportRates.put(in.readUTF(), in.readFloat());
            }

            int demandCount = in.readInt();
            for(int i = 0; i < demandCount; i++){
                snap.importDemands.put(in.readUTF(), in.readFloat());
            }

            snap.updatedMs = nowMs;
            return snap;
        }
    }

    public static byte[] serializeDeliveries(String planetName, int sectorId, Map<String, Integer> deliveries){
        try{
            ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
            DataOutputStream out = new DataOutputStream(baos);
            out.writeUTF(planetName);
            out.writeInt(sectorId);
            out.writeInt(deliveries.size());
            for(Map.Entry<String, Integer> entry : deliveries.entrySet()){
                out.writeUTF(entry.getKey());
                out.writeInt(entry.getValue());
            }
            out.flush();
            return baos.toByteArray();
        }catch(IOException e){
            throw new RuntimeException("Failed to serialize logistics deliveries", e);
        }
    }

    /** Deposits host-routed incoming logistics deliveries into the detached guest's live core. */
    public static void applyDeliveriesLocally(byte[] data) throws IOException{
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))){
            String planetName = in.readUTF();
            int sectorId = in.readInt();
            int count = in.readInt();

            Sector current = state.getSector();
            if(current == null || current.id != sectorId || !current.planet.name.equals(planetName)){
                return;
            }

            CoreBuild core = state.rules.defaultTeam != null ? state.rules.defaultTeam.core() : null;
            for(int i = 0; i < count; i++){
                String itemName = in.readUTF();
                int qty = in.readInt();
                if(qty <= 0) continue;
                Item item = content.item(itemName);
                if(item == null) continue;
                if(core != null){
                    int room = Math.max(0, core.storageCapacity - core.items.get(item));
                    int accepted = Math.min(qty, room);
                    if(accepted > 0){
                        core.items.add(item, accepted);
                    }
                }else{
                    current.info.items.add(item, qty);
                }
            }
        }
    }
}
