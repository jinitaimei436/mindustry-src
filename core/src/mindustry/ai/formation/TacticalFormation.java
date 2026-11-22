package mindustry.ai.formation;

import arc.graphics.*;
import arc.math.geom.*;
import arc.struct.*;
import mindustry.ai.formation.FormationMath.*;
import mindustry.graphics.*;

/**
 * Persistent tactical unit formation in a single sector/map (V2.1).
 * Members remain bound together across movement/attack commands until explicitly
 * removed or the formation is disbanded (or fewer than 2 living members remain).
 */
public class TacticalFormation{
    private static final Color[] badgeColors = {
        Pal.accent,
        Color.valueOf("84f491"),
        Color.valueOf("73d1ff"),
        Color.valueOf("ffa665"),
        Color.valueOf("d99bff"),
        Color.valueOf("ffe869")
    };

    public final int id;
    public final int teamId;
    public final IntSeq memberIds = new IntSeq();

    // Runtime per-tick computed state (transient, recomputed by FormationManager.update)
    public float slowestSpeed = 0f;
    public float centerX = 0f, centerY = 0f;
    public float cohesionRadius = 40f;

    public boolean hasTarget = false;
    public final Vec2 sharedTarget = new Vec2();

    public boolean anyBlocked = false;
    public float blockedAnchorX = 0f, blockedAnchorY = 0f;

    public float trailAnchorX = 0f, trailAnchorY = 0f;
    public float marchDirX = 0f, marchDirY = 0f;
    public boolean hasMarchDir = false;

    public final IntMap<MemberState> states = new IntMap<>();

    public static class MemberState{
        public boolean pathBlockedNow = false;
        public boolean blocked = false;
        public float blockedTimer = 0f;
        public float lastDistToTarget = Float.MAX_VALUE;
        public float progressTimer = 0f;

        public float slotOffsetX = 0f;
        public float slotOffsetY = 0f;

        public CohesionAction action = CohesionAction.MARCH;
        public float allowedSpeed = 0f;
        public float regroupTargetX = 0f;
        public float regroupTargetY = 0f;

        public void resetBlocked(){
            pathBlockedNow = false;
            blocked = false;
            blockedTimer = 0f;
            lastDistToTarget = Float.MAX_VALUE;
            progressTimer = 0f;
        }
    }

    public TacticalFormation(int id, int teamId){
        this.id = id;
        this.teamId = teamId;
    }

    public boolean containsUnit(int unitId){
        return memberIds.contains(unitId);
    }

    public int size(){
        return memberIds.size;
    }

    public Color badgeColor(){
        return badgeColors[Math.abs(id - 1) % badgeColors.length];
    }

    public MemberState stateFor(int unitId){
        MemberState st = states.get(unitId);
        if(st == null){
            st = new MemberState();
            states.put(unitId, st);
        }
        return st;
    }

    public void setSharedTarget(float x, float y){
        boolean changed = !hasTarget || sharedTarget.dst2(x, y) > 16f;
        hasTarget = true;
        sharedTarget.set(x, y);
        if(changed){
            anyBlocked = false;
            for(MemberState st : states.values()){
                st.resetBlocked();
            }
        }
    }

    public void clearSharedTarget(){
        hasTarget = false;
        anyBlocked = false;
        hasMarchDir = false;
        for(MemberState st : states.values()){
            st.resetBlocked();
            st.action = CohesionAction.MARCH;
        }
    }
}
