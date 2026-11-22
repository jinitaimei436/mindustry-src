package mindustry.ai.formation;

/**
 * Pure-Java math and tactical cohesion rules for V2.1 persistent unit formations.
 * Handles slowest-member speed synchronization, elastic lead/lag pacing,
 * concentric slot layout, and cross-domain terrain-blocked cooperative waiting.
 */
public final class FormationMath{
    public enum CohesionAction{
        /** March toward the shared formation destination at synchronized speed. */
        MARCH,
        /** Hold position right beside blocked or trailing teammates (still able to fire). */
        HOLD,
        /** Turn back and regroup right next to a terrain-blocked teammate before holding. */
        REGROUP_TO_BLOCKED
    }

    private FormationMath(){
    }

    /**
     * Computes the formation's synchronized cruise speed: the minimum positive
     * speed among all living members (tiles/tick in Mindustry units).
     */
    public static float computeSlowestSpeed(float[] memberBaseSpeeds){
        if(memberBaseSpeeds == null || memberBaseSpeeds.length == 0) return 0f;
        float min = Float.MAX_VALUE;
        for(float s : memberBaseSpeeds){
            if(s > 0.0001f && s < min){
                min = s;
            }
        }
        return min == Float.MAX_VALUE ? 0f : min;
    }

    /**
     * Computes a compact cohesion radius (world units) scaled by unit count and hitSizes.
     * Kept tight enough that mixed branches visibly stay side-by-side without cramming.
     */
    public static float computeCohesionRadius(float[] hitSizes){
        if(hitSizes == null || hitSizes.length == 0) return 32f;
        float sumHit = 0f;
        float maxHit = 8f;
        for(float h : hitSizes){
            float clamped = Math.max(6f, h);
            sumHit += clamped;
            if(clamped > maxHit) maxHit = clamped;
        }
        float avgHit = sumHit / hitSizes.length;
        float radius = (float)(maxHit * 1.1f + Math.sqrt(hitSizes.length) * avgHit * 0.95f + 12f);
        return Math.max(28f, Math.min(128f, radius));
    }

    /**
     * Generates deterministic concentric-ring local offsets (x0, y0, x1, y1, ...)
     * so mixed-domain members can gather neatly around a shared anchor or blocked teammate.
     */
    public static void computeSlotOffsets(float[] hitSizes, float[] outOffsets){
        if(hitSizes == null || outOffsets == null || hitSizes.length == 0) return;
        int n = hitSizes.length;
        if(outOffsets.length < n * 2) return;

        if(n == 1){
            outOffsets[0] = 0f;
            outOffsets[1] = 0f;
            return;
        }

        float sumHit = 0f;
        for(float h : hitSizes){
            sumHit += Math.max(8f, h);
        }
        float spacing = Math.max(14f, (sumHit / n) * 1.35f);

        int placed = 0;
        int ring = 1;
        while(placed < n){
            float radius = ring * spacing;
            int capacity = Math.max(6, (int)Math.floor((2.0 * Math.PI * radius) / spacing));
            int countInRing = Math.min(capacity, n - placed);
            double angleOffset = (ring % 2 == 0) ? (Math.PI / countInRing) : 0.0;
            for(int i = 0; i < countInRing; i++){
                double angle = angleOffset + (2.0 * Math.PI * i) / countInRing;
                outOffsets[placed * 2] = (float)(Math.cos(angle) * radius);
                outOffsets[placed * 2 + 1] = (float)(Math.sin(angle) * radius);
                placed++;
            }
            ring++;
        }
    }

    /**
     * Computes synchronized marching speed for one member.
     * - Normal aligned units move at {@code slowestSpeed} (fast air slows down to match slow ground/navy).
     * - Units that get ahead of the trailing/slowest anchor along the march axis smoothly brake down to 0
     *   so the formation never stretches apart.
     * - Units that fall slightly behind the trailing anchor get a gentle catch-up boost up to 1.30x slowestSpeed
     *   (never exceeding their own base speed).
     */
    public static float computeMarchSpeed(float memberBaseSpeed, float slowestSpeed, float leadAlongMarch, float cohesionRadius){
        if(memberBaseSpeed <= 0f || slowestSpeed <= 0f) return 0f;
        float baseCruise = Math.min(memberBaseSpeed, slowestSpeed);
        float radius = Math.max(24f, cohesionRadius);

        float comfortLead = radius * 0.65f;
        float maxLead = radius * 1.35f;

        if(leadAlongMarch > comfortLead){
            float t = (leadAlongMarch - comfortLead) / Math.max(1f, maxLead - comfortLead);
            if(t >= 1f) return 0f;
            return baseCruise * (1f - t);
        }

        if(leadAlongMarch < -radius * 0.45f){
            float lagRatio = Math.min(1f, (-leadAlongMarch - radius * 0.45f) / radius);
            float catchup = slowestSpeed * (1f + 0.30f * lagRatio);
            return Math.min(memberBaseSpeed, catchup);
        }

        return baseCruise;
    }

    /**
     * Evaluates cross-domain terrain-blocked coordination for a member:
     * When any teammate is terrain-blocked (e.g., Navy hits shoreline on a land target,
     * or Army hits deep water on a sea target, or Tank hits a cliff), all non-blocked
     * teammates must wait right next to the blocked member (holding if within waitRadius,
     * or regrouping back toward the blocked anchor if they ran ahead).
     */
    public static CohesionAction evaluateCohesionAction(
        boolean anyMemberBlocked,
        boolean thisMemberBlocked,
        float distToBlockedAnchor,
        float cohesionRadius
    ){
        if(!anyMemberBlocked){
            return CohesionAction.MARCH;
        }
        if(thisMemberBlocked){
            return CohesionAction.HOLD;
        }
        float waitRadius = Math.max(28f, cohesionRadius * 1.15f);
        if(distToBlockedAnchor <= waitRadius){
            return CohesionAction.HOLD;
        }
        return CohesionAction.REGROUP_TO_BLOCKED;
    }

    /**
     * Returns terrain-restriction priority used to pick the safest initial rally point
     * when creating a mixed formation (most terrain-restricted unit wins so air/ground
     * gather toward water's edge rather than pulling ships onto land).
     */
    public static int movementDomainPriority(boolean naval, boolean flying, boolean allowLegStep){
        if(naval) return 4;
        if(!flying && !allowLegStep) return 3; // standard ground
        if(!flying) return 2;                  // legged / crawler-like cross-wall
        return 1;                              // flying
    }
}
