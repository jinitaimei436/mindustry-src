package mindustry.game.strategy.core;

import java.util.*;

/**
 * Default logistics allocation policy:
 * 1. Serves higher-priority routes first.
 * 2. Within the same priority tier, splits remaining supply proportionally to demand.
 */
public class DefaultLogisticsPolicy implements LogisticsPolicy{

    @Override
    public float[] allocateSupply(String itemName, float availableSupply, List<LogisticsRoute> routes, float[] routeDemands){
        int n = routes.size();
        float[] allocated = new float[n];
        if(n == 0 || availableSupply <= 1e-5f) return allocated;

        // Group route indices by priority descending
        TreeMap<Integer, ArrayList<Integer>> tiers = new TreeMap<>(Collections.reverseOrder());
        for(int i = 0; i < n; i++){
            if(routeDemands[i] > 1e-5f && routes.get(i).maxItemsPerSec > 1e-5f){
                tiers.computeIfAbsent(routes.get(i).priority, k -> new ArrayList<>()).add(i);
            }
        }

        float remaining = availableSupply;
        for(ArrayList<Integer> tier : tiers.values()){
            if(remaining <= 1e-5f) break;

            float tierDemand = 0f;
            float[] capped = new float[tier.size()];
            for(int k = 0; k < tier.size(); k++){
                int idx = tier.get(k);
                capped[k] = Math.min(routeDemands[idx], routes.get(idx).maxItemsPerSec);
                tierDemand += capped[k];
            }

            if(tierDemand <= 1e-5f) continue;

            if(remaining >= tierDemand){
                for(int k = 0; k < tier.size(); k++){
                    allocated[tier.get(k)] = capped[k];
                }
                remaining -= tierDemand;
            }else{
                float ratio = remaining / tierDemand;
                for(int k = 0; k < tier.size(); k++){
                    allocated[tier.get(k)] = capped[k] * ratio;
                }
                remaining = 0f;
            }
        }

        return allocated;
    }
}
