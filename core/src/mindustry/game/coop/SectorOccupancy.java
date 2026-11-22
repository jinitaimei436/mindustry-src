package mindustry.game.coop;

import arc.util.*;

import java.io.*;
import java.util.*;

/**
 * Tracks real-time player occupancy and single-holder simulation leases across
 * all planets and sectors in V2 multi-sector campaign multiplayer.
 *
 * Pure-Java data structure (no Arc runtime dependencies) so it can be unit-tested
 * independently and serialized compactly over the network.
 */
public class SectorOccupancy{
    public static final class SectorKey{
        public final String planetName;
        public final int sectorId;

        public SectorKey(String planetName, int sectorId){
            this.planetName = planetName == null ? "" : planetName;
            this.sectorId = sectorId;
        }

        @Override
        public boolean equals(Object o){
            if(this == o) return true;
            if(!(o instanceof SectorKey other)) return false;
            return sectorId == other.sectorId && planetName.equals(other.planetName);
        }

        @Override
        public int hashCode(){
            return 31 * planetName.hashCode() + sectorId;
        }

        @Override
        public String toString(){
            return planetName + "#" + sectorId;
        }
    }

    public static String sectorKey(String planetName, int sectorId){
        return (planetName == null ? "" : planetName) + "#" + sectorId;
    }

    public static final class PlayerPresence{
        public final String uuid;
        public String name;
        public String planetName;
        public int sectorId;
        public ClientSectorMode mode;

        public PlayerPresence(String uuid, String name, String planetName, int sectorId, ClientSectorMode mode){
            this.uuid = uuid;
            this.name = name;
            this.planetName = planetName == null ? "" : planetName;
            this.sectorId = sectorId;
            this.mode = mode;
        }

        public SectorKey sectorKey(){
            return new SectorKey(planetName, sectorId);
        }
    }

    public static final class SectorLease{
        public final SectorKey key;
        public final String holderUuid;
        public long lastHeartbeatMs;

        public SectorLease(SectorKey key, String holderUuid, long lastHeartbeatMs){
            this.key = key;
            this.holderUuid = holderUuid;
            this.lastHeartbeatMs = lastHeartbeatMs;
        }
    }

    /** Authoritative presence map on host, keyed by player UUID. */
    private final LinkedHashMap<String, PlayerPresence> presences = new LinkedHashMap<>();
    /** Active remote-sector leases on host, keyed by SectorKey. */
    private final HashMap<SectorKey, SectorLease> leases = new HashMap<>();
    /** Cached per-sector player counts (authoritative on host, mirrored on clients). */
    private final HashMap<SectorKey, Integer> sectorCounts = new HashMap<>();
    /** Total online player count across the entire campaign session. */
    private int totalPlayers = 0;

    public synchronized void clear(){
        presences.clear();
        leases.clear();
        sectorCounts.clear();
        totalPlayers = 0;
    }

    public synchronized void setPlayerPresence(String uuid, String name, String planetName, int sectorId, ClientSectorMode mode){
        if(uuid == null || planetName == null) return;
        presences.put(uuid, new PlayerPresence(uuid, name == null ? "" : name, planetName, sectorId, mode));
        recomputeCounts();
    }

    public synchronized @Nullable PlayerPresence getPresence(String uuid){
        return uuid == null ? null : presences.get(uuid);
    }

    public synchronized List<PlayerPresence> getAllPresences(){
        return new ArrayList<>(presences.values());
    }

    public synchronized void removePlayer(String uuid){
        if(uuid == null) return;
        presences.remove(uuid);
        releaseLeasesHeldBy(uuid);
        recomputeCounts();
    }

    public synchronized boolean acquireLease(String planetName, int sectorId, String holderUuid, long nowMs){
        if(planetName == null || holderUuid == null) return false;
        SectorKey key = new SectorKey(planetName, sectorId);
        SectorLease existing = leases.get(key);
        if(existing != null && !existing.holderUuid.equals(holderUuid)){
            return false;
        }
        // A guest can only hold one detached sector lease at a time.
        releaseLeasesHeldBy(holderUuid);
        leases.put(key, new SectorLease(key, holderUuid, nowMs));
        return true;
    }

    public synchronized void heartbeatLease(String planetName, int sectorId, String holderUuid, long nowMs){
        if(planetName == null || holderUuid == null) return;
        SectorKey key = new SectorKey(planetName, sectorId);
        SectorLease lease = leases.get(key);
        if(lease != null && lease.holderUuid.equals(holderUuid)){
            lease.lastHeartbeatMs = nowMs;
        }
    }

    public synchronized @Nullable SectorLease getLease(String planetName, int sectorId){
        if(planetName == null) return null;
        return leases.get(new SectorKey(planetName, sectorId));
    }

    public synchronized @Nullable String getLeaseHolder(String planetName, int sectorId){
        SectorLease lease = getLease(planetName, sectorId);
        return lease == null ? null : lease.holderUuid;
    }

    public synchronized boolean isLeasedByOther(String planetName, int sectorId, String requesterUuid){
        String holder = getLeaseHolder(planetName, sectorId);
        return holder != null && !holder.equals(requesterUuid);
    }

    public synchronized @Nullable SectorLease releaseSectorLease(String planetName, int sectorId){
        if(planetName == null) return null;
        return leases.remove(new SectorKey(planetName, sectorId));
    }

    public synchronized void releaseLeasesHeldBy(String holderUuid){
        if(holderUuid == null) return;
        leases.values().removeIf(l -> holderUuid.equals(l.holderUuid));
    }

    public synchronized List<SectorLease> expireStaleLeases(long nowMs, long timeoutMs){
        List<SectorLease> expired = new ArrayList<>();
        Iterator<Map.Entry<SectorKey, SectorLease>> it = leases.entrySet().iterator();
        while(it.hasNext()){
            SectorLease lease = it.next().getValue();
            if(nowMs - lease.lastHeartbeatMs > timeoutMs){
                expired.add(lease);
                it.remove();
            }
        }
        return expired;
    }

    public synchronized int getPlayerCount(String planetName, int sectorId){
        if(planetName == null) return 0;
        Integer count = sectorCounts.get(new SectorKey(planetName, sectorId));
        return count == null ? 0 : count;
    }

    public synchronized boolean isOccupied(String planetName, int sectorId){
        return getPlayerCount(planetName, sectorId) > 0;
    }

    public synchronized int getTotalPlayerCount(){
        return totalPlayers;
    }

    public synchronized Map<SectorKey, Integer> getOccupiedSectors(){
        return new HashMap<>(sectorCounts);
    }

    private void recomputeCounts(){
        sectorCounts.clear();
        for(PlayerPresence p : presences.values()){
            if(p.planetName.isEmpty() || p.sectorId < 0) continue;
            SectorKey key = p.sectorKey();
            sectorCounts.put(key, sectorCounts.getOrDefault(key, 0) + 1);
        }
        totalPlayers = presences.size();
    }

    /** Writes compact occupancy snapshot (total player count + per-sector player counts). */
    public synchronized void writeSnapshot(DataOutputStream out) throws IOException{
        out.writeInt(totalPlayers);
        out.writeInt(sectorCounts.size());
        for(Map.Entry<SectorKey, Integer> entry : sectorCounts.entrySet()){
            out.writeUTF(entry.getKey().planetName);
            out.writeInt(entry.getKey().sectorId);
            out.writeInt(entry.getValue());
        }
    }

    /** Reads compact occupancy snapshot on clients (replaces cached counts). */
    public synchronized void readSnapshot(DataInputStream in) throws IOException{
        totalPlayers = Math.max(0, in.readInt());
        int count = Math.max(0, in.readInt());
        sectorCounts.clear();
        for(int i = 0; i < count; i++){
            String planet = in.readUTF();
            int sectorId = in.readInt();
            int players = in.readInt();
            if(players > 0){
                sectorCounts.put(new SectorKey(planet, sectorId), players);
            }
        }
    }
}
