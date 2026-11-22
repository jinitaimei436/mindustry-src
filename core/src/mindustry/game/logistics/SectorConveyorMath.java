package mindustry.game.logistics;

import java.io.*;
import java.util.*;

/**
 * Pure-Java mathematical, topological, and FIFO backpressure engine for the V2.8
 * Cross-Sector Conveyor system ("全球资源视野与区块间传送带").
 * Zero runtime dependency on Arc/Mindustry globals so all rules can be unit-tested on the JVM.
 *
 * Design guarantees:
 * - Each sector allows at most 4 senders total (Junior + Senior combined) and unlimited receivers.
 * - Both senders and receivers receive stable auto-assigned lowest-available port numbers (1, 2, 3...).
 * - Links are strictly 1-to-1 between a sender and a receiver in directly adjacent sectors.
 * - Cross-tier links (Junior <-> Senior) are allowed and capped at {@code min(senderRate, receiverRate)}.
 * - Multi-item FIFO queues preserve arrival order; full receiver buffers apply lossless backpressure.
 */
public final class SectorConveyorMath{
    public static final int STREAM_VERSION = 1;

    public static final int MAX_SENDERS_PER_SECTOR = 4;

    public static final int TIER_JUNIOR = 1;
    public static final int TIER_SENIOR = 2;

    public static final int JUNIOR_CAPACITY = 20;
    public static final int SENIOR_CAPACITY = 40;

    public static final float JUNIOR_RATE = 2f;
    public static final float SENIOR_RATE = 4f;
    public static final float PORT_INTAKE_RATE = 60f;

    private SectorConveyorMath(){
    }

    public static int capacityForTier(int tier){
        return tier == TIER_SENIOR ? SENIOR_CAPACITY : JUNIOR_CAPACITY;
    }

    public static float rateForTier(int tier){
        return tier == TIER_SENIOR ? SENIOR_RATE : JUNIOR_RATE;
    }

    /**
     * Computes the exact outside adjacent tile coordinates for the active input or output face
     * of a directional 3x3 or 4x4 block.
     *
     * Matches Mindustry's {@code Edges.getFacingEdge} anchor convention:
     * occupied tiles span {@code [-(size - 1) / 2 .. size / 2]} around {@code (tileX, tileY)}.
     * - For size 3 (odd): occupied [-1, 0, +1] -> middle 1 tile has tangent offset {0}.
     * - For size 4 (even): occupied [-1, 0, +1, +2] -> middle 2 tiles have tangent offsets {0, +1}.
     *
     * @param tileX block anchor tile X
     * @param tileY block anchor tile Y
     * @param size block size (3 or 4)
     * @param rotation block facing rotation (0=+X, 1=+Y, 2=-X, 3=-Y)
     * @param frontFace true for front/output face (receiver), false for back/input face (sender)
     * @return array of {@code [x, y]} outside tile coordinates (length 1 for size 3, length 2 for size 4)
     */
    public static int[][] computePortFaceTiles(int tileX, int tileY, int size, int rotation, boolean frontFace){
        int rot = ((rotation % 4) + 4) % 4;
        int faceDir = frontFace ? rot : (rot + 2) & 3;

        int minOff = -(size - 1) / 2;
        int maxOff = size / 2;
        int[] tangentOffsets = (size % 2 == 0)
            ? new int[]{0, 1}
            : new int[]{0};

        int[][] out = new int[tangentOffsets.length][2];
        for(int i = 0; i < tangentOffsets.length; i++){
            int t = tangentOffsets[i];
            int x = tileX;
            int y = tileY;
            switch(faceDir){
                case 0 -> { // +X face
                    x = tileX + maxOff + 1;
                    y = tileY + t;
                }
                case 1 -> { // +Y face
                    x = tileX + t;
                    y = tileY + maxOff + 1;
                }
                case 2 -> { // -X face
                    x = tileX + minOff - 1;
                    y = tileY + t;
                }
                case 3 -> { // -Y face
                    x = tileX + t;
                    y = tileY + minOff - 1;
                }
                default -> {}
            }
            out[i][0] = x;
            out[i][1] = y;
        }
        return out;
    }

    /** Packs (sectorId, isSender, portNumber) into a unique 64-bit key. */
    public static long packPortKey(int sectorId, boolean isSender, int portNumber){
        long secBits = ((long)(sectorId & 0x3fffffff)) << 33;
        long roleBit = isSender ? (1L << 32) : 0L;
        long numBits = portNumber & 0xffffffffL;
        return secBits | roleBit | numBits;
    }

    /** Immutable identity of one sender or receiver port on a planet. */
    public static class PortKey{
        public final int sectorId;
        public final boolean isSender;
        public final int portNumber;

        public PortKey(int sectorId, boolean isSender, int portNumber){
            this.sectorId = sectorId;
            this.isSender = isSender;
            this.portNumber = portNumber;
        }

        public long packed(){
            return packPortKey(sectorId, isSender, portNumber);
        }

        @Override
        public boolean equals(Object o){
            if(this == o) return true;
            if(!(o instanceof PortKey other)) return false;
            return sectorId == other.sectorId && isSender == other.isSender && portNumber == other.portNumber;
        }

        @Override
        public int hashCode(){
            int h = sectorId;
            h = 31 * h + (isSender ? 1 : 0);
            h = 31 * h + portNumber;
            return h;
        }
    }

    /** Strict 1-to-1 directional link from a sender port to an adjacent sector's receiver port. */
    public static class SectorConveyorLink{
        public final int senderSectorId;
        public final int senderPortNumber;
        public final int receiverSectorId;
        public final int receiverPortNumber;

        public SectorConveyorLink(int senderSectorId, int senderPortNumber, int receiverSectorId, int receiverPortNumber){
            this.senderSectorId = senderSectorId;
            this.senderPortNumber = senderPortNumber;
            this.receiverSectorId = receiverSectorId;
            this.receiverPortNumber = receiverPortNumber;
        }

        public long senderKey(){
            return packPortKey(senderSectorId, true, senderPortNumber);
        }

        public long receiverKey(){
            return packPortKey(receiverSectorId, false, receiverPortNumber);
        }

        @Override
        public boolean equals(Object o){
            if(this == o) return true;
            if(!(o instanceof SectorConveyorLink other)) return false;
            return senderSectorId == other.senderSectorId
                && senderPortNumber == other.senderPortNumber
                && receiverSectorId == other.receiverSectorId
                && receiverPortNumber == other.receiverPortNumber;
        }

        @Override
        public int hashCode(){
            int h = senderSectorId;
            h = 31 * h + senderPortNumber;
            h = 31 * h + receiverSectorId;
            h = 31 * h + receiverPortNumber;
            return h;
        }
    }

    /** Persistent & live state of one cross-sector conveyor sender or receiver port. */
    public static class SectorPortRecord{
        public final int sectorId;
        public final int portNumber;
        public final boolean isSender;
        public int tier;
        public int capacity;
        public float transferRate;
        /** Ordered multi-item FIFO buffer of Mindustry Item IDs (length strictly <= capacity). */
        public final ArrayDeque<Integer> fifo = new ArrayDeque<>();
        /** Fractional item transfer accumulator (for smooth 2/s or 4/s dispatch). */
        public float transferCarry = 0f;
        /** Fractional cross-sector receive accumulator (capped at 60/s). */
        public float receiveCarry = 0f;
        /** True when sender has items waiting but its linked receiver buffer is completely full. */
        public boolean backpressurePaused = false;
        /** Last item ID that moved through this port (-1 if none recently). */
        public int lastItemId = -1;
        /** Total measured throughput (items/sec) over the latest 1-second window. */
        public float recentTotalRate = 0f;
        /** Per-item measured throughput (itemId -> items/sec) over the latest 1-second window. */
        public final java.util.Map<Integer, Float> itemRatesPerSec = new LinkedHashMap<>();

        // Sliding 1-second telemetry accumulator (transient)
        private final java.util.Map<Integer, Integer> windowCounts = new LinkedHashMap<>();
        private float windowElapsedSec = 0f;

        public SectorPortRecord(int sectorId, int portNumber, boolean isSender, int tier){
            this.sectorId = sectorId;
            this.portNumber = portNumber;
            this.isSender = isSender;
            setTier(tier);
        }

        public void setTier(int tier){
            this.tier = (tier == TIER_SENIOR) ? TIER_SENIOR : TIER_JUNIOR;
            this.capacity = capacityForTier(this.tier);
            this.transferRate = rateForTier(this.tier);
            while(fifo.size() > capacity){
                fifo.pollLast();
            }
        }

        public int bufferedCount(){
            return fifo.size();
        }

        public int freeSpace(){
            return Math.max(0, capacity - fifo.size());
        }

        public boolean isFull(){
            return fifo.size() >= capacity;
        }

        public boolean isEmpty(){
            return fifo.isEmpty();
        }

        public boolean enqueue(int itemId){
            if(itemId < 0 || fifo.size() >= capacity) return false;
            fifo.addLast(itemId);
            return true;
        }

        public int peek(){
            Integer head = fifo.peekFirst();
            return head == null ? -1 : head;
        }

        public int dequeue(){
            Integer head = fifo.pollFirst();
            return head == null ? -1 : head;
        }

        public void recordMovedItem(int itemId){
            if(itemId < 0) return;
            lastItemId = itemId;
            windowCounts.merge(itemId, 1, Integer::sum);
        }

        /** Advances the 1-second throughput measurement window cleanly. */
        public void advanceRateWindow(float dtSeconds){
            if(dtSeconds <= 0f) return;
            windowElapsedSec += dtSeconds;
            if(windowElapsedSec >= 1.0f){
                float inv = 1f / windowElapsedSec;
                itemRatesPerSec.clear();
                float total = 0f;
                for(java.util.Map.Entry<Integer, Integer> e : windowCounts.entrySet()){
                    float rate = e.getValue() * inv;
                    if(rate > 0.001f){
                        itemRatesPerSec.put(e.getKey(), rate);
                        total += rate;
                    }
                }
                recentTotalRate = total;
                windowCounts.clear();
                windowElapsedSec = 0f;
            }
        }

        /** Copies persisted item counts/rates from another snapshot (for V2 network sync). */
        public void replaceFifo(List<Integer> items){
            fifo.clear();
            if(items == null) return;
            for(int i = 0; i < items.size() && fifo.size() < capacity; i++){
                int id = items.get(i);
                if(id >= 0) fifo.addLast(id);
            }
        }
    }

    /**
     * Mutable planetary cross-sector conveyor topology (one instance per Planet).
     * Enforces:
     * - Max 4 senders per sector (Junior + Senior combined), unlimited receivers
     * - Strict 1-to-1 links between adjacent sectors only
     * - Mixed-tier rate capping at min(senderRate, receiverRate)
     * - Multi-item FIFO lossless backpressure
     */
    public static class PlanetConveyorTopology{
        private final java.util.Map<Long, SectorPortRecord> ports = new HashMap<>();
        private final java.util.Map<Long, SectorConveyorLink> linksBySender = new HashMap<>();
        private final java.util.Map<Long, SectorConveyorLink> linksByReceiver = new HashMap<>();

        public void clear(){
            ports.clear();
            linksBySender.clear();
            linksByReceiver.clear();
        }

        public int countSendersInSector(int sectorId){
            int count = 0;
            for(SectorPortRecord p : ports.values()){
                if(p.sectorId == sectorId && p.isSender){
                    count++;
                }
            }
            return count;
        }

        public int countReceiversInSector(int sectorId){
            int count = 0;
            for(SectorPortRecord p : ports.values()){
                if(p.sectorId == sectorId && !p.isSender){
                    count++;
                }
            }
            return count;
        }

        public boolean hasAnyPort(int sectorId){
            for(SectorPortRecord p : ports.values()){
                if(p.sectorId == sectorId) return true;
            }
            return false;
        }

        public Set<Integer> getPortSectorIds(){
            Set<Integer> ids = new LinkedHashSet<>();
            for(SectorPortRecord p : ports.values()){
                ids.add(p.sectorId);
            }
            return ids;
        }

        /**
         * Allocates the lowest unused positive port number for a sector & role.
         * For senders, strictly enforces {@link #MAX_SENDERS_PER_SECTOR} (returns -1 if full).
         */
        public int allocatePortNumber(int sectorId, boolean isSender){
            if(isSender && countSendersInSector(sectorId) >= MAX_SENDERS_PER_SECTOR){
                return -1;
            }
            int num = 1;
            while(ports.containsKey(packPortKey(sectorId, isSender, num))){
                num++;
                if(isSender && num > MAX_SENDERS_PER_SECTOR){
                    return -1;
                }
            }
            return num;
        }

        public SectorPortRecord getPort(int sectorId, boolean isSender, int portNumber){
            return ports.get(packPortKey(sectorId, isSender, portNumber));
        }

        public List<SectorPortRecord> getSectorPorts(int sectorId, boolean isSender){
            List<SectorPortRecord> out = new ArrayList<>();
            for(SectorPortRecord p : ports.values()){
                if(p.sectorId == sectorId && p.isSender == isSender){
                    out.add(p);
                }
            }
            out.sort((a, b) -> Integer.compare(a.portNumber, b.portNumber));
            return out;
        }

        public List<SectorPortRecord> getAllSectorPorts(int sectorId){
            List<SectorPortRecord> out = new ArrayList<>();
            for(SectorPortRecord p : ports.values()){
                if(p.sectorId == sectorId){
                    out.add(p);
                }
            }
            out.sort((a, b) -> {
                if(a.isSender != b.isSender) return a.isSender ? -1 : 1;
                return Integer.compare(a.portNumber, b.portNumber);
            });
            return out;
        }

        /**
         * Registers or updates a port record. Preserves existing FIFO buffer if already registered
         * (essential when entering a sector whose background receivers already collected items!).
         */
        public SectorPortRecord registerPort(int sectorId, int portNumber, boolean isSender, int tier){
            if(portNumber <= 0) return null;
            if(isSender && portNumber > MAX_SENDERS_PER_SECTOR) return null;
            long key = packPortKey(sectorId, isSender, portNumber);
            SectorPortRecord existing = ports.get(key);
            if(existing != null){
                existing.setTier(tier);
                return existing;
            }
            if(isSender && countSendersInSector(sectorId) >= MAX_SENDERS_PER_SECTOR){
                return null;
            }
            SectorPortRecord rec = new SectorPortRecord(sectorId, portNumber, isSender, tier);
            ports.put(key, rec);
            return rec;
        }

        /** Removes a port and automatically severs any 1-to-1 link attached to it. */
        public boolean unregisterPort(int sectorId, int portNumber, boolean isSender){
            long key = packPortKey(sectorId, isSender, portNumber);
            SectorPortRecord removed = ports.remove(key);
            boolean linkRemoved = isSender
                ? unbindSender(sectorId, portNumber)
                : unbindReceiver(sectorId, portNumber);
            return removed != null || linkRemoved;
        }

        /** Removes all ports belonging to a sector (e.g. sector lost/abandoned) and severs all links. */
        public boolean clearSector(int sectorId){
            List<SectorPortRecord> list = getAllSectorPorts(sectorId);
            if(list.isEmpty()) return false;
            for(SectorPortRecord p : list){
                unregisterPort(p.sectorId, p.portNumber, p.isSender);
            }
            return true;
        }

        /** Reconciles a live sector's active ports against persisted topology (prunes removed ports cleanly). */
        public boolean reconcileSectorPorts(int sectorId, Set<Long> livePortKeys){
            boolean changed = false;
            List<SectorPortRecord> existing = getAllSectorPorts(sectorId);
            for(SectorPortRecord p : existing){
                long key = packPortKey(p.sectorId, p.isSender, p.portNumber);
                if(!livePortKeys.contains(key)){
                    unregisterPort(p.sectorId, p.portNumber, p.isSender);
                    changed = true;
                }
            }
            return changed;
        }

        // ==================== STRICT 1-TO-1 LINKING ====================

        public SectorConveyorLink getLinkForSender(int sectorId, int portNumber){
            return linksBySender.get(packPortKey(sectorId, true, portNumber));
        }

        public SectorConveyorLink getLinkForReceiver(int sectorId, int portNumber){
            return linksByReceiver.get(packPortKey(sectorId, false, portNumber));
        }

        public Collection<SectorConveyorLink> getAllLinks(){
            return linksBySender.values();
        }

        public boolean isPortLinked(int sectorId, boolean isSender, int portNumber){
            long key = packPortKey(sectorId, isSender, portNumber);
            return isSender ? linksBySender.containsKey(key) : linksByReceiver.containsKey(key);
        }

        /** Checks whether a strict 1-to-1 link can be established between sender and receiver. */
        public boolean canBind(int senderSectorId, int senderPortNumber, int receiverSectorId, int receiverPortNumber, boolean areAdjacent){
            if(!areAdjacent || senderSectorId == receiverSectorId) return false;
            long sKey = packPortKey(senderSectorId, true, senderPortNumber);
            long rKey = packPortKey(receiverSectorId, false, receiverPortNumber);
            if(!ports.containsKey(sKey) || !ports.containsKey(rKey)) return false;
            return !linksBySender.containsKey(sKey) && !linksByReceiver.containsKey(rKey);
        }

        /** Binds one sender to one receiver (strict 1-to-1, adjacent sectors only). */
        public boolean bind(int senderSectorId, int senderPortNumber, int receiverSectorId, int receiverPortNumber, boolean areAdjacent){
            if(!canBind(senderSectorId, senderPortNumber, receiverSectorId, receiverPortNumber, areAdjacent)){
                return false;
            }
            SectorConveyorLink link = new SectorConveyorLink(senderSectorId, senderPortNumber, receiverSectorId, receiverPortNumber);
            linksBySender.put(link.senderKey(), link);
            linksByReceiver.put(link.receiverKey(), link);
            return true;
        }

        public boolean unbindSender(int senderSectorId, int senderPortNumber){
            long sKey = packPortKey(senderSectorId, true, senderPortNumber);
            SectorConveyorLink removed = linksBySender.remove(sKey);
            if(removed == null) return false;
            linksByReceiver.remove(removed.receiverKey());
            SectorPortRecord sp = ports.get(sKey);
            if(sp != null) sp.backpressurePaused = false;
            return true;
        }

        public boolean unbindReceiver(int receiverSectorId, int receiverPortNumber){
            long rKey = packPortKey(receiverSectorId, false, receiverPortNumber);
            SectorConveyorLink removed = linksByReceiver.remove(rKey);
            if(removed == null) return false;
            linksBySender.remove(removed.senderKey());
            SectorPortRecord sp = ports.get(removed.senderKey());
            if(sp != null) sp.backpressurePaused = false;
            return true;
        }

        /** Cross-tier links cap speed at the slower endpoint (Junior=2/s, Senior=4/s). */
        public float effectiveLinkRate(SectorConveyorLink link){
            if(link == null) return 0f;
            SectorPortRecord s = ports.get(link.senderKey());
            SectorPortRecord r = ports.get(link.receiverKey());
            if(s == null || r == null) return 0f;
            return Math.min(s.transferRate, r.transferRate);
        }

        // ==================== FIFO TRANSFER & LOSSLESS BACKPRESSURE ====================

        /**
         * Steps one linked sender port by {@code dtSeconds}, moving items from sender FIFO
         * to linked receiver FIFO at {@code min(senderRate, receiverRate) * senderEfficiency},
         * capped by receiver's 60/s intake ceiling and free capacity (zero item loss on full).
         *
         * @return number of items transferred during this step.
         */
        public int stepSenderTransfer(int senderSectorId, int senderPortNumber, float dtSeconds, float senderEfficiency){
            SectorPortRecord sender = getPort(senderSectorId, true, senderPortNumber);
            if(sender == null) return 0;

            float dt = Math.max(0f, dtSeconds);
            sender.advanceRateWindow(dt);

            SectorConveyorLink link = getLinkForSender(senderSectorId, senderPortNumber);
            if(link == null){
                sender.backpressurePaused = false;
                sender.transferCarry = 0f;
                return 0;
            }

            SectorPortRecord receiver = ports.get(link.receiverKey());
            if(receiver == null){
                sender.backpressurePaused = false;
                return 0;
            }

            float eff = Math.max(0f, Math.min(1f, senderEfficiency));
            if(sender.isEmpty()){
                sender.backpressurePaused = false;
                // Keep small carry clamped so restarting is crisp without bursts
                sender.transferCarry = Math.min(sender.transferCarry, 1f);
                return 0;
            }

            if(receiver.isFull()){
                // Lossless backpressure: pause transfer until receiver frees at least 1 slot
                sender.backpressurePaused = true;
                sender.transferCarry = Math.min(sender.transferCarry, 1f);
                return 0;
            }

            sender.backpressurePaused = false;
            float rate = Math.min(sender.transferRate, receiver.transferRate) * eff;
            if(rate <= 0.0001f) return 0;

            sender.transferCarry = Math.min(sender.transferCarry + rate * dt, Math.max(1f, rate));
            receiver.receiveCarry = Math.min(receiver.receiveCarry + PORT_INTAKE_RATE * dt, PORT_INTAKE_RATE);

            int maxBySend = (int)Math.floor(sender.transferCarry);
            int maxByRecvIntake = (int)Math.floor(receiver.receiveCarry);
            int toMove = Math.min(Math.min(maxBySend, maxByRecvIntake), Math.min(sender.bufferedCount(), receiver.freeSpace()));

            int moved = 0;
            for(int i = 0; i < toMove; i++){
                int itemId = sender.dequeue();
                if(itemId < 0) break;
                if(receiver.enqueue(itemId)){
                    sender.recordMovedItem(itemId);
                    receiver.recordMovedItem(itemId);
                    moved++;
                }else{
                    // Defensive rollback: never lose an item
                    sender.fifo.addFirst(itemId);
                    sender.backpressurePaused = true;
                    break;
                }
            }

            if(moved > 0){
                sender.transferCarry -= moved;
                receiver.receiveCarry -= moved;
            }

            if(!sender.isEmpty() && receiver.isFull()){
                sender.backpressurePaused = true;
            }

            return moved;
        }

        /**
         * Steps background-only links (whose sender sector is NOT actively simulated in the foreground)
         * and advances telemetry windows for background receivers.
         */
        public void stepBackgroundLinks(Set<Integer> activelySimulatedSectorIds, float dtSeconds){
            if(dtSeconds <= 0f || ports.isEmpty()) return;

            for(SectorConveyorLink link : linksBySender.values()){
                boolean senderSimulated = activelySimulatedSectorIds != null && activelySimulatedSectorIds.contains(link.senderSectorId);
                if(!senderSimulated){
                    stepSenderTransfer(link.senderSectorId, link.senderPortNumber, dtSeconds, 1f);
                }
            }

            for(SectorPortRecord p : ports.values()){
                boolean simulated = activelySimulatedSectorIds != null && activelySimulatedSectorIds.contains(p.sectorId);
                if(!simulated){
                    if(!p.isSender){
                        p.advanceRateWindow(dtSeconds);
                    }else if(!linksBySender.containsKey(packPortKey(p.sectorId, true, p.portNumber))){
                        p.advanceRateWindow(dtSeconds);
                    }
                }
            }
        }

        // ==================== BINARY SERIALIZATION ====================

        public void writeToStream(DataOutput out) throws IOException{
            out.writeInt(STREAM_VERSION);

            List<SectorPortRecord> sortedPorts = new ArrayList<>(ports.values());
            sortedPorts.sort((a, b) -> {
                int c = Integer.compare(a.sectorId, b.sectorId);
                if(c != 0) return c;
                if(a.isSender != b.isSender) return a.isSender ? -1 : 1;
                return Integer.compare(a.portNumber, b.portNumber);
            });

            out.writeInt(sortedPorts.size());
            for(SectorPortRecord p : sortedPorts){
                out.writeInt(p.sectorId);
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

            List<SectorConveyorLink> sortedLinks = new ArrayList<>(linksBySender.values());
            sortedLinks.sort((a, b) -> {
                int c = Integer.compare(a.senderSectorId, b.senderSectorId);
                return c != 0 ? c : Integer.compare(a.senderPortNumber, b.senderPortNumber);
            });

            out.writeInt(sortedLinks.size());
            for(SectorConveyorLink l : sortedLinks){
                out.writeInt(l.senderSectorId);
                out.writeInt(l.senderPortNumber);
                out.writeInt(l.receiverSectorId);
                out.writeInt(l.receiverPortNumber);
            }
        }

        public void readFromStream(DataInput in) throws IOException{
            clear();
            int ver = in.readInt();
            if(ver != STREAM_VERSION) return;

            int portCount = Math.max(0, in.readInt());
            for(int i = 0; i < portCount; i++){
                int sectorId = in.readInt();
                int portNumber = in.readInt();
                boolean isSender = in.readBoolean();
                int tier = in.readByte();
                boolean paused = in.readBoolean();
                int lastItem = in.readInt();
                float totalRate = in.readFloat();

                SectorPortRecord rec = registerPort(sectorId, portNumber, isSender, tier);
                int fifoCount = Math.max(0, in.readInt());
                for(int j = 0; j < fifoCount; j++){
                    int itemId = in.readInt();
                    if(rec != null) rec.enqueue(itemId);
                }
                int rateCount = Math.max(0, in.readInt());
                for(int j = 0; j < rateCount; j++){
                    int itemId = in.readInt();
                    float rate = in.readFloat();
                    if(rec != null && rate > 0.001f){
                        rec.itemRatesPerSec.put(itemId, rate);
                    }
                }
                if(rec != null){
                    rec.backpressurePaused = paused;
                    rec.lastItemId = lastItem;
                    rec.recentTotalRate = totalRate;
                }
            }

            int linkCount = Math.max(0, in.readInt());
            for(int i = 0; i < linkCount; i++){
                int sSec = in.readInt();
                int sPort = in.readInt();
                int rSec = in.readInt();
                int rPort = in.readInt();
                // Links persisted on disk were validated adjacent when created; enforce 1-to-1 + port existence on load
                bind(sSec, sPort, rSec, rPort, sSec != rSec);
            }
        }
    }
}
