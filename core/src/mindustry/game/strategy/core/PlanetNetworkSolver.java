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
    public int maxLocalIterations = 6;
    public float convergenceEpsilon = 1e-4f;

    private final LogisticsPolicy policy;

    public PlanetNetworkSolver(LogisticsPolicy policy){
        this.policy = policy;
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
        float dt = Math.max(0.1f, stepSeconds);

        // Initialize runtime maps
        for(SectorStrategyProfile s : sectors.values()){
            s.solvedNetRates.clear();
            s.solvedGrossProduction.clear();
            s.solvedExportRates.clear();
            s.solvedImportRates.clear();
            s.bottleneckTag = "";
            s.solvedPowerSatisfaction = 1f;
            for(ProductionNode node : s.nodes){
                node.solvedEfficiency = node.measuredEfficiency;
            }
        }
        for(LogisticsRoute r : routes){
            r.lastFlowPerSec = 0f;
        }

        // Group outgoing routes by source sector
        HashMap<Integer, ArrayList<LogisticsRoute>> outgoingBySector = new HashMap<>();
        for(LogisticsRoute r : routes){
            if(r.enabled && sectors.containsKey(r.fromSectorId) && sectors.containsKey(r.toSectorId)){
                outgoingBySector.computeIfAbsent(r.fromSectorId, k -> new ArrayList<>()).add(r);
            }
        }

        // Track solved incoming instantaneous stream per sector (routes with transitSeconds == 0)
        HashMap<Integer, HashMap<String, Float>> instantImports = new HashMap<>();
        for(int id : sectors.keySet()){
            instantImports.put(id, new HashMap<>());
        }

        for(int gIter = 0; gIter < maxGlobalIterations; gIter++){
            float maxDelta = 0f;

            // 1. Solve local steady state for each sector given current incoming imports
            for(SectorStrategyProfile sector : sectors.values()){
                if(!sector.hasBase || (policy.freezeUnderAttack() && sector.underAttack)){
                    clearInactiveSector(sector);
                    continue;
                }
                // Foreground sector runs natively at 60 TPS; its exports/imports are bridged externally
                if(sector.foregroundActive){
                    continue;
                }

                HashMap<String, Float> incoming = instantImports.get(sector.sectorId);
                solveLocalSector(sector, incoming, dt);
            }

            // 2. Solve cross-sector route flows based on updated local surplus and downstream demand
            HashMap<Integer, HashMap<String, Float>> nextInstantImports = new HashMap<>();
            for(int id : sectors.keySet()){
                nextInstantImports.put(id, new HashMap<>());
            }

            for(SectorStrategyProfile src : sectors.values()){
                src.solvedExportRates.clear();
                if(!src.hasBase || (policy.freezeUnderAttack() && src.underAttack)){
                    continue;
                }

                ArrayList<LogisticsRoute> outRoutes = outgoingBySector.get(src.sectorId);
                if(outRoutes == null || outRoutes.isEmpty()) continue;

                // Collect all candidate items that can leave src
                HashSet<String> candidateItems = new HashSet<>();
                candidateItems.addAll(src.solvedGrossProduction.keySet());
                candidateItems.addAll(src.storedItems.keySet());
                for(LogisticsRoute r : outRoutes){
                    if(!"*".equals(r.itemName)) candidateItems.add(r.itemName);
                }

                float sharedCap = src.exportCapacities.getOrDefault("*", Float.POSITIVE_INFINITY);
                float sharedUsed = 0f;

                for(String item : candidateItems){
                    ArrayList<LogisticsRoute> matching = new ArrayList<>();
                    for(LogisticsRoute r : outRoutes){
                        SectorStrategyProfile dst = sectors.get(r.toSectorId);
                        if(dst == null || !dst.hasBase || (policy.freezeUnderAttack() && dst.underAttack)) continue;
                        if(r.acceptsItem(item)) matching.add(r);
                    }
                    if(matching.isEmpty()) continue;

                    // Compute exportable rate for this item from src
                    float netLocal = src.solvedNetRates.getOrDefault(item, 0f);
                    float bufferRate = src.getStored(item) / dt;
                    float itemPadCap = src.exportCapacities.getOrDefault(item, Float.POSITIVE_INFINITY);
                    float remainingShared = Math.max(0f, sharedCap - sharedUsed);
                    float availableExport = Math.max(0f, Math.min(Math.min(itemPadCap, remainingShared), Math.max(0f, netLocal) + bufferRate));

                    if(availableExport <= 1e-5f) continue;

                    // Compute downstream demand for each route
                    float[] demands = new float[matching.size()];
                    for(int i = 0; i < matching.size(); i++){
                        LogisticsRoute r = matching.get(i);
                        SectorStrategyProfile dst = sectors.get(r.toSectorId);
                        float explicitDemand = dst.importDemands.getOrDefault(item, 0f);
                        float roomRate = Math.max(0f, (dst.storageCapacity - dst.getStored(item)) / dt);
                        float consumptionDeficit = Math.max(0f, -dst.solvedNetRates.getOrDefault(item, 0f));
                        float demand = explicitDemand > 0f
                            ? Math.min(explicitDemand, roomRate + consumptionDeficit)
                            : Math.min(r.maxItemsPerSec, roomRate + consumptionDeficit);
                        demands[i] = Math.max(0f, demand);
                    }

                    float[] alloc = policy.allocateSupply(item, availableExport, matching, demands);
                    float totalExported = 0f;
                    for(int i = 0; i < matching.size(); i++){
                        float flow = alloc[i];
                        LogisticsRoute r = matching.get(i);
                        float prev = r.lastFlowPerSec;
                        r.lastFlowPerSec = flow;
                        maxDelta = Math.max(maxDelta, Math.abs(flow - prev));
                        totalExported += flow;

                        // Instant routes feed directly into downstream steady-state during the same tick
                        if(r.transitSeconds <= 1e-4f && flow > 1e-5f){
                            HashMap<String, Float> dstIn = nextInstantImports.get(r.toSectorId);
                            dstIn.put(item, dstIn.getOrDefault(item, 0f) + flow);
                        }
                    }

                    if(totalExported > 1e-5f){
                        src.solvedExportRates.put(item, src.solvedExportRates.getOrDefault(item, 0f) + totalExported);
                        sharedUsed += totalExported;
                    }
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
                instantImports.put(s.sectorId, nextIn);
                s.solvedImportRates.clear();
                s.solvedImportRates.putAll(nextIn);
            }

            if(maxDelta < convergenceEpsilon){
                break;
            }
        }

        // Finalize net rates after exports and instant imports
        for(SectorStrategyProfile s : sectors.values()){
            if(!s.hasBase || s.foregroundActive || (policy.freezeUnderAttack() && s.underAttack)) continue;
            HashMap<String, Float> incoming = instantImports.get(s.sectorId);
            solveLocalSector(s, incoming, dt);
            for(Map.Entry<String, Float> e : s.solvedExportRates.entrySet()){
                s.solvedNetRates.put(e.getKey(), s.solvedNetRates.getOrDefault(e.getKey(), 0f) - e.getValue());
            }
        }
    }

    private void clearInactiveSector(SectorStrategyProfile s){
        s.solvedPowerSatisfaction = 0f;
        s.solvedNetRates.clear();
        s.solvedGrossProduction.clear();
        s.solvedExportRates.clear();
        s.solvedImportRates.clear();
        s.bottleneckTag = s.underAttack ? "under-attack" : "no-base";
        for(ProductionNode n : s.nodes){
            n.solvedEfficiency = 0f;
        }
    }

    private void solveLocalSector(SectorStrategyProfile sector, Map<String, Float> incomingImports, float dt){
        HashMap<String, Float> grossProd = new HashMap<>();
        HashMap<String, Float> totalDemand = new HashMap<>();

        for(int iter = 0; iter < maxLocalIterations; iter++){
            grossProd.clear();
            totalDemand.clear();
            float powerProd = 0f;
            float powerCons = 0f;

            // Gather current gross production, demand, and power using current node efficiencies
            for(ProductionNode node : sector.nodes){
                float eff = node.solvedEfficiency;
                powerProd += node.powerProducePerSec * eff;
                powerCons += node.powerConsumePerSec * node.measuredEfficiency;

                for(Map.Entry<String, Float> out : node.outputsPerSec.entrySet()){
                    grossProd.put(out.getKey(), grossProd.getOrDefault(out.getKey(), 0f) + out.getValue() * eff);
                }
                for(Map.Entry<String, Float> in : node.inputsPerSec.entrySet()){
                    totalDemand.put(in.getKey(), totalDemand.getOrDefault(in.getKey(), 0f) + in.getValue() * node.measuredEfficiency);
                }
            }

            float powerSat = policy.computePowerSatisfaction(powerProd, powerCons);
            sector.solvedPowerSatisfaction = powerSat;

            // Compute item satisfaction ratios (supply includes local prod + imports + buffer)
            HashMap<String, Float> itemSat = new HashMap<>();
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
                    + (sector.getStored(item) / dt);
                float sat = Math.min(1f, supply / needed);
                itemSat.put(item, sat);
                if(sat < worstSat){
                    worstSat = sat;
                    worstMissingItem = item;
                }
            }

            // Update each node's solvedEfficiency
            float maxNodeDelta = 0f;
            for(ProductionNode node : sector.nodes){
                float factor = node.measuredEfficiency;

                // Power constraint
                if(node.powerConsumePerSec > 1e-5f){
                    factor *= powerSat;
                }

                // Input item constraint (limiting reagent)
                for(String inItem : node.inputsPerSec.keySet()){
                    factor *= itemSat.getOrDefault(inItem, 1f);
                }

                // Storage backpressure: if all outputs are full and have no downstream demand/export, throttle
                if(policy.enableStorageBackpressure() && node.stopWhenOutputsFull && !node.outputsPerSec.isEmpty()){
                    boolean allBlocked = true;
                    float maxNeededRatio = 0f;
                    for(Map.Entry<String, Float> out : node.outputsPerSec.entrySet()){
                        String outItem = out.getKey();
                        if(!sector.isItemFull(outItem)){
                            allBlocked = false;
                            break;
                        }
                        float outFlow = totalDemand.getOrDefault(outItem, 0f)
                            + sector.solvedExportRates.getOrDefault(outItem, 0f);
                        if(outFlow > 1e-5f && out.getValue() > 1e-5f){
                            maxNeededRatio = Math.max(maxNeededRatio, Math.min(1f, outFlow / Math.max(out.getValue(), grossProd.getOrDefault(outItem, out.getValue()))));
                        }
                    }
                    if(allBlocked){
                        factor *= maxNeededRatio;
                    }
                }

                float clamped = Math.max(0f, Math.min(1f, factor));
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

        // Compute final gross production and net rates (before exports)
        sector.solvedGrossProduction.clear();
        sector.solvedNetRates.clear();

        for(Map.Entry<String, Float> in : incomingImports.entrySet()){
            sector.solvedNetRates.put(in.getKey(), sector.solvedNetRates.getOrDefault(in.getKey(), 0f) + in.getValue());
        }

        for(ProductionNode node : sector.nodes){
            float eff = node.solvedEfficiency;
            for(Map.Entry<String, Float> out : node.outputsPerSec.entrySet()){
                float rate = out.getValue() * eff;
                sector.solvedGrossProduction.put(out.getKey(), sector.solvedGrossProduction.getOrDefault(out.getKey(), 0f) + rate);
                sector.solvedNetRates.put(out.getKey(), sector.solvedNetRates.getOrDefault(out.getKey(), 0f) + rate);
            }
            for(Map.Entry<String, Float> in : node.inputsPerSec.entrySet()){
                float rate = in.getValue() * eff;
                sector.solvedNetRates.put(in.getKey(), sector.solvedNetRates.getOrDefault(in.getKey(), 0f) - rate);
            }
        }
    }
}
