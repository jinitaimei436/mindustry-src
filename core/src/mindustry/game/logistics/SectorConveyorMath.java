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
    public static final int STREAM_VERSION = 2;

    public static final int MAX_SENDERS_PER_SECTOR = 4;
    public static final int MAX_FEED_PATTERN_SIZE = 16;

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
        /**
         * Ordered ring sample of recently accepted item IDs on a sender port (max {@link #MAX_FEED_PATTERN_SIZE}).
         * Enables background real-time settlement to keep refilling a background sender with the exact
         * single or mixed item ratio its foreground belt supplied, without fabricating un-belted items.
         */
        public final ArrayList<Integer> backgroundFeedPattern = new ArrayList<>();
        /** Transient cursor for round-robin sampling of {@link #backgroundFeedPattern}. */
        public int backgroundFeedCursor = 0;
        /** Transient fractional accumulator for background sender refill from sector storage. */
        public float backgroundPullCarry = 0f;
        /** Transient fractional accumulator for background receiver drain into sector storage. */
        public float backgroundDrainCarry = 0f;

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

        /** Records an item physically accepted onto a sender port so background settlement remembers its feed mix. */
        public void recordAcceptedItem(int itemId){
            if(itemId < 0) return;
            lastItemId = itemId;
            if(backgroundFeedPattern.size() >= MAX_FEED_PATTERN_SIZE){
                backgroundFeedPattern.remove(0);
                if(backgroundFeedCursor > 0){
                    backgroundFeedCursor--;
                }
            }
            backgroundFeedPattern.add(itemId);
        }

        /** Seeds background feed pattern from existing FIFO / lastItemId if none has been recorded yet (old saves). */
        public void seedFeedPatternIfEmpty(){
            if(!backgroundFeedPattern.isEmpty()) return;
            if(!fifo.isEmpty()){
                for(int id : fifo){
                    if(id >= 0){
                        backgroundFeedPattern.add(id);
                        if(backgroundFeedPattern.size() >= MAX_FEED_PATTERN_SIZE) break;
                    }
                }
            }else if(lastItemId >= 0){
                backgroundFeedPattern.add(lastItemId);
            }
        }

        /** Returns the active feed pattern (seeding from FIFO/lastItemId if needed for legacy saves). */
        public List<Integer> getEffectiveFeedPattern(){
            seedFeedPatternIfEmpty();
            return backgroundFeedPattern;
        }

        /** Cycles through the observed feed pattern to pick the next item ID to pull in background settlement. */
        public int nextFeedItemId(){
            List<Integer> pattern = getEffectiveFeedPattern();
            int size = pattern.size();
            if(size == 0) return -1;
            int idx = ((backgroundFeedCursor % size) + size) % size;
            backgroundFeedCursor = (idx + 1) % size;
            return pattern.get(idx);
        }

        public void replaceFeedPattern(List<Integer> items){
            backgroundFeedPattern.clear();
            backgroundFeedCursor = 0;
            if(items == null) return;
            for(int i = 0; i < items.size() && backgroundFeedPattern.size() < MAX_FEED_PATTERN_SIZE; i++){
                int id = items.get(i);
                if(id >= 0) backgroundFeedPattern.add(id);
            }
        }

        /** Advances the 1-second throughput measurement window cleanly. */
        public void advanceRateWindow(float dtSeconds){
            advanceRateWindowTracked(dtSeconds);
        }

        /**
         * Variant of {@link #advanceRateWindow} returning true if displayed throughput changed
         * (either items moved during this window or a previously non-zero rate dropped to zero).
         */
        public boolean advanceRateWindowTracked(float dtSeconds){
            if(dtSeconds <= 0f) return false;
            windowElapsedSec += dtSeconds;
            if(windowElapsedSec >= 1.0f){
                boolean hadPriorRate = recentTotalRate > 0.001f || !itemRatesPerSec.isEmpty();
                boolean hasNewCounts = !windowCounts.isEmpty();
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
                return hadPriorRate || hasNewCounts;
            }
            return false;
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
        /** V3.2: Per-sector sorted bucket index (senders asc, then receivers asc) for O(1) lookup. */
        private final java.util.Map<Integer, ArrayList<SectorPortRecord>> portsBySector = new HashMap<>();
        private final java.util.Map<Long, SectorConveyorLink> linksBySender = new HashMap<>();
        private final java.util.Map<Long, SectorConveyorLink> linksByReceiver = new HashMap<>();

        private static int comparePorts(SectorPortRecord a, SectorPortRecord b){
            if(a.isSender != b.isSender) return a.isSender ? -1 : 1;
            return Integer.compare(a.portNumber, b.portNumber);
        }

        public void clear(){
            ports.clear();
            portsBySector.clear();
            linksBySender.clear();
            linksByReceiver.clear();
        }

        public int countSendersInSector(int sectorId){
            ArrayList<SectorPortRecord> bucket = portsBySector.get(sectorId);
            if(bucket == null || bucket.isEmpty()) return 0;
            int count = 0;
            for(int i = 0; i < bucket.size(); i++){
                if(bucket.get(i).isSender){
                    count++;
                }else{
                    break; // Senders are always ordered before receivers in each bucket
                }
            }
            return count;
        }

        public int countReceiversInSector(int sectorId){
            ArrayList<SectorPortRecord> bucket = portsBySector.get(sectorId);
            if(bucket == null || bucket.isEmpty()) return 0;
            int count = 0;
            for(int i = 0; i < bucket.size(); i++){
                if(!bucket.get(i).isSender){
                    count++;
                }
            }
            return count;
        }

        public boolean hasAnyPort(int sectorId){
            ArrayList<SectorPortRecord> bucket = portsBySector.get(sectorId);
            return bucket != null && !bucket.isEmpty();
        }

        public Set<Integer> getPortSectorIds(){
            return new LinkedHashSet<>(portsBySector.keySet());
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
            ArrayList<SectorPortRecord> bucket = portsBySector.get(sectorId);
            if(bucket == null || bucket.isEmpty()) return new ArrayList<>();
            List<SectorPortRecord> out = new ArrayList<>(bucket.size());
            for(int i = 0; i < bucket.size(); i++){
                SectorPortRecord p = bucket.get(i);
                if(p.isSender == isSender){
                    out.add(p);
                }
            }
            return out;
        }

        public List<SectorPortRecord> getAllSectorPorts(int sectorId){
            ArrayList<SectorPortRecord> bucket = portsBySector.get(sectorId);
            if(bucket == null || bucket.isEmpty()) return new ArrayList<>();
            return new ArrayList<>(bucket);
        }

        /**
         * Zero-allocation read-only view of a sector's sorted ports (senders first, then receivers).
         * Ideal for hot paths such as 1-second background settlement and PlanetDialog rendering.
         */
        public List<SectorPortRecord> getAllSectorPortsReadOnly(int sectorId){
            ArrayList<SectorPortRecord> bucket = portsBySector.get(sectorId);
            if(bucket == null || bucket.isEmpty()) return Collections.emptyList();
            return Collections.unmodifiableList(bucket);
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
            ArrayList<SectorPortRecord> bucket = portsBySector.computeIfAbsent(sectorId, k -> new ArrayList<>());
            bucket.add(rec);
            bucket.sort((a, b) -> comparePorts(a, b));
            return rec;
        }

        /** Removes a port and automatically severs any 1-to-1 link attached to it. */
        public boolean unregisterPort(int sectorId, int portNumber, boolean isSender){
            long key = packPortKey(sectorId, isSender, portNumber);
            SectorPortRecord removed = ports.remove(key);
            if(removed != null){
                ArrayList<SectorPortRecord> bucket = portsBySector.get(sectorId);
                if(bucket != null){
                    bucket.remove(removed);
                    if(bucket.isEmpty()){
                        portsBySector.remove(sectorId);
                    }
                }
            }
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
            stepBackgroundLinks(activelySimulatedSectorIds, null, null, dtSeconds);
        }

        /**
         * Power- and freeze-aware variant of {@link #stepBackgroundLinks(Set, float)}.
         * Senior Senders scale by their source sector's solved power satisfaction; frozen sectors
         * (e.g. under attack) pause background transfers without losing buffered items.
         */
        public void stepBackgroundLinks(
            Set<Integer> activelySimulatedSectorIds,
            java.util.Map<Integer, Float> sectorPowerEfficiency,
            Set<Integer> frozenSectorIds,
            float dtSeconds
        ){
            stepBackgroundLinksTracked(activelySimulatedSectorIds, sectorPowerEfficiency, frozenSectorIds, dtSeconds);
        }

        /**
         * Variant of {@link #stepBackgroundLinks} returning true if any background item moved or any
         * displayed port throughput rate changed (allows callers to throttle UI refreshes & disk saves).
         */
        public boolean stepBackgroundLinksTracked(
            Set<Integer> activelySimulatedSectorIds,
            java.util.Map<Integer, Float> sectorPowerEfficiency,
            Set<Integer> frozenSectorIds,
            float dtSeconds
        ){
            if(dtSeconds <= 0f || ports.isEmpty()) return false;
            boolean anyChanged = false;

            for(SectorConveyorLink link : linksBySender.values()){
                boolean senderSimulated = activelySimulatedSectorIds != null && activelySimulatedSectorIds.contains(link.senderSectorId);
                if(!senderSimulated){
                    boolean senderFrozen = frozenSectorIds != null && frozenSectorIds.contains(link.senderSectorId);
                    boolean receiverFrozen = frozenSectorIds != null && frozenSectorIds.contains(link.receiverSectorId);
                    SectorPortRecord sender = ports.get(link.senderKey());
                    float prevRate = sender != null ? sender.recentTotalRate : 0f;
                    boolean prevPaused = sender != null && sender.backpressurePaused;
                    float eff = 1f;
                    if(senderFrozen || receiverFrozen){
                        eff = 0f;
                    }else if(sender != null && sender.tier == TIER_SENIOR && sectorPowerEfficiency != null){
                        Float p = sectorPowerEfficiency.get(link.senderSectorId);
                        if(p != null){
                            eff = Math.max(0f, Math.min(1f, p));
                        }
                    }
                    int moved = stepSenderTransfer(link.senderSectorId, link.senderPortNumber, dtSeconds, eff);
                    if(moved > 0){
                        anyChanged = true;
                    }else if(sender != null && (sender.backpressurePaused != prevPaused || Math.abs(sender.recentTotalRate - prevRate) > 0.001f)){
                        anyChanged = true;
                    }
                }
            }

            for(SectorPortRecord p : ports.values()){
                boolean simulated = activelySimulatedSectorIds != null && activelySimulatedSectorIds.contains(p.sectorId);
                if(!simulated){
                    if(!p.isSender){
                        if(p.advanceRateWindowTracked(dtSeconds)){
                            anyChanged = true;
                        }
                    }else if(!linksBySender.containsKey(packPortKey(p.sectorId, true, p.portNumber))){
                        if(p.advanceRateWindowTracked(dtSeconds)){
                            anyChanged = true;
                        }
                    }
                }
            }
            return anyChanged;
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

                // STREAM_VERSION >= 2: observed background feed pattern
                out.writeInt(p.backgroundFeedPattern.size());
                for(int itemId : p.backgroundFeedPattern){
                    out.writeInt(itemId);
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
            if(ver < 1 || ver > STREAM_VERSION) return;

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
                if(ver >= 2){
                    int feedCount = Math.max(0, in.readInt());
                    List<Integer> feed = new ArrayList<>(feedCount);
                    for(int j = 0; j < feedCount; j++){
                        feed.add(in.readInt());
                    }
                    if(rec != null){
                        rec.replaceFeedPattern(feed);
                    }
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
