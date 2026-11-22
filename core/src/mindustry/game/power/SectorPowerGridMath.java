package mindustry.game.power;

import java.io.*;
import java.util.*;

/**
 * Pure-Java mathematical and topological core for the V2.5 Cross-Sector Power Grid
 * ("跨区块电力系统"). Zero runtime dependency on Arc/Mindustry globals so it can be
 * exhaustively unit-tested on the JVM.
 *
 * Design guarantees:
 * - Only sectors with an active Surge Sector Power Hub participate in the inter-sector grid.
 * - Links are undirected edges strictly between adjacent sectors that both have active hubs.
 * - Connected components form distinct inter-sector grids ("区块间电网").
 * - Unlimited hub transfer capacity and zero transmission loss: all sectors in a connected
 *   component pool their production, consumption, and battery storage in real time.
 * - Destroying a hub or losing a sector automatically severs all links incident to that sector,
 *   cleanly partitioning any remaining neighbors into their valid sub-networks.
 * - Grid overload scales satisfaction proportionally (totalAvailable / totalNeeded), matching
 *   vanilla Mindustry PowerGraph brownout semantics.
 */
public final class SectorPowerGridMath{
    public static final int STREAM_VERSION = 1;

    private SectorPowerGridMath(){
    }

    /** Per-sector power hub telemetry snapshot (rates are power/tick; energy is raw battery units). */
    public static class SectorPowerNode{
        public final int sectorId;
        public boolean hasHub;
        public float produced;
        public float needed;
        public float batteryStored;
        public float batteryCapacity;
        public long lastUpdatedMs;

        public SectorPowerNode(int sectorId){
            this.sectorId = sectorId;
        }

        public void updateTelemetry(float produced, float needed, float batteryStored, float batteryCapacity, long nowMs){
            this.produced = Math.max(0f, produced);
            this.needed = Math.max(0f, needed);
            this.batteryCapacity = Math.max(0f, batteryCapacity);
            this.batteryStored = clamp(batteryStored, 0f, this.batteryCapacity);
            this.lastUpdatedMs = nowMs;
        }

        public void clearTelemetry(){
            this.produced = 0f;
            this.needed = 0f;
            this.batteryStored = 0f;
            this.batteryCapacity = 0f;
        }
    }

    /** Undirected link between two adjacent sector IDs (canonicalized with a < b). */
    public static class SectorLink{
        public final int a;
        public final int b;

        public SectorLink(int a, int b){
            this.a = Math.min(a, b);
            this.b = Math.max(a, b);
        }

        public long packedKey(){
            return packEdge(a, b);
        }

        @Override
        public boolean equals(Object o){
            if(this == o) return true;
            if(!(o instanceof SectorLink other)) return false;
            return a == other.a && b == other.b;
        }

        @Override
        public int hashCode(){
            return 31 * a + b;
        }
    }

    /** Aggregated telemetry for one connected inter-sector power grid component. */
    public static class GridAggregate{
        public final int[] sectorIds;
        public final float totalProduced;
        public final float totalNeeded;
        public final float totalBatteryStored;
        public final float totalBatteryCapacity;
        public final float netBalancePerTick;
        public final float satisfaction;
        public final boolean overloaded;

        public GridAggregate(
            int[] sectorIds,
            float totalProduced,
            float totalNeeded,
            float totalBatteryStored,
            float totalBatteryCapacity,
            float satisfaction
        ){
            this.sectorIds = sectorIds;
            this.totalProduced = totalProduced;
            this.totalNeeded = totalNeeded;
            this.totalBatteryStored = totalBatteryStored;
            this.totalBatteryCapacity = totalBatteryCapacity;
            this.netBalancePerTick = totalProduced - totalNeeded;
            this.satisfaction = satisfaction;
            this.overloaded = totalNeeded > 0.0001f && satisfaction < 0.999f;
        }

        public int sectorCount(){
            return sectorIds.length;
        }
    }

    /** Result of solving one tick for an active sector's hub-connected local PowerGraph. */
    public static class PowerStepResult{
        /** Final power coverage [0..1] to distribute to consumers in this local PowerGraph. */
        public final float coverage;
        /** Whether surplus power charged batteries this tick. */
        public final boolean charged;
        /** Signed battery energy delta to apply to THIS local graph's batteries (+charge / -discharge). */
        public final float localBatteryDelta;
        /** Signed battery energy delta applied to REMOTE sectors' shared batteries in this component. */
        public final float remoteBatteryDelta;
        /** Effective available power (produced + battery draw) across the full inter-sector grid for this tick. */
        public final float gridEffectiveAvailable;
        /** Total power needed across the full inter-sector grid for this tick (scaled by dt). */
        public final float gridTotalNeededTick;
        /** Net grid power balance per tick (totalProducedPerTick - totalNeededPerTick). */
        public final float gridNetBalancePerTick;
        /** Total produced per tick across the full inter-sector grid. */
        public final float gridTotalProducedPerTick;
        /** Total needed per tick across the full inter-sector grid. */
        public final float gridTotalNeededPerTick;
        /** Total battery stored across the full inter-sector grid after this tick. */
        public final float gridTotalStored;
        /** Total battery capacity across the full inter-sector grid. */
        public final float gridTotalCapacity;
        /** Number of sectors connected in this inter-sector grid component. */
        public final int connectedSectors;

        public PowerStepResult(
            float coverage,
            boolean charged,
            float localBatteryDelta,
            float remoteBatteryDelta,
            float gridEffectiveAvailable,
            float gridTotalNeededTick,
            float gridNetBalancePerTick,
            float gridTotalProducedPerTick,
            float gridTotalNeededPerTick,
            float gridTotalStored,
            float gridTotalCapacity,
            int connectedSectors
        ){
            this.coverage = coverage;
            this.charged = charged;
            this.localBatteryDelta = localBatteryDelta;
            this.remoteBatteryDelta = remoteBatteryDelta;
            this.gridEffectiveAvailable = gridEffectiveAvailable;
            this.gridTotalNeededTick = gridTotalNeededTick;
            this.gridNetBalancePerTick = gridNetBalancePerTick;
            this.gridTotalProducedPerTick = gridTotalProducedPerTick;
            this.gridTotalNeededPerTick = gridTotalNeededPerTick;
            this.gridTotalStored = gridTotalStored;
            this.gridTotalCapacity = gridTotalCapacity;
            this.connectedSectors = connectedSectors;
        }
    }

    public static long packEdge(int a, int b){
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return (((long)lo) << 32) | (hi & 0xffffffffL);
    }

    private static float clamp(float v, float min, float max){
        return Math.max(min, Math.min(max, v));
    }

    /**
     * Mutable planetary inter-sector power topology (one instance per Planet).
     */
    public static class PlanetPowerTopology{
        private final java.util.Map<Integer, SectorPowerNode> nodes = new HashMap<>();
        private final java.util.Map<Integer, Set<Integer>> adjacency = new HashMap<>();
        private final java.util.Map<Long, SectorLink> edges = new HashMap<>();

        public void clear(){
            nodes.clear();
            adjacency.clear();
            edges.clear();
        }

        public SectorPowerNode getOrCreateNode(int sectorId){
            return nodes.computeIfAbsent(sectorId, SectorPowerNode::new);
        }

        public SectorPowerNode getNode(int sectorId){
            return nodes.get(sectorId);
        }

        public boolean hasHub(int sectorId){
            SectorPowerNode n = nodes.get(sectorId);
            return n != null && n.hasHub;
        }

        /**
         * Updates whether a sector currently has an active Surge Sector Power Hub.
         * When set to {@code false} (hub destroyed/deconstructed or sector lost), all
         * incident cross-sector links are automatically severed and telemetry is cleared.
         *
         * @return true if hub status or topology changed.
         */
        public boolean setHubActive(int sectorId, boolean active){
            SectorPowerNode node = getOrCreateNode(sectorId);
            boolean changed = node.hasHub != active;
            node.hasHub = active;
            if(!active){
                node.clearTelemetry();
                if(removeAllLinksOf(sectorId)){
                    changed = true;
                }
            }
            return changed;
        }

        public void updateSectorTelemetry(int sectorId, float producedPerTick, float neededPerTick, float batteryStored, float batteryCapacity, long nowMs){
            SectorPowerNode node = getOrCreateNode(sectorId);
            node.hasHub = true;
            node.updateTelemetry(producedPerTick, neededPerTick, batteryStored, batteryCapacity, nowMs);
        }

        public boolean areLinked(int a, int b){
            if(a == b) return false;
            return edges.containsKey(packEdge(a, b));
        }

        /** Checks whether two sectors are eligible to form a cross-sector link. */
        public boolean canLink(int a, int b, boolean areAdjacent){
            return a != b && areAdjacent && hasHub(a) && hasHub(b);
        }

        /**
         * Toggles a cross-sector power link between two adjacent hub-equipped sectors.
         *
         * @return {@code +1} if linked, {@code -1} if unlinked, {@code 0} if invalid/not allowed.
         */
        public int toggleLink(int a, int b, boolean areAdjacent){
            if(a == b) return 0;
            if(areLinked(a, b)){
                removeLink(a, b);
                return -1;
            }
            if(!canLink(a, b, areAdjacent)){
                return 0;
            }
            addLinkInternal(a, b);
            return 1;
        }

        public boolean addLink(int a, int b, boolean areAdjacent){
            if(!canLink(a, b, areAdjacent) || areLinked(a, b)) return false;
            addLinkInternal(a, b);
            return true;
        }

        private void addLinkInternal(int a, int b){
            SectorLink link = new SectorLink(a, b);
            edges.put(link.packedKey(), link);
            adjacency.computeIfAbsent(a, k -> new HashSet<>()).add(b);
            adjacency.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        }

        public boolean removeLink(int a, int b){
            long key = packEdge(a, b);
            if(edges.remove(key) == null) return false;
            Set<Integer> na = adjacency.get(a);
            if(na != null) na.remove(b);
            Set<Integer> nb = adjacency.get(b);
            if(nb != null) nb.remove(a);
            return true;
        }

        public boolean removeAllLinksOf(int sectorId){
            Set<Integer> neighbors = adjacency.remove(sectorId);
            if(neighbors == null || neighbors.isEmpty()) return false;
            for(int other : neighbors){
                edges.remove(packEdge(sectorId, other));
                Set<Integer> otherAdj = adjacency.get(other);
                if(otherAdj != null){
                    otherAdj.remove(sectorId);
                }
            }
            return true;
        }

        public Collection<SectorLink> getAllLinks(){
            return edges.values();
        }

        public Set<Integer> getLinkedNeighbors(int sectorId){
            Set<Integer> set = adjacency.get(sectorId);
            return set == null ? Collections.emptySet() : Collections.unmodifiableSet(set);
        }

        public List<Integer> getHubSectorIds(){
            List<Integer> out = new ArrayList<>();
            for(SectorPowerNode n : nodes.values()){
                if(n.hasHub){
                    out.add(n.sectorId);
                }
            }
            Collections.sort(out);
            return out;
        }

        /** BFS connected component containing {@code rootSectorId} (only traverses active-hub nodes). */
        public int[] getConnectedComponent(int rootSectorId){
            if(!hasHub(rootSectorId)) return new int[0];

            Set<Integer> visited = new LinkedHashSet<>();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            visited.add(rootSectorId);
            queue.add(rootSectorId);

            while(!queue.isEmpty()){
                int cur = queue.poll();
                Set<Integer> neigh = adjacency.get(cur);
                if(neigh == null) continue;
                for(int nxt : neigh){
                    if(hasHub(nxt) && visited.add(nxt)){
                        queue.add(nxt);
                    }
                }
            }

            int[] result = new int[visited.size()];
            int idx = 0;
            for(int id : visited){
                result[idx++] = id;
            }
            Arrays.sort(result);
            return result;
        }

        /** Computes aggregated production, consumption, shared storage, and satisfaction for a sector's grid. */
        public GridAggregate computeAggregate(int sectorId){
            int[] comp = getConnectedComponent(sectorId);
            if(comp.length == 0){
                return new GridAggregate(comp, 0f, 0f, 0f, 0f, 0f);
            }

            float prod = 0f;
            float need = 0f;
            float stored = 0f;
            float cap = 0f;

            for(int id : comp){
                SectorPowerNode n = nodes.get(id);
                if(n == null || !n.hasHub) continue;
                prod += n.produced;
                need += n.needed;
                stored += n.batteryStored;
                cap += n.batteryCapacity;
            }

            float satisfaction;
            if(need <= 0.0001f){
                satisfaction = (prod > 0.0001f || stored > 0.0001f) ? 1f : 0f;
            }else if(prod >= need){
                satisfaction = 1f;
            }else{
                // Batteries can bridge deficit as long as shared storage has charge
                float available = prod + Math.min(stored, need - prod);
                satisfaction = clamp(available / need, 0f, 1f);
            }

            return new GridAggregate(comp, prod, need, stored, cap, satisfaction);
        }

        /**
         * Solves one simulation tick for an active sector whose hub-connected PowerGraph is running live.
         * Pools unlimited power and shared batteries across all sectors in the same connected component,
         * mutates remote sectors' battery storage proportionally, and returns exact local instructions.
         *
         * @param activeSectorId currently simulated sector ID
         * @param localProducedTick power produced by local graph this tick (already scaled by dt)
         * @param localNeededTick power needed by local graph this tick (already scaled by dt)
         * @param localStored current energy stored in local graph's batteries
         * @param localCapacity total battery capacity of local graph
         * @param deltaTicks frame delta in ticks ({@code Time.delta}, > 0)
         * @param nowMs current wall clock ms
         */
        public PowerStepResult resolveActiveSectorTick(
            int activeSectorId,
            float localProducedTick,
            float localNeededTick,
            float localStored,
            float localCapacity,
            float deltaTicks,
            long nowMs
        ){
            float dt = Math.max(0.0001f, deltaTicks);
            float localProdRate = Math.max(0f, localProducedTick / dt);
            float localNeedRate = Math.max(0f, localNeededTick / dt);
            float safeLocalCap = Math.max(0f, localCapacity);
            float safeLocalStored = clamp(localStored, 0f, safeLocalCap);

            updateSectorTelemetry(activeSectorId, localProdRate, localNeedRate, safeLocalStored, safeLocalCap, nowMs);

            int[] comp = getConnectedComponent(activeSectorId);
            float remoteProdTick = 0f;
            float remoteNeedTick = 0f;
            float remoteStored = 0f;
            float remoteCap = 0f;

            for(int id : comp){
                if(id == activeSectorId) continue;
                SectorPowerNode n = nodes.get(id);
                if(n == null || !n.hasHub) continue;
                remoteProdTick += n.produced * dt;
                remoteNeedTick += n.needed * dt;
                remoteStored += n.batteryStored;
                remoteCap += n.batteryCapacity;
            }

            float totalProdTick = Math.max(0f, localProducedTick) + remoteProdTick;
            float totalNeedTick = Math.max(0f, localNeededTick) + remoteNeedTick;
            float totalStoredBefore = safeLocalStored + remoteStored;
            float totalCap = safeLocalCap + remoteCap;

            boolean charged = false;
            float localBatteryDelta = 0f;
            float remoteBatteryDelta = 0f;
            float effectiveAvailable = totalProdTick;

            if(totalNeedTick > totalProdTick + 0.00001f){
                // Deficit: draw from combined local + remote battery pool with zero transfer limit
                float deficit = totalNeedTick - totalProdTick;
                if(totalStoredBefore > 0.00001f){
                    float drawTotal = Math.min(totalStoredBefore, deficit);
                    float localShare = safeLocalStored / totalStoredBefore;
                    float localDraw = Math.min(safeLocalStored, drawTotal * localShare);
                    float remoteDraw = Math.min(remoteStored, drawTotal - localDraw);
                    // Handle floating-point remainder cleanly
                    float rem = drawTotal - (localDraw + remoteDraw);
                    if(rem > 0.00001f){
                        if(safeLocalStored - localDraw >= rem){
                            localDraw += rem;
                        }else if(remoteStored - remoteDraw >= rem){
                            remoteDraw += rem;
                        }
                    }
                    localBatteryDelta = -localDraw;
                    remoteBatteryDelta = -remoteDraw;
                    effectiveAvailable += (localDraw + remoteDraw);
                }
            }else if(totalProdTick > totalNeedTick + 0.00001f){
                // Surplus: charge combined local + remote battery pool proportionally to free capacity
                charged = true;
                float excess = totalProdTick - totalNeedTick;
                float localFree = Math.max(0f, safeLocalCap - safeLocalStored);
                float remoteFree = Math.max(0f, remoteCap - remoteStored);
                float totalFree = localFree + remoteFree;
                if(totalFree > 0.00001f){
                    float chargeTotal = Math.min(totalFree, excess);
                    float localCharge = Math.min(localFree, chargeTotal * (localFree / totalFree));
                    float remoteCharge = Math.min(remoteFree, chargeTotal - localCharge);
                    localBatteryDelta = localCharge;
                    remoteBatteryDelta = remoteCharge;
                }
            }

            // Apply remote battery delta proportionally across remote sectors in this component
            if(Math.abs(remoteBatteryDelta) > 0.00001f){
                applyRemoteBatteryDelta(comp, activeSectorId, remoteBatteryDelta);
            }

            // Update active sector's stored snapshot to post-tick value
            float postLocalStored = clamp(safeLocalStored + localBatteryDelta, 0f, safeLocalCap);
            SectorPowerNode activeNode = nodes.get(activeSectorId);
            if(activeNode != null){
                activeNode.batteryStored = postLocalStored;
            }

            float postTotalStored = clamp(totalStoredBefore + localBatteryDelta + remoteBatteryDelta, 0f, totalCap);

            float coverage;
            if(totalNeedTick <= 0.00001f){
                coverage = (totalProdTick <= 0.00001f && !charged && postTotalStored <= 0.00001f) ? 0f : 1f;
            }else{
                coverage = clamp(effectiveAvailable / totalNeedTick, 0f, 1f);
            }

            float totalProdRate = totalProdTick / dt;
            float totalNeedRate = totalNeedTick / dt;

            return new PowerStepResult(
                coverage,
                charged,
                localBatteryDelta,
                remoteBatteryDelta,
                effectiveAvailable,
                totalNeedTick,
                totalProdRate - totalNeedRate,
                totalProdRate,
                totalNeedRate,
                postTotalStored,
                totalCap,
                comp.length
            );
        }

        private void applyRemoteBatteryDelta(int[] comp, int excludeSectorId, float delta){
            if(delta < 0f){
                float totalAvail = 0f;
                for(int id : comp){
                    if(id == excludeSectorId) continue;
                    SectorPowerNode n = nodes.get(id);
                    if(n != null && n.hasHub) totalAvail += n.batteryStored;
                }
                if(totalAvail <= 0.00001f) return;
                float ratio = Math.min(1f, (-delta) / totalAvail);
                for(int id : comp){
                    if(id == excludeSectorId) continue;
                    SectorPowerNode n = nodes.get(id);
                    if(n != null && n.hasHub && n.batteryStored > 0f){
                        n.batteryStored = Math.max(0f, n.batteryStored * (1f - ratio));
                    }
                }
            }else if(delta > 0f){
                float totalFree = 0f;
                for(int id : comp){
                    if(id == excludeSectorId) continue;
                    SectorPowerNode n = nodes.get(id);
                    if(n != null && n.hasHub){
                        totalFree += Math.max(0f, n.batteryCapacity - n.batteryStored);
                    }
                }
                if(totalFree <= 0.00001f) return;
                float ratio = Math.min(1f, delta / totalFree);
                for(int id : comp){
                    if(id == excludeSectorId) continue;
                    SectorPowerNode n = nodes.get(id);
                    if(n != null && n.hasHub && n.batteryCapacity > n.batteryStored){
                        float free = n.batteryCapacity - n.batteryStored;
                        n.batteryStored = Math.min(n.batteryCapacity, n.batteryStored + free * ratio);
                    }
                }
            }
        }

        /**
         * Steps shared battery charge/discharge for background-only components (components that do
         * NOT contain any currently simulated sector). Runs once per second in Universe.update().
         */
        public void stepBackgroundComponents(Set<Integer> activelySimulatedSectorIds, float dtSeconds){
            if(dtSeconds <= 0f || nodes.isEmpty()) return;
            float dtTicks = dtSeconds * 60f;
            Set<Integer> visited = new HashSet<>();

            for(SectorPowerNode node : nodes.values()){
                if(!node.hasHub || visited.contains(node.sectorId)) continue;
                int[] comp = getConnectedComponent(node.sectorId);
                boolean hasActiveSim = false;
                for(int id : comp){
                    visited.add(id);
                    if(activelySimulatedSectorIds != null && activelySimulatedSectorIds.contains(id)){
                        hasActiveSim = true;
                    }
                }
                if(hasActiveSim || comp.length == 0) continue;

                float netPerTick = 0f;
                for(int id : comp){
                    SectorPowerNode n = nodes.get(id);
                    if(n != null && n.hasHub){
                        netPerTick += (n.produced - n.needed);
                    }
                }
                float energyDelta = netPerTick * dtTicks;
                if(Math.abs(energyDelta) > 0.0001f){
                    applyRemoteBatteryDelta(comp, -1, energyDelta);
                }
            }
        }

        // ==================== BINARY SERIALIZATION ====================

        public void writeToStream(DataOutput out) throws IOException{
            out.writeInt(STREAM_VERSION);

            List<SectorPowerNode> activeNodes = new ArrayList<>();
            for(SectorPowerNode n : nodes.values()){
                if(n.hasHub){
                    activeNodes.add(n);
                }
            }
            activeNodes.sort(Comparator.comparingInt(n -> n.sectorId));

            out.writeInt(activeNodes.size());
            for(SectorPowerNode n : activeNodes){
                out.writeInt(n.sectorId);
                out.writeFloat(n.produced);
                out.writeFloat(n.needed);
                out.writeFloat(n.batteryStored);
                out.writeFloat(n.batteryCapacity);
            }

            List<SectorLink> sortedLinks = new ArrayList<>(edges.values());
            sortedLinks.sort((l1, l2) -> {
                int c = Integer.compare(l1.a, l2.a);
                return c != 0 ? c : Integer.compare(l1.b, l2.b);
            });
            out.writeInt(sortedLinks.size());
            for(SectorLink link : sortedLinks){
                out.writeInt(link.a);
                out.writeInt(link.b);
            }
        }

        public void readFromStream(DataInput in) throws IOException{
            clear();
            int version = in.readInt();
            if(version != STREAM_VERSION) return;

            int nodeCount = Math.max(0, in.readInt());
            for(int i = 0; i < nodeCount; i++){
                int sectorId = in.readInt();
                float prod = in.readFloat();
                float need = in.readFloat();
                float stored = in.readFloat();
                float cap = in.readFloat();
                updateSectorTelemetry(sectorId, prod, need, stored, cap, 0L);
            }

            int linkCount = Math.max(0, in.readInt());
            for(int i = 0; i < linkCount; i++){
                int a = in.readInt();
                int b = in.readInt();
                if(hasHub(a) && hasHub(b) && a != b){
                    addLinkInternal(a, b);
                }
            }
        }
    }

    // ==================== HUB ANIMATION STATE MACHINE ====================

    /** Lifecycle animation states for the Surge Sector Power Hub. */
    public enum HubAnimState{
        /** Standby / powered down. */
        OFFLINE,
        /** Playing startup sequence (0 -> 1). */
        STARTING,
        /** Steady-state operating loop. */
        RUNNING,
        /** Playing shutdown sequence (0 -> 1, warmup 1 -> 0). */
        STOPPING
    }

    /**
     * Pure-Java animation state machine for {@code SurgeSectorPowerHub}.
     * Drives startup, looping running, and shutdown animations smoothly,
     * including seamless mid-transition reversals without visual pops.
     */
    public static class HubAnimationController{
        public HubAnimState state = HubAnimState.OFFLINE;
        /** Elapsed ticks in the current state. */
        public float stateTimer = 0f;
        /** Normalized [0..1] progress within STARTING or STOPPING (0 in OFFLINE, 1 in RUNNING). */
        public float phaseProgress = 0f;
        /** Normalized [0..1] visual intensity: 0 in OFFLINE, 0->1 in STARTING, 1 in RUNNING, 1->0 in STOPPING. */
        public float warmup = 0f;
        /** Normalized [0..1) looping cycle progress while in RUNNING state. */
        public float runCycleProgress = 0f;
        /** Monotonically increasing animation clock (compatible with Mindustry DrawBlock.totalProgress). */
        public float totalProgress = 0f;

        /**
         * Advances the animation controller by {@code deltaTicks}.
         *
         * @return true if the {@link HubAnimState} transitioned during this step.
         */
        public boolean step(
            boolean shouldRun,
            float deltaTicks,
            float startupDuration,
            float runCycleDuration,
            float shutdownDuration
        ){
            float dt = Math.max(0f, deltaTicks);
            float startDur = Math.max(0.001f, startupDuration);
            float runDur = Math.max(0.001f, runCycleDuration);
            float stopDur = Math.max(0.001f, shutdownDuration);
            HubAnimState prev = state;

            switch(state){
                case OFFLINE -> {
                    warmup = 0f;
                    phaseProgress = 0f;
                    runCycleProgress = 0f;
                    if(shouldRun){
                        state = HubAnimState.STARTING;
                        stateTimer = 0f;
                        phaseProgress = 0f;
                    }
                }
                case STARTING -> {
                    if(!shouldRun){
                        // Smoothly reverse into STOPPING from current warmup level
                        state = HubAnimState.STOPPING;
                        phaseProgress = clamp(1f - warmup, 0f, 1f);
                        stateTimer = phaseProgress * stopDur;
                    }else{
                        stateTimer += dt;
                        totalProgress += dt * 0.5f;
                        phaseProgress = clamp(stateTimer / startDur, 0f, 1f);
                        warmup = phaseProgress;
                        if(stateTimer >= startDur){
                            state = HubAnimState.RUNNING;
                            stateTimer = 0f;
                            phaseProgress = 1f;
                            warmup = 1f;
                            runCycleProgress = 0f;
                        }
                    }
                }
                case RUNNING -> {
                    if(!shouldRun){
                        state = HubAnimState.STOPPING;
                        stateTimer = 0f;
                        phaseProgress = 0f;
                        warmup = 1f;
                    }else{
                        stateTimer += dt;
                        totalProgress += dt;
                        runCycleProgress = (stateTimer % runDur) / runDur;
                        phaseProgress = 1f;
                        warmup = 1f;
                    }
                }
                case STOPPING -> {
                    if(shouldRun){
                        // Smoothly reverse back into STARTING from current warmup level
                        state = HubAnimState.STARTING;
                        phaseProgress = clamp(warmup, 0f, 1f);
                        stateTimer = phaseProgress * startDur;
                    }else{
                        stateTimer += dt;
                        totalProgress += dt * Math.max(0.05f, warmup) * 0.5f;
                        phaseProgress = clamp(stateTimer / stopDur, 0f, 1f);
                        warmup = 1f - phaseProgress;
                        if(stateTimer >= stopDur){
                            state = HubAnimState.OFFLINE;
                            stateTimer = 0f;
                            phaseProgress = 0f;
                            warmup = 0f;
                            runCycleProgress = 0f;
                        }
                    }
                }
            }

            return state != prev;
        }

        public void resetOffline(){
            state = HubAnimState.OFFLINE;
            stateTimer = 0f;
            phaseProgress = 0f;
            warmup = 0f;
            runCycleProgress = 0f;
        }

        /** Maps normalized progress [0..1] to a valid frame index [0..frameCount-1], or -1 if empty. */
        public static int frameIndex(int frameCount, float progress01){
            if(frameCount <= 0) return -1;
            float p = clamp(progress01, 0f, 1f);
            int idx = (int)(p * frameCount);
            return Math.min(frameCount - 1, Math.max(0, idx));
        }

        /** Symmetric reverse frame mapping [frameCount-1..0] for reusing startup frames as shutdown animation. */
        public static int reverseFrameIndex(int frameCount, float progress01){
            if(frameCount <= 0) return -1;
            return (frameCount - 1) - frameIndex(frameCount, progress01);
        }
    }
}

