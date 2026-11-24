package mindustry.game.strategy.core;

import java.util.*;

/**
 * Fixed-point steady-state flow solver for a planet's multi-sector production
 * and logistics network. Handles arbitrary multi-hop chains (A -> B -> C) and
 * cyclic dependencies (A -> B -> A) while respecting power, local input supply,
 * core buffer availability, and storage backpressure.
 */
public class PlanetNetworkSolver{
    public int maxGlobalIterations = 12;
    public int maxLocalIterations = 8;
    public float convergenceEpsilon = 1e-4f;

    private final LogisticsPolicy policy;

    // V3.2: Reusable scratch buffers to drastically cut minor GC pressure on 272-sector planets (single-threaded solver)
    private final HashMap<Integer, ArrayList<LogisticsRoute>> scratchOutgoingBySector = new HashMap<>();
    private final HashMap<Integer, HashMap<String, Float>> scratchInstantImports = new HashMap<>();
    private final HashMap<Integer, HashMap<String, Float>> scratchNextInstantImports = new HashMap<>();
    private final HashMap<Integer, HashMap<String, Float>> scratchLocalNetWithoutImports = new HashMap<>();
    private final IdentityHashMap<LogisticsRoute, HashMap<String, Float>> scratchPrevRouteItemFlows = new IdentityHashMap<>();
    private final HashSet<String> scratchCandidateSet = new HashSet<>();
    private final ArrayList<String> scratchCandidateItems = new ArrayList<>();
    private final IdentityHashMap<LogisticsRoute, Float> scratchRouteTentativeSums = new IdentityHashMap<>();

    private final HashMap<String, Float> scratchGrossProd = new HashMap<>();
    private final HashMap<String, Float> scratchPotentialProd = new HashMap<>();
    private final HashMap<String, Float> scratchTotalDemand = new HashMap<>();
    private final HashMap<String, Float> scratchItemSat = new HashMap<>();
    private float[] scratchNodePowerFactor = new float[16];
    private float[] scratchNodeInputFactor = new float[16];
    private float[] scratchNodeBackpressureFactor = new float[16];

    public PlanetNetworkSolver(LogisticsPolicy policy){
        this.policy = policy;
    }

    private void ensureLocalNodeScratchCapacity(int nodeCount){
        if(scratchNodePowerFactor.length < nodeCount){
            int cap = Math.max(nodeCount, scratchNodePowerFactor.length * 2);
            scratchNodePowerFactor = new float[cap];
            scratchNodeInputFactor = new float[cap];
            scratchNodeBackpressureFactor = new float[cap];
        }
    }

    private static HashMap<String, Float> getOrClearFloatMap(HashMap<Integer, HashMap<String, Float>> table, int key){
        HashMap<String, Float> map = table.get(key);
        if(map == null){
            map = new HashMap<>();
            table.put(key, map);
        }else{
            map.clear();
        }
        return map;
    }

    /**
     * Solves instantaneous steady-state production, consumption, power satisfaction,
     * and cross-sector export/import rates for all sectors on a planet.
     *
     * @param sectors            all sector profiles keyed by sectorId
     * @param routes             all active cross-sector logistics routes
     * @param stepSeconds        simulation step window (used to bound buffer draw rates)
     */
    public void solve(Map<Integer, SectorStrategyProfile> sectors, List<LogisticsRoute> routes, float stepSeconds){
        float dt = Math.max(0.05f, stepSeconds);

        // Initialize runtime maps
        for(SectorStrategyProfile s : sectors.values()){
            s.solvedNetRates.clear();
            s.solvedGrossProduction.clear();
            s.solvedExportRates.clear();
            s.solvedImportRates.clear();
            s.bottleneckTag = "";
            s.solvedPowerSatisfaction = 1f;
            s.solvedLocalPowerProd = 0f;
            s.solvedLocalPowerCons = 0f;
            for(ProductionNode node : s.nodes){
                node.solvedEfficiency = node.measuredEfficiency;
            }
        }
        for(LogisticsRoute r : routes){
            r.lastFlowPerSec = 0f;
            r.lastItemFlows.clear();
        }

        // Group outgoing routes by source sector (reusing bucket lists)
        for(ArrayList<LogisticsRoute> list : scratchOutgoingBySector.values()){
            list.clear();
        }
        for(LogisticsRoute r : routes){
            if(r.enabled && sectors.containsKey(r.fromSectorId) && sectors.containsKey(r.toSectorId)){
                scratchOutgoingBySector.computeIfAbsent(r.fromSectorId, k -> new ArrayList<>()).add(r);
            }
        }

        // Reuse per-sector maps across ticks; swap instantImports <-> nextInstantImports each iteration
        HashMap<Integer, HashMap<String, Float>> instantImports = scratchInstantImports;
        HashMap<Integer, HashMap<String, Float>> nextInstantImports = scratchNextInstantImports;
        HashMap<Integer, HashMap<String, Float>> localNetWithoutImports = scratchLocalNetWithoutImports;
        for(int id : sectors.keySet()){
            getOrClearFloatMap(instantImports, id);
            getOrClearFloatMap(nextInstantImports, id);
            getOrClearFloatMap(localNetWithoutImports, id);
        }

        // Track previous per-(route, item) flows so wildcard "*" routes converge properly
        scratchPrevRouteItemFlows.clear();
        boolean converged = false;

        for(int gIter = 0; gIter < maxGlobalIterations; gIter++){
            float maxDelta = 0f;

            // 1. Solve local steady state for each sector given current incoming imports
            for(SectorStrategyProfile sector : sectors.values()){
                if(!sector.hasBase || (policy.freezeUnderAttack() && sector.underAttack)){
                    clearInactiveSector(sector);
                    localNetWithoutImports.get(sector.sectorId).clear();
                    continue;
                }
                // Foreground sector runs natively at 60 TPS; its exports/imports are bridged externally
                if(sector.foregroundActive){
                    localNetWithoutImports.get(sector.sectorId).clear();
                    continue;
                }

                HashMap<String, Float> incoming = instantImports.get(sector.sectorId);
                HashMap<String, Float> localOnlyNet = localNetWithoutImports.get(sector.sectorId);
                solveLocalSector(sector, incoming, localOnlyNet, dt);
            }

            // 2. Solve cross-sector route flows based on updated local surplus and downstream demand
            for(int id : sectors.keySet()){
                nextInstantImports.get(id).clear();
            }

            for(LogisticsRoute r : routes){
                r.lastFlowPerSec = 0f;
                r.lastItemFlows.clear();
            }

            for(SectorStrategyProfile src : sectors.values()){
                src.solvedExportRates.clear();
                if(!src.hasBase || (policy.freezeUnderAttack() && src.underAttack)){
                    continue;
                }

                ArrayList<LogisticsRoute> outRoutes = scratchOutgoingBySector.get(src.sectorId);
                if(outRoutes == null || outRoutes.isEmpty()) continue;

                // Collect all candidate items that can leave src in deterministic sorted order
                scratchCandidateSet.clear();
                if(src.foregroundActive){
                    // Foreground sector never duplicates from core storage; only route measured physical exports
                    scratchCandidateSet.addAll(src.foregroundExportRates.keySet());
                }else{
                    scratchCandidateSet.addAll(src.solvedGrossProduction.keySet());
                    scratchCandidateSet.addAll(src.storedItems.keySet());
                    scratchCandidateSet.addAll(instantImports.get(src.sectorId).keySet());
                    for(LogisticsRoute r : outRoutes){
                        if(!"*".equals(r.itemName)) scratchCandidateSet.add(r.itemName);
                    }
                }
                if(scratchCandidateSet.isEmpty()) continue;

                scratchCandidateItems.clear();
                scratchCandidateItems.addAll(scratchCandidateSet);
                Collections.sort(scratchCandidateItems);
                ArrayList<String> candidateItems = scratchCandidateItems;

                float sharedCap = src.exportCapacities.getOrDefault("*", Float.POSITIVE_INFINITY);
                if(src.foregroundActive){
                    // Foreground measured exports already reflect real LaunchPad throughput
                    sharedCap = Float.POSITIVE_INFINITY;
                }

                // Pass A: compute tentative per-item allocations ignoring shared launch pad contention
                ArrayList<String> activeItems = new ArrayList<>();
                ArrayList<ArrayList<LogisticsRoute>> activeMatching = new ArrayList<>();
                ArrayList<float[]> activeDemands = new ArrayList<>();
                ArrayList<float[]> tentativeAllocs = new ArrayList<>();
                float[] tentativeTotals = new float[candidateItems.size()];
                float grandTentativeTotal = 0f;

                for(String item : candidateItems){
                    ArrayList<LogisticsRoute> matching = new ArrayList<>();
                    for(LogisticsRoute r : outRoutes){
                        SectorStrategyProfile dst = sectors.get(r.toSectorId);
                        if(dst == null || !dst.hasBase || (policy.freezeUnderAttack() && dst.underAttack)) continue;
                        if(r.acceptsItem(item)) matching.add(r);
                    }
                    if(matching.isEmpty()) continue;

                    // Compute exportable rate for this item from src without double-drawing from buffer
                    float availableExport;
                    if(src.foregroundActive){
                        availableExport = Math.max(0f, src.foregroundExportRates.getOrDefault(item, 0f));
                    }else{
                        float netWithImports = src.solvedNetRates.getOrDefault(item, 0f);
                        float bufferRate = src.getStored(item) / dt;
                        float itemPadCap = src.exportCapacities.getOrDefault(item, Float.POSITIVE_INFINITY);
                        // netWithImports may be negative when local factories consume from core buffer;
                        // add first so local consumption and export cannot double-extract the same buffer.
                        float supplyCap = Math.max(0f, netWithImports + bufferRate);
                        availableExport = Math.max(0f, Math.min(itemPadCap, supplyCap));
                    }

                    if(availableExport <= 1e-5f) continue;

                    // Compute downstream demand for each route
                    float[] demands = new float[matching.size()];
                    boolean anyDemand = false;
                    for(int i = 0; i < matching.size(); i++){
                        LogisticsRoute r = matching.get(i);
                        SectorStrategyProfile dst = sectors.get(r.toSectorId);
                        float explicitDemand = dst.importDemands.getOrDefault(item, 0f);
                        float demand;
                        if(dst.foregroundActive && dst.requireExplicitImportDemand){
                            // Foreground v8 LandingPads deliver onto belts, not directly to core;
                            // only pull what active LandingPads explicitly request.
                            demand = Math.min(r.maxItemsPerSec, explicitDemand);
                        }else if(dst.requireExplicitImportDemand){
                            float roomRate = Math.max(0f, (dst.storageCapacity - dst.getStored(item)) / dt);
                            float dstLocalNet = localNetWithoutImports.get(dst.sectorId).getOrDefault(item, 0f);
                            float consumptionDeficit = Math.max(0f, -dstLocalNet);
                            demand = Math.min(explicitDemand, roomRate + consumptionDeficit);
                        }else{
                            float roomRate = Math.max(0f, (dst.storageCapacity - dst.getStored(item)) / dt);
                            float dstLocalNet = localNetWithoutImports.get(dst.sectorId).getOrDefault(item, 0f);
                            float consumptionDeficit = Math.max(0f, -dstLocalNet);
                            demand = explicitDemand > 0f
                                ? Math.min(explicitDemand, roomRate + consumptionDeficit)
                                : Math.min(r.maxItemsPerSec, roomRate + consumptionDeficit);
                        }
                        demands[i] = Math.max(0f, demand);
                        if(demands[i] > 1e-5f) anyDemand = true;
                    }
                    if(!anyDemand) continue;

                    float[] alloc = policy.allocateSupply(item, availableExport, matching, demands);
                    float itemTotal = 0f;
                    for(float f : alloc) itemTotal += f;
                    if(itemTotal <= 1e-5f) continue;

                    int idx = activeItems.size();
                    activeItems.add(item);
                    activeMatching.add(matching);
                    activeDemands.add(demands);
                    tentativeAllocs.add(alloc);
                    tentativeTotals[idx] = itemTotal;
                    grandTentativeTotal += itemTotal;
                }

                // Pass B: fairly scale across items if shared launch pad bandwidth is saturated
                boolean scaleShared = Float.isFinite(sharedCap) && grandTentativeTotal > sharedCap && grandTentativeTotal > 1e-5f;
                float sharedScale = scaleShared ? (sharedCap / grandTentativeTotal) : 1f;

                // Track per-route tentative totals so wildcard routes respect r.maxItemsPerSec across all items
                scratchRouteTentativeSums.clear();
                IdentityHashMap<LogisticsRoute, Float> routeTentativeSums = scratchRouteTentativeSums;
                ArrayList<float[]> postSharedAllocs = new ArrayList<>(activeItems.size());

                for(int idx = 0; idx < activeItems.size(); idx++){
                    String item = activeItems.get(idx);
                    ArrayList<LogisticsRoute> matching = activeMatching.get(idx);
                    float[] alloc;
                    if(scaleShared){
                        float itemBudget = tentativeTotals[idx] * sharedScale;
                        alloc = policy.allocateSupply(item, itemBudget, matching, activeDemands.get(idx));
                    }else{
                        alloc = tentativeAllocs.get(idx);
                    }
                    postSharedAllocs.add(alloc);
                    for(int i = 0; i < matching.size(); i++){
                        LogisticsRoute r = matching.get(i);
                        if(alloc[i] > 1e-5f){
                            routeTentativeSums.put(r, routeTentativeSums.getOrDefault(r, 0f) + alloc[i]);
                        }
                    }
                }

                // Commit final flows, enforcing per-route total maxItemsPerSec for wildcard routes
                for(int idx = 0; idx < activeItems.size(); idx++){
                    String item = activeItems.get(idx);
                    ArrayList<LogisticsRoute> matching = activeMatching.get(idx);
                    float[] alloc = postSharedAllocs.get(idx);
                    float totalExported = 0f;

                    for(int i = 0; i < matching.size(); i++){
                        LogisticsRoute r = matching.get(i);
                        float flow = alloc[i];
                        if("*".equals(r.itemName)){
                            float rSum = routeTentativeSums.getOrDefault(r, 0f);
                            if(rSum > r.maxItemsPerSec && rSum > 1e-5f){
                                flow *= (r.maxItemsPerSec / rSum);
                            }
                        }

                        if(flow > 1e-5f){
                            r.lastItemFlows.put(item, flow);
                            r.lastFlowPerSec += flow;
                            totalExported += flow;

                            // Instant routes feed directly into downstream steady-state during the same tick
                            if(r.transitSeconds <= 1e-4f){
                                HashMap<String, Float> dstIn = nextInstantImports.get(r.toSectorId);
                                dstIn.put(item, dstIn.getOrDefault(item, 0f) + flow);
                            }
                        }
                    }

                    if(totalExported > 1e-5f){
                        src.solvedExportRates.put(item, src.solvedExportRates.getOrDefault(item, 0f) + totalExported);
                    }
                }
            }

            // Compare per-(route, item) flows with previous iteration for accurate convergence
            for(LogisticsRoute r : routes){
                HashMap<String, Float> prevMap = scratchPrevRouteItemFlows.get(r);
                for(Map.Entry<String, Float> e : r.lastItemFlows.entrySet()){
                    float prev = prevMap != null ? prevMap.getOrDefault(e.getKey(), 0f) : 0f;
                    maxDelta = Math.max(maxDelta, Math.abs(e.getValue() - prev));
                }
                if(prevMap != null){
                    for(Map.Entry<String, Float> e : prevMap.entrySet()){
                        if(!r.lastItemFlows.containsKey(e.getKey())){
                            maxDelta = Math.max(maxDelta, Math.abs(e.getValue()));
                        }
                    }
                    prevMap.clear();
                    prevMap.putAll(r.lastItemFlows);
                }else{
                    scratchPrevRouteItemFlows.put(r, new HashMap<>(r.lastItemFlows));
                }
            }

            // Update solvedImportRates and check convergence
            for(SectorStrategyProfile s : sectors.values()){
                HashMap<String, Float> nextIn = nextInstantImports.get(s.sectorId);
                HashMap<String, Float> prevIn = instantImports.get(s.sectorId);
                for(Map.Entry<String, Float> e : nextIn.entrySet()){
                    maxDelta = Math.max(maxDelta, Math.abs(e.getValue() - prevIn.getOrDefault(e.getKey(), 0f)));
                }
                for(Map.Entry<String, Float> e : prevIn.entrySet()){
                    if(!nextIn.containsKey(e.getKey())){
                        maxDelta = Math.max(maxDelta, Math.abs(e.getValue()));
                    }
                }
                s.solvedImportRates.clear();
                if(!nextIn.isEmpty()){
                    s.solvedImportRates.putAll(nextIn);
                }
            }

            // Swap double-buffered import maps with zero allocation
            HashMap<Integer, HashMap<String, Float>> swap = instantImports;
            instantImports = nextInstantImports;
            nextInstantImports = swap;

            if(maxDelta < convergenceEpsilon){
                converged = true;
                break;
            }
        }

        // Finalize net rates after exports and instant imports.
        // When the global fixed-point loop converged, each sector's solvedNetRates/solvedGrossProduction
        // were already computed against the exact converged incoming imports & export rates.
        for(SectorStrategyProfile s : sectors.values()){
            if(!s.hasBase || s.foregroundActive || (policy.freezeUnderAttack() && s.underAttack)) continue;
            if(!converged){
                HashMap<String, Float> incoming = instantImports.get(s.sectorId);
                HashMap<String, Float> localOnlyNet = localNetWithoutImports.get(s.sectorId);
                solveLocalSector(s, incoming, localOnlyNet, dt);
            }
            for(Map.Entry<String, Float> e : s.solvedExportRates.entrySet()){
                s.solvedNetRates.put(e.getKey(), s.solvedNetRates.getOrDefault(e.getKey(), 0f) - e.getValue());
            }
        }
    }

    private void clearInactiveSector(SectorStrategyProfile s){
        s.solvedPowerSatisfaction = 0f;
        s.solvedLocalPowerProd = 0f;
        s.solvedLocalPowerCons = 0f;
        s.solvedNetRates.clear();
        s.solvedGrossProduction.clear();
        s.solvedExportRates.clear();
        s.solvedImportRates.clear();
        s.bottleneckTag = s.underAttack ? "under-attack" : "no-base";
        for(ProductionNode n : s.nodes){
            n.solvedEfficiency = 0f;
        }
    }

    private void solveLocalSector(
        SectorStrategyProfile sector,
        Map<String, Float> incomingImports,
        Map<String, Float> outLocalNetWithoutImports,
        float dt
    ){
        int nodeCount = sector.nodes.size();
        ensureLocalNodeScratchCapacity(nodeCount);
        float[] nodePowerFactor = scratchNodePowerFactor;
        float[] nodeInputFactor = scratchNodeInputFactor;
        float[] nodeBackpressureFactor = scratchNodeBackpressureFactor;
        Arrays.fill(nodePowerFactor, 0, nodeCount, 1f);
        Arrays.fill(nodeInputFactor, 0, nodeCount, 1f);
        Arrays.fill(nodeBackpressureFactor, 0, nodeCount, 1f);

        HashMap<String, Float> grossProd = scratchGrossProd;
        HashMap<String, Float> potentialProd = scratchPotentialProd;
        HashMap<String, Float> totalDemand = scratchTotalDemand;
        HashMap<String, Float> itemSat = scratchItemSat;
        grossProd.clear();
        potentialProd.clear();
        totalDemand.clear();
        itemSat.clear();

        for(int iter = 0; iter < maxLocalIterations; iter++){
            grossProd.clear();
            potentialProd.clear();
            totalDemand.clear();
            float powerProd = 0f;
            float powerCons = 0f;

            // Gather current gross production, unthrottled potential production, non-self power/item demand
            for(int i = 0; i < nodeCount; i++){
                ProductionNode node = sector.nodes.get(i);
                float eff = node.solvedEfficiency;
                float preBpEff = Math.max(0f, Math.min(1f, node.measuredEfficiency * nodePowerFactor[i] * nodeInputFactor[i]));
                // Non-power throttling (missing inputs or full outputs) reduces requested power without
                // feeding powerSat back into powerCons (which would oscillate under brownout).
                float nonPowerThrottle = nodeInputFactor[i] * nodeBackpressureFactor[i];

                powerProd += node.powerProducePerSec * eff;
                powerCons += node.powerConsumePerSec * node.measuredEfficiency * nonPowerThrottle;

                for(Map.Entry<String, Float> out : node.outputsPerSec.entrySet()){
                    String outItem = out.getKey();
                    grossProd.put(outItem, grossProd.getOrDefault(outItem, 0f) + out.getValue() * eff);
                    potentialProd.put(outItem, potentialProd.getOrDefault(outItem, 0f) + out.getValue() * preBpEff);
                }

                // Demand for each input item is throttled by power, backpressure, and OTHER inputs
                // (never by this item's own itemSat, preventing fixed-point oscillation).
                for(Map.Entry<String, Float> in : node.inputsPerSec.entrySet()){
                    String inItem = in.getKey();
                    float otherInputSat = 1f;
                    if(iter > 0 && node.inputsPerSec.size() > 1){
                        for(String other : node.inputsPerSec.keySet()){
                            if(!other.equals(inItem)){
                                otherInputSat = Math.min(otherInputSat, itemSat.getOrDefault(other, 1f));
                            }
                        }
                    }
                    float demScale = node.measuredEfficiency * nodePowerFactor[i] * nodeBackpressureFactor[i] * otherInputSat;
                    totalDemand.put(inItem, totalDemand.getOrDefault(inItem, 0f) + in.getValue() * demScale);
                }
            }

            float localPowerSat = policy.computePowerSatisfaction(powerProd, powerCons);
            float powerSat = sector.externalPowerSatisfaction >= 0f
                ? Math.max(0f, Math.min(1f, sector.externalPowerSatisfaction))
                : localPowerSat;
            sector.solvedPowerSatisfaction = powerSat;
            sector.solvedLocalPowerProd = powerProd;
            sector.solvedLocalPowerCons = powerCons;

            // Compute item satisfaction ratios (supply includes local prod + route imports + external imports + buffer)
            itemSat.clear();
            String worstMissingItem = null;
            float worstSat = 1f;

            for(Map.Entry<String, Float> dem : totalDemand.entrySet()){
                String item = dem.getKey();
                float needed = dem.getValue();
                if(needed <= 1e-5f){
                    itemSat.put(item, 1f);
                    continue;
                }
                float supply = grossProd.getOrDefault(item, 0f)
                    + incomingImports.getOrDefault(item, 0f)
                    + sector.externalImportRates.getOrDefault(item, 0f)
                    + (sector.getStored(item) / dt);
                float sat = Math.min(1f, supply / needed);
                itemSat.put(item, sat);
                if(sat < worstSat){
                    worstSat = sat;
                    worstMissingItem = item;
                }
            }

            // Update each node's component factors and solvedEfficiency
            float maxNodeDelta = 0f;
            for(int i = 0; i < nodeCount; i++){
                ProductionNode node = sector.nodes.get(i);
                float pFactor = node.powerConsumePerSec > 1e-5f ? powerSat : 1f;

                // Limiting reagent: minimum satisfaction across all required input items
                float inFactor = 1f;
                for(String inItem : node.inputsPerSec.keySet()){
                    inFactor = Math.min(inFactor, itemSat.getOrDefault(inItem, 1f));
                }

                // Storage backpressure: if all outputs are full, throttle proportionally to downstream
                // consumption + export against total unthrottled potential production.
                float bpFactor = 1f;
                if(policy.enableStorageBackpressure() && node.stopWhenOutputsFull && !node.outputsPerSec.isEmpty()){
                    boolean allBlocked = true;
                    float maxNeededRatio = 0f;
                    float preBpEff = node.measuredEfficiency * pFactor * inFactor;
                    for(Map.Entry<String, Float> out : node.outputsPerSec.entrySet()){
                        String outItem = out.getKey();
                        if(!sector.isItemFull(outItem)){
                            allBlocked = false;
                            break;
                        }
                        float outFlow = totalDemand.getOrDefault(outItem, 0f)
                            + sector.solvedExportRates.getOrDefault(outItem, 0f)
                            + sector.externalExportDemands.getOrDefault(outItem, 0f);
                        float pot = Math.max(out.getValue() * preBpEff, potentialProd.getOrDefault(outItem, 0f));
                        if(outFlow > 1e-5f && pot > 1e-5f){
                            maxNeededRatio = Math.max(maxNeededRatio, Math.min(1f, outFlow / pot));
                        }
                    }
                    if(allBlocked){
                        bpFactor = maxNeededRatio;
                    }
                }

                nodePowerFactor[i] = pFactor;
                nodeInputFactor[i] = inFactor;
                nodeBackpressureFactor[i] = bpFactor;

                float clamped = Math.max(0f, Math.min(1f, node.measuredEfficiency * pFactor * inFactor * bpFactor));
                maxNodeDelta = Math.max(maxNodeDelta, Math.abs(clamped - node.solvedEfficiency));
                node.solvedEfficiency = clamped;
            }

            if(powerSat < 0.99f){
                sector.bottleneckTag = "low-power";
            }else if(worstMissingItem != null && worstSat < 0.99f){
                sector.bottleneckTag = "missing:" + worstMissingItem;
            }else{
                sector.bottleneckTag = "";
            }

            if(maxNodeDelta < convergenceEpsilon){
                break;
            }
        }

        // Compute final gross production and net rates (both with and without incoming imports)
        sector.solvedGrossProduction.clear();
        sector.solvedNetRates.clear();
        outLocalNetWithoutImports.clear();

        for(Map.Entry<String, Float> in : incomingImports.entrySet()){
            sector.solvedNetRates.put(in.getKey(), sector.solvedNetRates.getOrDefault(in.getKey(), 0f) + in.getValue());
        }
        for(Map.Entry<String, Float> extIn : sector.externalImportRates.entrySet()){
            if(extIn.getValue() > 1e-5f){
                sector.solvedNetRates.put(extIn.getKey(), sector.solvedNetRates.getOrDefault(extIn.getKey(), 0f) + extIn.getValue());
            }
        }

        for(ProductionNode node : sector.nodes){
            float eff = node.solvedEfficiency;
            for(Map.Entry<String, Float> out : node.outputsPerSec.entrySet()){
                float rate = out.getValue() * eff;
                sector.solvedGrossProduction.put(out.getKey(), sector.solvedGrossProduction.getOrDefault(out.getKey(), 0f) + rate);
                sector.solvedNetRates.put(out.getKey(), sector.solvedNetRates.getOrDefault(out.getKey(), 0f) + rate);
                outLocalNetWithoutImports.put(out.getKey(), outLocalNetWithoutImports.getOrDefault(out.getKey(), 0f) + rate);
            }
            for(Map.Entry<String, Float> in : node.inputsPerSec.entrySet()){
                float rate = in.getValue() * eff;
                sector.solvedNetRates.put(in.getKey(), sector.solvedNetRates.getOrDefault(in.getKey(), 0f) - rate);
                outLocalNetWithoutImports.put(in.getKey(), outLocalNetWithoutImports.getOrDefault(in.getKey(), 0f) - rate);
            }
        }
    }
}
