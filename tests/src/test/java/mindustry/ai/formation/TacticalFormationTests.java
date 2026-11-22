package mindustry.ai.formation;

import mindustry.ai.formation.FormationMath.*;
import mindustry.core.*;
import org.junit.jupiter.api.*;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-Java JUnit 5 unit tests for V2.1 persistent tactical unit formations:
 * slowest-member speed sync, elastic cohesion pacing, cross-domain terrain-blocked
 * waiting/regrouping, slot offset layout, and binary save-chunk serialization.
 */
public class TacticalFormationTests{

    @Test
    void versionForkTagIsV21(){
        assertEquals("v2.8.1", Version.forkVersion);
    }

    @Test
    void slowestSpeedSynchronizesFastAirToSlowestGroundOrNaval(){
        // e.g., Flare (3.0), Dagger (0.5), Risso (1.1), Reign (0.4)
        float slowest = FormationMath.computeSlowestSpeed(new float[]{3.0f, 0.5f, 1.1f, 0.4f});
        assertEquals(0.4f, slowest, 1e-5f);

        float cohesionRadius = FormationMath.computeCohesionRadius(new float[]{9f, 8f, 12f, 26f});
        assertTrue(cohesionRadius >= 28f && cohesionRadius <= 128f);

        // Aligned fast flyer must slow down to the slowest member's speed (0.4)
        float alignedFlyerSpeed = FormationMath.computeMarchSpeed(3.0f, slowest, 0f, cohesionRadius);
        assertEquals(0.4f, alignedFlyerSpeed, 1e-5f);

        // Fast flyer that drifts well ahead of the trailing anchor must brake smoothly to 0
        float aheadFlyerSpeed = FormationMath.computeMarchSpeed(3.0f, slowest, cohesionRadius * 1.5f, cohesionRadius);
        assertEquals(0f, aheadFlyerSpeed, 1e-5f);

        // Partially ahead flyer slows smoothly below slowestSpeed
        float slightlyAhead = FormationMath.computeMarchSpeed(3.0f, slowest, cohesionRadius * 1.0f, cohesionRadius);
        assertTrue(slightlyAhead > 0f && slightlyAhead < slowest);

        // Lagging fast flyer gets a mild catch-up boost (up to 1.30x slowest) without exceeding base speed
        float laggingFlyer = FormationMath.computeMarchSpeed(3.0f, slowest, -cohesionRadius * 1.5f, cohesionRadius);
        assertEquals(slowest * 1.30f, laggingFlyer, 1e-4f);

        // Slowest member lagging cannot exceed its own base speed
        float laggingSlowest = FormationMath.computeMarchSpeed(0.4f, slowest, -cohesionRadius * 1.5f, cohesionRadius);
        assertEquals(0.4f, laggingSlowest, 1e-5f);
    }

    @Test
    void crossDomainTerrainBlockedTriggersCooperativeHoldAndRegroup(){
        float cohesionRadius = 48f;

        // No member blocked -> everyone marches normally
        assertEquals(
            CohesionAction.MARCH,
            FormationMath.evaluateCohesionAction(false, false, 100f, cohesionRadius)
        );

        // Naval unit blocked at shoreline -> naval unit holds
        assertEquals(
            CohesionAction.HOLD,
            FormationMath.evaluateCohesionAction(true, true, 0f, cohesionRadius)
        );

        // Army unit right beside the blocked naval unit -> army holds beside navy
        assertEquals(
            CohesionAction.HOLD,
            FormationMath.evaluateCohesionAction(true, false, cohesionRadius * 0.8f, cohesionRadius)
        );

        // Army or Air unit that already moved inland away from blocked navy -> regroups back beside navy
        assertEquals(
            CohesionAction.REGROUP_TO_BLOCKED,
            FormationMath.evaluateCohesionAction(true, false, cohesionRadius * 2.0f, cohesionRadius)
        );
    }

    @Test
    void rallyAnchorPrioritizesMostTerrainRestrictedDomain(){
        int navy = FormationMath.movementDomainPriority(true, false, false);
        int ground = FormationMath.movementDomainPriority(false, false, false);
        int legs = FormationMath.movementDomainPriority(false, false, true);
        int air = FormationMath.movementDomainPriority(false, true, false);

        assertTrue(navy > ground);
        assertTrue(ground > legs);
        assertTrue(legs > air);
    }

    @Test
    void slotOffsetsPlaceMembersSymmetricallyWithoutNaN(){
        float[] hitSizes = {10f, 10f, 14f, 18f, 12f, 8f, 16f};
        float[] offsets = new float[hitSizes.length * 2];
        FormationMath.computeSlotOffsets(hitSizes, offsets);

        for(int i = 0; i < hitSizes.length; i++){
            float ox = offsets[i * 2];
            float oy = offsets[i * 2 + 1];
            assertFalse(Float.isNaN(ox) || Float.isNaN(oy));
            assertTrue(Math.hypot(ox, oy) > 0.1f, "Multi-unit slot offset should lie on ring");
        }
    }

    @Test
    void formationManagerSerializesAndRestoresPersistentFormations() throws IOException{
        FormationManager fm = FormationManager.get();
        fm.clear();

        // Populate state via stream roundtrip to test pure-Java serialization without Groups.unit runtime
        ByteArrayOutputStream src = new ByteArrayOutputStream();
        try(DataOutputStream out = new DataOutputStream(src)){
            out.writeInt(1); // chunk version
            out.writeInt(5); // nextFormationId
            out.writeInt(1); // 1 formation
            out.writeInt(3); // formation id
            out.writeInt(1); // team id (sharded)
            out.writeBoolean(true); // hasTarget
            out.writeFloat(240f);
            out.writeFloat(360f);
            out.writeInt(3); // 3 members
            out.writeInt(101);
            out.writeInt(102);
            out.writeInt(103);
        }

        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(src.toByteArray()))){
            fm.readStateFromStream(in);
        }

        TacticalFormation restored = fm.getFormation(3);
        assertNotNull(restored);
        assertEquals(3, restored.size());
        assertTrue(restored.containsUnit(101));
        assertTrue(restored.containsUnit(102));
        assertTrue(restored.containsUnit(103));
        assertTrue(restored.hasTarget);
        assertEquals(240f, restored.sharedTarget.x, 1e-4f);
        assertEquals(360f, restored.sharedTarget.y, 1e-4f);
        assertSame(restored, fm.getFormationOfUnitId(102));

        // Roundtrip write -> read preserves exact data
        ByteArrayOutputStream reEncoded = new ByteArrayOutputStream();
        try(DataOutputStream out = new DataOutputStream(reEncoded)){
            fm.writeStateToStream(out);
        }
        fm.clear();
        assertNull(fm.getFormation(3));

        try(DataInputStream in = new DataInputStream(new ByteArrayInputStream(reEncoded.toByteArray()))){
            fm.readStateFromStream(in);
        }

        TacticalFormation again = fm.getFormation(3);
        assertNotNull(again);
        assertEquals(3, again.size());
        assertEquals(360f, again.sharedTarget.y, 1e-4f);

        fm.clear();
    }
}
