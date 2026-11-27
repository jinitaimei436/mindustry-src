package mindustry.ai.formation;

import arc.*;
import arc.math.*;
import arc.math.geom.*;
import arc.struct.*;
import arc.util.*;
import mindustry.ai.formation.FormationMath.*;
import mindustry.ai.formation.TacticalFormation.*;
import mindustry.ai.types.*;
import mindustry.core.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.io.SaveFileReader.*;
import mindustry.net.*;

import java.io.*;

import static mindustry.Vars.*;

/**
 * Central manager for V2.1 persistent tactical unit formations within the active sector/map.
 *
 * Responsibilities:
 * - Create, disband, and remove individual members from persistent formations (SP + MP).
 * - Synchronize formation speed to the slowest living member.
 * - Detect cross-domain terrain blockage (e.g., Navy stopped at shore on a land target,
 *   Army stopped at water on a sea target, Tank stopped at a wall) and hold/regroup all
 *   other members right beside the blocked teammate(s).
 * - Persist formations safely inside saves via {@link SaveVersion#addCustomChunk}.
 */
public class FormationManager implements CustomChunk{
    private static final FormationManager instance = new FormationManager();
    private static final String CHUNK_NAME = "v21-tactical-formations";
    private static final int CHUNK_VERSION = 1;

    /** Ticks of sustained path blockage before triggering whole-formation cooperative wait. */
    private static final float BLOCKED_TRIGGER_TICKS = 18f;
    /** Ticks of zero progress toward an impassable destination before marking blocked. */
    private static final float STAGNATION_TRIGGER_TICKS = 42f;

    private final IntMap<TacticalFormation> formations = new IntMap<>();
    private final IntMap<TacticalFormation> unitToFormation = new IntMap<>();
    private final Seq<Unit> tmpMembers = new Seq<>(false);
    private final IntSeq tmpDeadIds = new IntSeq();
    private final IntSeq tmpDissolveIds = new IntSeq();

    private int nextFormationId = 1;
    private int uiRevision = 1;
    private boolean initialized = false;
    private boolean chunkRegistered = false;
    private boolean netRegistered = false;

    public static FormationManager get(){
        return instance;
    }

    private FormationManager(){
    }

    public void init(){
        if(!chunkRegistered){
            chunkRegistered = true;
            SaveVersion.addCustomChunk(CHUNK_NAME, this);
        }
        if(!netRegistered && net != null){
            netRegistered = true;
            net.handleServer(FormationPacket.class, this::handleServerPacket);
            net.handleClient(FormationPacket.class, this::handleClientPacket);
        }
        initialized = true;
    }

    public int getUiRevision(){
        return uiRevision;
    }

    private void bumpUiRevision(){
        uiRevision++;
    }

    public void clear(){
        formations.clear();
        unitToFormation.clear();
        nextFormationId = 1;
        bumpUiRevision();
    }

    public @Nullable TacticalFormation getFormation(int formationId){
        return formations.get(formationId);
    }

    public @Nullable TacticalFormation getFormationOf(@Nullable Unit unit){
        return unit == null ? null : unitToFormation.get(unit.id);
    }

    public @Nullable TacticalFormation getFormationOfUnitId(int unitId){
        return unitToFormation.get(unitId);
    }

    /** Collects all active formations belonging to the given team, sorted by formation ID. */
    public Seq<TacticalFormation> getTeamFormations(@Nullable Team team, Seq<TacticalFormation> out){
        out.clear();
        if(team == null) return out;
        for(TacticalFormation f : formations.values()){
            if(f.teamId == team.id && f.memberIds.size >= 2){
                out.add(f);
            }
        }
        out.sort((a, b) -> Integer.compare(a.id, b.id));
        return out;
    }

    /** Collects all living member Unit instances of a formation. */
    public Seq<Unit> getLivingMembers(@Nullable TacticalFormation formation, Seq<Unit> out){
        out.clear();
        if(formation == null || Groups.unit == null) return out;
        for(int i = 0; i < formation.memberIds.size; i++){
            Unit u = Groups.unit.getByID(formation.memberIds.items[i]);
            if(isValidMember(u, formation.teamId)){
                out.add(u);
            }
        }
        return out;
    }

    /** Returns a concise Chinese movement-domain tag for the command-panel member list. */
    public static String domainLabel(@Nullable Unit unit){
        if(unit == null || unit.type == null) return "未知";
        if(unit.type.naval) return "海军";
        if(unit.isFlying() || unit.type.flying) return "空军";
        if(unit.type.allowLegStep) return "蜘蛛/跨障";
        if(unit.type.hovering) return "悬浮";
        return "陆军";
    }

    // ==================== PLAYER REQUEST API ====================

    /** Creates a new persistent formation from the given units (requires >= 2 valid units). */
    public @Nullable TacticalFormation requestCreateFormation(Seq<Unit> units){
        if(units == null || units.size < 2 || player == null) return null;
        init();

        IntSeq ids = new IntSeq(units.size);
        for(int i = 0; i < units.size; i++){
            Unit u = units.get(i);
            if(isValidMember(u, player.team().id) && !ids.contains(u.id)){
                ids.add(u.id);
            }
        }
        if(ids.size < 2) return null;

        if(net != null && net.client()){
            sendClientOp(FormationPacket.OP_CREATE, out -> {
                out.writeInt(ids.size);
                for(int i = 0; i < ids.size; i++){
                    out.writeInt(ids.items[i]);
                }
            });
            return null;
        }

        TacticalFormation created = createFormationAuthoritative(player.team().id, ids, true);
        if(created != null && net != null && net.server()){
            broadcastFullSync();
        }
        return created;
    }

    /** Disbands an entire persistent formation so all members resume independent movement. */
    public boolean requestDisbandFormation(int formationId){
        init();
        TacticalFormation f = formations.get(formationId);
        if(f == null) return false;
        if(player != null && f.teamId != player.team().id) return false;

        if(net != null && net.client()){
            sendClientOp(FormationPacket.OP_DISBAND, out -> out.writeInt(formationId));
            return true;
        }

        boolean removed = disbandFormationAuthoritative(formationId);
        if(removed && net != null && net.server()){
            broadcastFullSync();
        }
        return removed;
    }

    /** Removes a single unit from its persistent formation (auto-disbands if < 2 remain). */
    public boolean requestRemoveMember(int formationId, int unitId){
        init();
        TacticalFormation f = formations.get(formationId);
        if(f == null || !f.containsUnit(unitId)) return false;
        if(player != null && f.teamId != player.team().id) return false;

        if(net != null && net.client()){
            sendClientOp(FormationPacket.OP_REMOVE_MEMBER, out -> {
                out.writeInt(formationId);
                out.writeInt(unitId);
            });
            return true;
        }

        boolean changed = removeMemberAuthoritative(formationId, unitId);
        if(changed && net != null && net.server()){
            broadcastFullSync();
        }
        return changed;
    }

    /**
     * Authoritatively creates a persistent formation from spawned/unloaded units
     * (used by the V3.9 Cross-Sector Troop Airlift System to restore formations on landing or pad unload).
     */
    public @Nullable TacticalFormation createFormationFromUnits(@Nullable Team team, @Nullable Seq<Unit> units, boolean rallyMembers){
        if(team == null || units == null || units.size < 2) return null;
        init();
        IntSeq ids = new IntSeq(units.size);
        for(int i = 0; i < units.size; i++){
            Unit u = units.get(i);
            if(isValidMember(u, team.id) && !ids.contains(u.id)){
                ids.add(u.id);
            }
        }
        if(ids.size < 2) return null;
        TacticalFormation created = createFormationAuthoritative(team.id, ids, rallyMembers);
        if(created != null && net != null && net.server()){
            broadcastFullSync();
        }
        return created;
    }

    // ==================== AUTHORITATIVE MUTATIONS ====================

    private @Nullable TacticalFormation createFormationAuthoritative(int teamId, IntSeq unitIds, boolean rallyMembers){
        if(unitIds == null || unitIds.size < 2) return null;

        IntSeq validIds = new IntSeq(unitIds.size);
        for(int i = 0; i < unitIds.size; i++){
            int id = unitIds.items[i];
            Unit u = Groups.unit.getByID(id);
            if(isValidMember(u, teamId) && !validIds.contains(id)){
                validIds.add(id);
            }
        }
        if(validIds.size < 2) return null;

        // Detach units from any prior formations first.
        for(int i = 0; i < validIds.size; i++){
            TacticalFormation old = unitToFormation.get(validIds.items[i]);
            if(old != null){
                removeMemberInternal(old, validIds.items[i]);
            }
        }

        int id = nextFormationId++;
        TacticalFormation formation = new TacticalFormation(id, teamId);
        for(int i = 0; i < validIds.size; i++){
            int uid = validIds.items[i];
            formation.memberIds.add(uid);
            unitToFormation.put(uid, formation);
            formation.stateFor(uid);
        }
        formations.put(id, formation);
        recomputeFormationState(formation, 0f);

        // Immediately rally newly grouped members toward the most terrain-restricted / slowest member
        // so scattered units gather together right away.
        if(rallyMembers){
            Unit anchor = pickInitialRallyAnchor(formation);
            if(anchor != null){
                formation.setSharedTarget(anchor.x, anchor.y);
                for(int i = 0; i < formation.memberIds.size; i++){
                    Unit u = Groups.unit.getByID(formation.memberIds.items[i]);
                    if(u != null && u.controller() instanceof CommandAI ai){
                        if(ai.command == null || ai.command.switchToMove){
                            ai.command(mindustry.ai.UnitCommand.moveCommand);
                        }
                        MemberState st = formation.stateFor(u.id);
                        ai.commandQueue.clear();
                        ai.commandPosition(Tmp.v1.set(anchor.x + st.slotOffsetX, anchor.y + st.slotOffsetY));
                        ai.group = null;
                    }
                }
            }
        }

        bumpUiRevision();
        return formation;
    }

    private boolean disbandFormationAuthoritative(int formationId){
        TacticalFormation f = formations.remove(formationId);
        if(f == null) return false;
        for(int i = 0; i < f.memberIds.size; i++){
            unitToFormation.remove(f.memberIds.items[i]);
        }
        f.memberIds.clear();
        f.states.clear();
        bumpUiRevision();
        return true;
    }

    private boolean removeMemberAuthoritative(int formationId, int unitId){
        TacticalFormation f = formations.get(formationId);
        if(f == null || !f.containsUnit(unitId)) return false;
        removeMemberInternal(f, unitId);
        bumpUiRevision();
        return true;
    }

    private void removeMemberInternal(TacticalFormation f, int unitId){
        f.memberIds.removeValue(unitId);
        f.states.remove(unitId);
        unitToFormation.remove(unitId);
        if(f.memberIds.size < 2){
            formations.remove(f.id);
            for(int i = 0; i < f.memberIds.size; i++){
                unitToFormation.remove(f.memberIds.items[i]);
            }
            f.memberIds.clear();
            f.states.clear();
        }
    }

    // ==================== COMMAND & AI INTEGRATION ====================

    /**
     * Called from {@link mindustry.input.InputHandler#commandUnits} to expand commanded unit IDs
     * so issuing a move/attack command to any formation member moves the entire formation together,
     * and updates each involved formation's shared target.
     */
    public int[] expandCommandedUnits(int teamId, int[] unitIds, @Nullable Position targetPos){
        if(unitIds == null || unitIds.length == 0 || formations.isEmpty()) return unitIds;

        IntSet expanded = new IntSet(unitIds.length * 2);
        ObjectSet<TacticalFormation> touched = new ObjectSet<>();

        for(int id : unitIds){
            expanded.add(id);
            TacticalFormation f = unitToFormation.get(id);
            if(f != null && f.teamId == teamId){
                touched.add(f);
                for(int i = 0; i < f.memberIds.size; i++){
                    int mid = f.memberIds.items[i];
                    Unit m = Groups.unit.getByID(mid);
                    if(isValidMember(m, teamId)){
                        expanded.add(mid);
                    }
                }
            }
        }

        if(targetPos != null && touched.size > 0){
            for(TacticalFormation f : touched){
                f.setSharedTarget(targetPos.getX(), targetPos.getY());
            }
        }

        if(expanded.size == unitIds.length){
            return unitIds;
        }

        int[] result = new int[expanded.size];
        int idx = 0;
        IntSet.IntSetIterator it = expanded.iterator();
        while(it.hasNext){
            result[idx++] = it.next();
        }
        return result;
    }

    /** Called when explicit stop/clearCommands is issued to a unit in a formation. */
    public void onUnitCommandsCleared(@Nullable Unit unit){
        if(unit == null) return;
        TacticalFormation f = unitToFormation.get(unit.id);
        if(f != null){
            f.clearSharedTarget();
        }
    }

    /**
     * Called by {@link CommandAI} after querying {@link ControlPathfinder} for a grounded/naval unit
     * so the formation learns immediately when a branch hits an impassable barrier or shoreline.
     */
    public void reportMemberPathStatus(Unit unit, boolean pathMove, boolean pathUnreachable, boolean navalEdgeBlocked){
        if(unit == null) return;
        TacticalFormation f = unitToFormation.get(unit.id);
        if(f == null || !f.hasTarget) return;

        MemberState st = f.stateFor(unit.id);
        float arriveDist = Math.max(f.cohesionRadius, unit.hitSize * 1.5f + tilesize * 2f);
        if(unit.within(f.sharedTarget, arriveDist)){
            st.pathBlockedNow = false;
            return;
        }
        st.pathBlockedNow = pathUnreachable || !pathMove || navalEdgeBlocked;
    }

    /** Resolves the synchronized movement speed for a unit inside CommandAI.prefSpeed(). */
    public float resolveSpeed(@Nullable Unit unit, float baseSpeed){
        if(unit == null || baseSpeed <= 0f) return baseSpeed;
        TacticalFormation f = unitToFormation.get(unit.id);
        if(f == null || f.memberIds.size < 2) return baseSpeed;
        MemberState st = f.states.get(unit.id);
        if(st == null) return baseSpeed;
        return Math.min(baseSpeed, st.allowedSpeed);
    }

    /** Returns true when this formation member must hold in place to stay beside a blocked teammate. */
    public boolean shouldHoldPosition(@Nullable Unit unit){
        if(unit == null) return false;
        TacticalFormation f = unitToFormation.get(unit.id);
        if(f == null || f.memberIds.size < 2) return false;
        MemberState st = f.states.get(unit.id);
        return st != null && st.action == CohesionAction.HOLD;
    }

    /**
     * If this member ran ahead of a terrain-blocked teammate, fills {@code out} with the
     * regroup point beside the blocked teammate and returns true.
     */
    public boolean getRegroupTarget(@Nullable Unit unit, Vec2 out){
        if(unit == null || out == null) return false;
        TacticalFormation f = unitToFormation.get(unit.id);
        if(f == null || f.memberIds.size < 2) return false;
        MemberState st = f.states.get(unit.id);
        if(st != null && st.action == CohesionAction.REGROUP_TO_BLOCKED){
            out.set(st.regroupTargetX, st.regroupTargetY);
            return true;
        }
        return false;
    }

    // ==================== PER-TICK SIMULATION UPDATE ====================

    /** Updates all active formations once per game tick right before unit AI updates. */
    public void update(){
        if(formations.isEmpty() || Groups.unit == null) return;

        float dt = Time.delta;
        boolean changedStructure = false;
        tmpDissolveIds.clear();

        for(TacticalFormation f : formations.values()){
            if(pruneDeadMembers(f)){
                changedStructure = true;
            }
            if(f.memberIds.size < 2){
                tmpDissolveIds.add(f.id);
                continue;
            }
            recomputeFormationState(f, dt);
        }

        if(tmpDissolveIds.size > 0){
            changedStructure = true;
            for(int i = 0; i < tmpDissolveIds.size; i++){
                disbandFormationAuthoritative(tmpDissolveIds.items[i]);
            }
        }

        if(changedStructure){
            bumpUiRevision();
        }
    }

    private boolean pruneDeadMembers(TacticalFormation f){
        tmpDeadIds.clear();
        for(int i = 0; i < f.memberIds.size; i++){
            int uid = f.memberIds.items[i];
            Unit u = Groups.unit.getByID(uid);
            if(!isValidMember(u, f.teamId)){
                tmpDeadIds.add(uid);
            }
        }
        if(tmpDeadIds.size == 0) return false;

        for(int i = 0; i < tmpDeadIds.size; i++){
            int uid = tmpDeadIds.items[i];
            f.memberIds.removeValue(uid);
            f.states.remove(uid);
            unitToFormation.remove(uid);
        }
        return true;
    }

    private void recomputeFormationState(TacticalFormation f, float dt){
        tmpMembers.clear();
        for(int i = 0; i < f.memberIds.size; i++){
            Unit u = Groups.unit.getByID(f.memberIds.items[i]);
            if(isValidMember(u, f.teamId)){
                tmpMembers.add(u);
            }
        }
        int n = tmpMembers.size;
        if(n < 2) return;

        float[] speeds = new float[n];
        float[] hitSizes = new float[n];
        float[] slotOffsets = new float[n * 2];

        float sumX = 0f, sumY = 0f;
        boolean anyActiveCommand = false;
        float fallbackTargetX = 0f, fallbackTargetY = 0f;

        for(int i = 0; i < n; i++){
            Unit u = tmpMembers.get(i);
            speeds[i] = Math.max(0.01f, u.speed());
            hitSizes[i] = u.hitSize;
            sumX += u.x;
            sumY += u.y;

            if(u.controller() instanceof CommandAI ai && ai.targetPos != null){
                anyActiveCommand = true;
                fallbackTargetX = ai.targetPos.x;
                fallbackTargetY = ai.targetPos.y;
            }
        }

        f.centerX = sumX / n;
        f.centerY = sumY / n;
        f.slowestSpeed = FormationMath.computeSlowestSpeed(speeds);
        f.cohesionRadius = FormationMath.computeCohesionRadius(hitSizes);
        FormationMath.computeSlotOffsets(hitSizes, slotOffsets);

        if(anyActiveCommand && !f.hasTarget){
            f.setSharedTarget(fallbackTargetX, fallbackTargetY);
        }else if(!anyActiveCommand && f.hasTarget){
            // Everyone finished or cleared orders.
            f.clearSharedTarget();
        }

        // Assign compact slot offsets.
        for(int i = 0; i < n; i++){
            MemberState st = f.stateFor(tmpMembers.get(i).id);
            st.slotOffsetX = slotOffsets[i * 2];
            st.slotOffsetY = slotOffsets[i * 2 + 1];
        }

        // Compute march direction toward sharedTarget (if marching).
        if(f.hasTarget){
            float dx = f.sharedTarget.x - f.centerX;
            float dy = f.sharedTarget.y - f.centerY;
            float len = Mathf.len(dx, dy);
            if(len > 4f){
                f.marchDirX = dx / len;
                f.marchDirY = dy / len;
                f.hasMarchDir = true;
            }else{
                f.hasMarchDir = false;
            }
        }else{
            f.hasMarchDir = false;
        }

        // Step 1: Detect terrain-blocked members (cross-domain barrier detection).
        int destTileX = f.hasTarget ? World.toTile(f.sharedTarget.x) : -1;
        int destTileY = f.hasTarget ? World.toTile(f.sharedTarget.y) : -1;

        int blockedCount = 0;
        float blockedSumX = 0f, blockedSumY = 0f;

        for(int i = 0; i < n; i++){
            Unit u = tmpMembers.get(i);
            MemberState st = f.stateFor(u.id);

            if(!f.hasTarget){
                st.resetBlocked();
                continue;
            }

            float distToTarget = u.dst(f.sharedTarget);
            float arriveThreshold = Math.max(f.cohesionRadius, u.hitSize * 1.5f + tilesize * 2f);

            if(distToTarget <= arriveThreshold){
                st.resetBlocked();
                continue;
            }

            if(u.isGrounded()){
                boolean destImpassable = world != null && world.tiles != null && u.isPathImpassable(destTileX, destTileY);

                // Check if unit's forward step toward target is immediately impassable (e.g., shoreline/wall edge).
                boolean edgeBlocked = false;
                if(destImpassable && world != null && world.tiles != null){
                    float ang = u.angleTo(f.sharedTarget);
                    float probeDist = Math.max(tilesize * 1.25f, u.hitSize * 0.7f);
                    int nextTx = World.toTile(u.x + Angles.trnsx(ang, probeDist));
                    int nextTy = World.toTile(u.y + Angles.trnsy(ang, probeDist));
                    if(!u.canPass(nextTx, nextTy)){
                        edgeBlocked = true;
                    }
                }

                if(st.pathBlockedNow || edgeBlocked){
                    st.blockedTimer += dt;
                    if(st.blockedTimer >= BLOCKED_TRIGGER_TICKS){
                        st.blocked = true;
                    }
                }else{
                    st.blockedTimer = Math.max(0f, st.blockedTimer - dt * 0.5f);
                }

                // Stagnation fallback: if destination is impassable and unit made no meaningful progress for ~0.7s.
                if(distToTarget + Math.max(2f, f.slowestSpeed * 4f) < st.lastDistToTarget){
                    st.lastDistToTarget = distToTarget;
                    st.progressTimer = 0f;
                    if(!st.pathBlockedNow && !edgeBlocked){
                        st.blocked = false;
                        st.blockedTimer = 0f;
                    }
                }else if(dt > 0f && destImpassable){
                    if(st.lastDistToTarget == Float.MAX_VALUE){
                        st.lastDistToTarget = distToTarget;
                    }
                    st.progressTimer += dt;
                    if(st.progressTimer >= STAGNATION_TRIGGER_TICKS){
                        st.blocked = true;
                    }
                }
            }else{
                // Flying units are never terrain-blocked themselves, but will wait for blocked teammates.
                st.blocked = false;
                st.blockedTimer = 0f;
            }

            if(st.blocked){
                blockedCount++;
                blockedSumX += u.x;
                blockedSumY += u.y;
            }
        }

        f.anyBlocked = blockedCount > 0;
        if(f.anyBlocked){
            f.blockedAnchorX = blockedSumX / blockedCount;
            f.blockedAnchorY = blockedSumY / blockedCount;
        }

        // Step 2: Find trailing (least-advanced) anchor along march direction so fast units wait for slow ones.
        float minProj = Float.MAX_VALUE;
        Unit trailUnit = tmpMembers.get(0);
        if(f.hasMarchDir){
            for(int i = 0; i < n; i++){
                Unit u = tmpMembers.get(i);
                float proj = (u.x - f.centerX) * f.marchDirX + (u.y - f.centerY) * f.marchDirY;
                if(proj < minProj){
                    minProj = proj;
                    trailUnit = u;
                }
            }
        }
        f.trailAnchorX = trailUnit.x;
        f.trailAnchorY = trailUnit.y;

        // Step 3: Compute per-member action & synchronized allowedSpeed.
        for(int i = 0; i < n; i++){
            Unit u = tmpMembers.get(i);
            MemberState st = f.stateFor(u.id);
            float baseSpeed = speeds[i];

            if(!f.hasTarget){
                st.action = CohesionAction.MARCH;
                st.allowedSpeed = Math.min(baseSpeed, f.slowestSpeed);
                continue;
            }

            if(f.anyBlocked){
                float distToBlocked = Mathf.dst(u.x, u.y, f.blockedAnchorX, f.blockedAnchorY);
                st.action = FormationMath.evaluateCohesionAction(true, st.blocked, distToBlocked, f.cohesionRadius);
                if(st.action == CohesionAction.HOLD){
                    st.allowedSpeed = 0f;
                }else{
                    // Regroup right next to the blocked teammate(s).
                    st.regroupTargetX = f.blockedAnchorX + st.slotOffsetX * 0.5f;
                    st.regroupTargetY = f.blockedAnchorY + st.slotOffsetY * 0.5f;
                    st.allowedSpeed = Math.min(baseSpeed, Math.max(f.slowestSpeed, baseSpeed * 0.65f));
                }
            }else{
                st.action = CohesionAction.MARCH;
                float lead = f.hasMarchDir
                    ? ((u.x - f.trailAnchorX) * f.marchDirX + (u.y - f.trailAnchorY) * f.marchDirY)
                    : 0f;
                st.allowedSpeed = FormationMath.computeMarchSpeed(baseSpeed, f.slowestSpeed, lead, f.cohesionRadius);
            }
        }
    }

    private @Nullable Unit pickInitialRallyAnchor(TacticalFormation f){
        Unit best = null;
        int bestPriority = -1;
        float bestSpeed = Float.MAX_VALUE;
        for(int i = 0; i < f.memberIds.size; i++){
            Unit u = Groups.unit.getByID(f.memberIds.items[i]);
            if(!isValidMember(u, f.teamId)) continue;
            int pri = FormationMath.movementDomainPriority(u.type.naval, u.isFlying() || u.type.flying, u.type.allowLegStep);
            float spd = u.speed();
            if(pri > bestPriority || (pri == bestPriority && spd < bestSpeed)){
                bestPriority = pri;
                bestSpeed = spd;
                best = u;
            }
        }
        return best;
    }

    private static boolean isValidMember(@Nullable Unit u, int teamId){
        return u != null && u.isValid() && !u.dead && u.team.id == teamId && u.allowCommand() && u.controller() instanceof CommandAI;
    }

    // ==================== SAVE CHUNK PERSISTENCE ====================

    @Override
    public boolean shouldWrite(){
        return !formations.isEmpty();
    }

    @Override
    public boolean writeNet(){
        return true;
    }

    @Override
    public void write(DataOutput stream) throws IOException{
        writeStateToStream(stream);
    }

    @Override
    public void read(DataInput stream) throws IOException{
        readStateFromStream(stream);
    }

    public void writeStateToStream(DataOutput out) throws IOException{
        out.writeInt(CHUNK_VERSION);
        out.writeInt(nextFormationId);

        Seq<TacticalFormation> valid = new Seq<>();
        for(TacticalFormation f : formations.values()){
            if(f.memberIds.size >= 2){
                valid.add(f);
            }
        }
        out.writeInt(valid.size);
        for(int i = 0; i < valid.size; i++){
            TacticalFormation f = valid.get(i);
            out.writeInt(f.id);
            out.writeInt(f.teamId);
            out.writeBoolean(f.hasTarget);
            if(f.hasTarget){
                out.writeFloat(f.sharedTarget.x);
                out.writeFloat(f.sharedTarget.y);
            }
            out.writeInt(f.memberIds.size);
            for(int m = 0; m < f.memberIds.size; m++){
                out.writeInt(f.memberIds.items[m]);
            }
        }
    }

    public void readStateFromStream(DataInput in) throws IOException{
        clear();
        int version = in.readInt();
        if(version != CHUNK_VERSION) return;

        nextFormationId = Math.max(1, in.readInt());
        int count = Math.max(0, in.readInt());
        for(int i = 0; i < count; i++){
            int id = in.readInt();
            int teamId = in.readInt();
            boolean hasTarget = in.readBoolean();
            float tx = 0f, ty = 0f;
            if(hasTarget){
                tx = in.readFloat();
                ty = in.readFloat();
            }
            int memberCount = Math.max(0, in.readInt());
            TacticalFormation f = new TacticalFormation(id, teamId);
            if(hasTarget){
                f.hasTarget = true;
                f.sharedTarget.set(tx, ty);
            }
            for(int m = 0; m < memberCount; m++){
                int uid = in.readInt();
                if(!f.memberIds.contains(uid)){
                    f.memberIds.add(uid);
                }
            }
            if(f.memberIds.size >= 2){
                formations.put(f.id, f);
                for(int m = 0; m < f.memberIds.size; m++){
                    int uid = f.memberIds.items[m];
                    unitToFormation.put(uid, f);
                    f.stateFor(uid);
                }
                if(f.id >= nextFormationId){
                    nextFormationId = f.id + 1;
                }
            }
        }
        bumpUiRevision();
    }

    // ==================== MULTIPLAYER PACKET HANDLING ====================

    private interface DataWriter{
        void write(DataOutputStream out) throws IOException;
    }

    private void sendClientOp(byte op, DataWriter writer){
        if(net == null) return;
        try{
            ByteArrayOutputStream baos = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(baos);
            writer.write(out);
            out.flush();
            net.send(new FormationPacket(op, baos.toByteArray()), true);
        }catch(IOException e){
            Log.err("Failed to send FormationPacket op=" + op, e);
        }
    }

    private void broadcastFullSync(){
        if(net == null || !net.server()) return;
        try{
            ByteArrayOutputStream baos = new ByteArrayOutputStream(128);
            DataOutputStream out = new DataOutputStream(baos);
            writeStateToStream(out);
            out.flush();
            FormationPacket packet = new FormationPacket(FormationPacket.OP_FULL_SYNC, baos.toByteArray());
            for(NetConnection con : net.getConnections()){
                if(con != null && con.isConnected() && con.isCoLocated()){
                    con.send(packet, true);
                }
            }
        }catch(IOException e){
            Log.err("Failed to broadcast formation sync", e);
        }
    }

    private void handleServerPacket(NetConnection con, FormationPacket packet){
        if(con == null || packet == null || con.player == null || !con.isCoLocated()) return;
        Player sender = con.player;
        int teamId = sender.team().id;

        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet.data))){
            boolean changed = false;
            switch(packet.op){
                case FormationPacket.OP_CREATE -> {
                    int count = Math.min(200, Math.max(0, in.readInt()));
                    IntSeq ids = new IntSeq(count);
                    for(int i = 0; i < count; i++){
                        ids.add(in.readInt());
                    }
                    changed = createFormationAuthoritative(teamId, ids, true) != null;
                }
                case FormationPacket.OP_DISBAND -> {
                    int fid = in.readInt();
                    TacticalFormation f = formations.get(fid);
                    if(f != null && f.teamId == teamId){
                        changed = disbandFormationAuthoritative(fid);
                    }
                }
                case FormationPacket.OP_REMOVE_MEMBER -> {
                    int fid = in.readInt();
                    int uid = in.readInt();
                    TacticalFormation f = formations.get(fid);
                    if(f != null && f.teamId == teamId){
                        changed = removeMemberAuthoritative(fid, uid);
                    }
                }
                default -> {}
            }
            if(changed){
                broadcastFullSync();
            }
        }catch(Throwable t){
            Log.err("Failed to handle server FormationPacket op=" + packet.op, t);
        }
    }

    private void handleClientPacket(FormationPacket packet){
        if(packet == null || packet.op != FormationPacket.OP_FULL_SYNC) return;
        if(net != null && net.isDetachedClient()) return;
        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(packet.data))){
            readStateFromStream(in);
        }catch(Throwable t){
            Log.err("Failed to apply client formation sync", t);
        }
    }
}
