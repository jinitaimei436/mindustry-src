package mindustry.game.strategy.core;

import java.util.*;

/**
 * Real-time event-driven and continuous-step simulation engine for a planet's
 * global logistics network and strategic troop dispatches.
 */
public class StrategySimulationEngine{
    public final HashMap<Integer, SectorStrategyProfile> sectors = new HashMap<>();
    public final ArrayList<LogisticsRoute> routes = new ArrayList<>();
    public final ArrayList<LogisticsRoute.InTransitShipment> inTransitShipments = new ArrayList<>();
    public final ArrayList<TroopDispatchOrder> activeDispatches = new ArrayList<>();
    public final ArrayList<TroopDispatchOrder> arrivedDispatches = new ArrayList<>();

    private final LogisticsPolicy policy;
    private final PlanetNetworkSolver solver;
    private StrategicAIController aiController;

    private double elapsedSeconds = 0.0;

    public StrategySimulationEngine(){
        this(new DefaultLogisticsPolicy());
    }

    public StrategySimulationEngine(LogisticsPolicy policy){
        this.policy = policy;
        this.solver = new PlanetNetworkSolver(policy);
    }

    public void setAIController(StrategicAIController aiController){
        this.aiController = aiController;
    }

    public double getElapsedSeconds(){
        return elapsedSeconds;
    }

    public void putSector(SectorStrategyProfile profile){
        sectors.put(profile.sectorId, profile);
    }

    public void addRoute(LogisticsRoute route){
        routes.add(route);
    }

    public void clearRoutesFrom(int fromSectorId){
        routes.removeIf(r -> r.fromSectorId == fromSectorId);
    }

    public void enqueueTroopDispatch(TroopDispatchOrder order){
        if(order != null && order.totalUnits() > 0){
            activeDispatches.add(order);
        }
    }

    /**
     * Advances the entire planet simulation by dtSeconds.
     * Automatically subdivides across storage full/empty inflection points or shipment
     * arrivals so state transitions remain accurate even for larger time steps.
     */
    public void step(float dtSeconds){
        float remaining = dtSeconds;
        int safetyGuard = 0;

        while(remaining > 1e-4f && safetyGuard++ < 32){
            // 1. Solve instantaneous rates
            solver.solve(sectors, routes, remaining);

            // 2. Find earliest inflection event within remaining window
            float subStep = Math.min(remaining, findNextInflectionTime(remaining));
            if(subStep < 0.05f){
                subStep = Math.min(remaining, 0.05f);
            }

            // 3. Emit delayed shipments at the start of subStep for routes with transitSeconds > 0
            for(LogisticsRoute r : routes){
                if(!r.enabled || r.transitSeconds <= 1e-4f || r.lastFlowPerSec <= 1e-5f) continue;
                if(!"*".equals(r.itemName)){
                    inTransitShipments.add(new LogisticsRoute.InTransitShipment(
                        r.fromSectorId, r.toSectorId, r.itemName, r.lastFlowPerSec * subStep, r.transitSeconds
                    ));
                }
            }

            // 4. Advance all in-transit shipments by subStep
            advanceShipments(subStep);

            // 5. Apply solved net rates to background sectors' storage buffers
            for(SectorStrategyProfile s : sectors.values()){
                if(!s.hasBase || s.foregroundActive || (policy.freezeUnderAttack() && s.underAttack)) continue;
                for(Map.Entry<String, Float> e : s.solvedNetRates.entrySet()){
                    float delta = e.getValue() * subStep;
                    if(Math.abs(delta) > 1e-6f){
                        s.addStored(e.getKey(), delta);
                    }
                }
            }

            // 6. Advance troop dispatches
            advanceTroopDispatches(subStep);

            // 7. Tick strategic AI hook if registered
            elapsedSeconds += subStep;
            if(aiController != null){
                ArrayList<TroopDispatchOrder> newOrders = new ArrayList<>();
                aiController.evaluate(elapsedSeconds, subStep, Collections.unmodifiableMap(sectors), newOrders);
                for(TroopDispatchOrder order : newOrders){
                    enqueueTroopDispatch(order);
                }
            }

            remaining -= subStep;
        }

        // Refresh readouts at the end of the step
        solver.solve(sectors, routes, 1f);
    }

    private float findNextInflectionTime(float maxStep){
        float earliest = maxStep;

        for(LogisticsRoute.InTransitShipment ship : inTransitShipments){
            if(ship.remainingSeconds > 1e-4f && ship.remainingSeconds < earliest){
                earliest = ship.remainingSeconds;
            }
        }

        for(TroopDispatchOrder order : activeDispatches){
            if(order.remainingSeconds > 1e-4f && order.remainingSeconds < earliest){
                earliest = order.remainingSeconds;
            }
        }

        for(SectorStrategyProfile s : sectors.values()){
            if(!s.hasBase || s.foregroundActive || (policy.freezeUnderAttack() && s.underAttack)) continue;
            for(Map.Entry<String, Float> e : s.solvedNetRates.entrySet()){
                float rate = e.getValue();
                float stored = s.getStored(e.getKey());
                if(rate < -1e-4f && stored > 1e-4f){
                    float tEmpty = stored / (-rate);
                    if(tEmpty < earliest) earliest = tEmpty;
                }else if(rate > 1e-4f && stored < s.storageCapacity - 1e-4f){
                    float tFull = (s.storageCapacity - stored) / rate;
                    if(tFull < earliest) earliest = tFull;
                }
            }
        }

        return earliest;
    }

    private void advanceShipments(float dt){
        Iterator<LogisticsRoute.InTransitShipment> it = inTransitShipments.iterator();
        while(it.hasNext()){
            LogisticsRoute.InTransitShipment ship = it.next();
            ship.remainingSeconds -= dt;
            if(ship.remainingSeconds <= 1e-4f){
                SectorStrategyProfile dst = sectors.get(ship.toSectorId);
                if(dst != null && dst.hasBase && !dst.foregroundActive){
                    dst.addStored(ship.itemName, ship.amount);
                }
                it.remove();
            }
        }
    }

    private void advanceTroopDispatches(float dt){
        Iterator<TroopDispatchOrder> it = activeDispatches.iterator();
        while(it.hasNext()){
            TroopDispatchOrder order = it.next();
            order.remainingSeconds -= dt;
            if(order.remainingSeconds <= 1e-4f){
                arrivedDispatches.add(order);
                it.remove();
            }
        }
    }
}
