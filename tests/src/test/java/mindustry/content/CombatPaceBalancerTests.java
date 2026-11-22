package mindustry.content;

import arc.*;
import arc.util.*;
import mindustry.*;
import mindustry.core.*;
import mindustry.entities.abilities.*;
import mindustry.entities.bullet.*;
import mindustry.game.*;
import mindustry.type.*;
import mindustry.type.unit.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.turrets.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

public class CombatPaceBalancerTests{
    private ContentLoader previousContent;
    private I18NBundle previousBundle;
    private boolean previousApplied;

    @BeforeEach
    void setUp(){
        previousContent = Vars.content;
        previousBundle = Core.bundle;
        previousApplied = CombatPaceBalancer.isApplied();

        if(Core.bundle == null){
            Core.bundle = I18NBundle.createEmptyBundle();
        }
        Vars.content = new ContentLoader();
        CombatPaceBalancer.resetForTesting();
    }

    @AfterEach
    void tearDown(){
        Vars.content = previousContent;
        Core.bundle = previousBundle;
        CombatPaceBalancer.setAppliedForTesting(previousApplied);
    }

    @Test
    void regularTurretCombinedDpsIsHalfAndPointDefenseSlowed(){
        Item copper = new Item("test-copper");
        BasicBulletType bullet = new BasicBulletType(4f, 20f);
        bullet.lifetime = 60f;

        ItemTurret duo = new ItemTurret("test-duo");
        duo.reload = 20f;
        duo.rotateSpeed = 10f;
        duo.ammo(copper, bullet);

        PointDefenseTurret segment = new PointDefenseTurret("test-segment");
        segment.reload = 30f;

        float baseDps = bullet.damage * (60f / duo.reload);

        Rules rules = new Rules();
        rules.applyCombatPace();
        CombatPaceBalancer.apply();

        float scaledDps = (bullet.damage * rules.blockDamage(Team.sharded)) * (60f / duo.reload);
        float dpsRatio = scaledDps / baseDps;

        assertEquals(0.50f, dpsRatio, 0.01f, "Regular turret combined DPS should be ~0.50x");
        assertEquals(28f, duo.reload, 1e-4f);
        assertEquals(8.5f, duo.rotateSpeed, 1e-4f);
        assertEquals(42f, segment.reload, 1e-4f, "PointDefenseTurret reload should scale by 1.40x");
    }

    @Test
    void continuousBeamAndLaserTurretsDpsBalanced(){
        ContinuousLaserBulletType beam = new ContinuousLaserBulletType(100f);
        ContinuousTurret lustre = new ContinuousTurret("test-lustre");
        lustre.reload = 10f;
        lustre.rotateSpeed = 8f;
        lustre.shootType = beam;

        Liquid slag = new Liquid("test-slag");
        ContinuousLaserBulletType liquidBeam = new ContinuousLaserBulletType(80f);
        ContinuousLiquidTurret sublimate = new ContinuousLiquidTurret("test-sublimate");
        sublimate.rotateSpeed = 6f;
        sublimate.ammo(slag, liquidBeam);

        ContinuousLaserBulletType meltdownBeam = new ContinuousLaserBulletType(120f);
        LaserTurret meltdown = new LaserTurret("test-meltdown");
        meltdown.reload = 90f;
        meltdown.rotateSpeed = 4f;
        meltdown.shootType = meltdownBeam;

        Rules rules = new Rules();
        rules.applyCombatPace();
        CombatPaceBalancer.apply();

        float continuousDpsRatio = (beam.damage * rules.blockDamage(Team.sharded)) / 100f;
        float liquidContinuousDpsRatio = (liquidBeam.damage * rules.blockDamage(Team.sharded)) / 80f;

        assertEquals(0.50f, continuousDpsRatio, 0.01f, "ContinuousTurret combined DPS should be ~0.50x");
        assertEquals(0.50f, liquidContinuousDpsRatio, 0.01f, "ContinuousLiquidTurret combined DPS should be ~0.50x");
        assertEquals(10f, lustre.reload, 1e-4f, "ContinuousTurret reload should remain unchanged");
        assertEquals(6.8f, lustre.rotateSpeed, 1e-4f);
        assertEquals(5.1f, sublimate.rotateSpeed, 1e-4f);

        assertEquals(126f, meltdown.reload, 1e-4f, "LaserTurret reload should scale by 1.40x");
        assertEquals(3.4f, meltdown.rotateSpeed, 1e-4f);
        assertEquals(93.6f, meltdownBeam.damage, 1e-4f, "LaserTurret shootType.damage should scale by 0.78x");
    }

    @Test
    void projectileAndMissileRealRangePreservedWithinOnePercent(){
        Item copper = new Item("test-copper-range");
        BasicBulletType physical = new BasicBulletType(8f, 30f);
        physical.lifetime = 45f;
        float origPhysicalRange = physical.speed * physical.lifetime;

        LaserBulletType laser = new LaserBulletType(50f);
        laser.speed = 1f; // even with non-zero speed, LaserBulletType must be excluded
        laser.lifetime = 16f;

        LightningBulletType lightning = new LightningBulletType();
        lightning.speed = 1f;
        lightning.lifetime = 10f;

        RailBulletType rail = new RailBulletType();
        rail.speed = 1f;
        rail.lifetime = 12f;

        PointBulletType point = new PointBulletType();
        point.speed = 2f;
        point.lifetime = 100f;

        ContinuousLaserBulletType continuous = new ContinuousLaserBulletType(40f);
        continuous.speed = 1f;
        continuous.lifetime = 16f;

        ItemTurret turret = new ItemTurret("test-range-turret");
        turret.ammo(
            copper, physical,
            new Item("test-i2"), laser,
            new Item("test-i3"), lightning,
            new Item("test-i4"), rail,
            new Item("test-i5"), point,
            new Item("test-i6"), continuous
        );

        MissileUnitType missile = new MissileUnitType("test-missile");
        missile.speed = 6f;
        missile.lifetime = 90f;
        float origMissileRange = missile.speed * missile.lifetime;

        CombatPaceBalancer.apply();

        float scaledPhysicalRange = physical.speed * physical.lifetime;
        float physicalError = Math.abs(scaledPhysicalRange - origPhysicalRange) / origPhysicalRange;
        assertTrue(physicalError < 0.01f, "Physical projectile range error must be < 1%, was " + physicalError);
        assertEquals(6f, physical.speed, 1e-4f);
        assertEquals(45f * 1.333f, physical.lifetime, 1e-3f);

        float scaledMissileRange = missile.speed * missile.lifetime;
        float missileError = Math.abs(scaledMissileRange - origMissileRange) / origMissileRange;
        assertTrue(missileError < 0.01f, "MissileUnitType range error must be < 1%, was " + missileError);
        assertEquals(4.5f, missile.speed, 1e-4f);

        // Non-physical bullet types must NOT have speed/lifetime scaled.
        assertEquals(1f, laser.speed, 1e-4f);
        assertEquals(16f, laser.lifetime, 1e-4f);
        assertEquals(1f, lightning.speed, 1e-4f);
        assertEquals(10f, lightning.lifetime, 1e-4f);
        assertEquals(1f, rail.speed, 1e-4f);
        assertEquals(12f, rail.lifetime, 1e-4f);
        assertEquals(2f, point.speed, 1e-4f);
        assertEquals(100f, point.lifetime, 1e-4f);
        assertEquals(1f, continuous.speed, 1e-4f);
        assertEquals(16f, continuous.lifetime, 1e-4f);
    }

    @Test
    void sharedBulletsAndRepeatedApplyNeverDoubleScale(){
        Item copper = new Item("test-shared-copper");

        BasicBulletType sharedFrag = new BasicBulletType(4f, 10f);
        sharedFrag.lifetime = 30f;

        BasicBulletType sharedInterval = new BasicBulletType(5f, 12f);
        sharedInterval.lifetime = 25f;

        BasicBulletType sharedMain = new BasicBulletType(6f, 40f);
        sharedMain.lifetime = 50f;
        sharedMain.fragBullet = sharedFrag;
        sharedMain.intervalBullet = sharedInterval;
        sharedFrag.fragBullet = sharedInterval; // DAG shared reference

        ContinuousLaserBulletType sharedBeam = new ContinuousLaserBulletType(100f);

        ItemTurret turretA = new ItemTurret("test-turret-a");
        turretA.reload = 20f;
        turretA.ammo(copper, sharedMain);

        ItemTurret turretB = new ItemTurret("test-turret-b");
        turretB.reload = 30f;
        turretB.ammo(copper, sharedMain);

        ContinuousTurret beamTurretA = new ContinuousTurret("test-beam-a");
        beamTurretA.shootType = sharedBeam;

        ContinuousTurret beamTurretB = new ContinuousTurret("test-beam-b");
        beamTurretB.shootType = sharedBeam;

        UnitType unit = new UnitType("test-unit-shared");
        Weapon w = new Weapon("test-shared-weapon");
        w.reload = 20f;
        w.bullet = sharedMain;
        Weapon beamWeapon = new Weapon("test-shared-beam-weapon");
        beamWeapon.reload = 10f;
        beamWeapon.continuous = true;
        beamWeapon.bullet = sharedBeam;
        unit.weapons.add(w, beamWeapon);

        CombatPaceBalancer.apply();
        // Second call must be a strict no-op via static re-entry guard.
        CombatPaceBalancer.apply();

        assertEquals(4.5f, sharedMain.speed, 1e-4f, "Shared main bullet must only be scaled once");
        assertEquals(50f * 1.333f, sharedMain.lifetime, 1e-3f);
        assertEquals(3f, sharedFrag.speed, 1e-4f, "Shared frag bullet must only be scaled once");
        assertEquals(3.75f, sharedInterval.speed, 1e-4f, "Shared interval bullet must only be scaled once");
        assertEquals(71.4f, sharedBeam.damage, 1e-3f, "Shared continuous beam damage must only be scaled once");
        assertEquals(28f, turretA.reload, 1e-4f, "Repeated apply() must not double-scale turret reload");
        assertEquals(28f, w.reload, 1e-4f, "Repeated apply() must not double-scale weapon reload");
    }

    @Test
    void fixedArmorToDamageRatioPreservedForUnitsAndBlocks(){
        UnitType heavyUnit = new UnitType("test-reign");
        heavyUnit.armor = 18f;
        heavyUnit.speed = 0.4f;
        heavyUnit.rotateSpeed = 2f;

        Block wall = new Block("test-thorium-wall");
        wall.armor = 10f;

        float rawBulletDamage = 25f;
        float origUnitArmorRatio = heavyUnit.armor / rawBulletDamage;
        float origBlockArmorRatio = wall.armor / rawBulletDamage;

        Rules rules = new Rules();
        rules.applyCombatPace();
        CombatPaceBalancer.apply();

        float scaledBulletDamage = rawBulletDamage * rules.unitDamage(Team.sharded);
        float newUnitArmorRatio = heavyUnit.armor / scaledBulletDamage;
        float newBlockArmorRatio = wall.armor / scaledBulletDamage;

        assertEquals(origUnitArmorRatio, newUnitArmorRatio, 1e-5f, "Unit armor/damage ratio must remain invariant");
        assertEquals(origBlockArmorRatio, newBlockArmorRatio, 1e-5f, "Block armor/damage ratio must remain invariant");
        assertEquals(0.30f, heavyUnit.speed, 1e-4f);
        assertEquals(1.70f, heavyUnit.rotateSpeed, 1e-4f);
    }

    @Test
    void healingShieldsAndStatusDotScaledCorrectly(){
        MendProjector mend = new MendProjector("test-mend");
        mend.reload = 250f;

        RegenProjector regen = new RegenProjector("test-regen");
        regen.healPercent = 0.20f;

        ForceProjector force = new ForceProjector("test-force");
        force.shieldHealth = 700f;
        force.phaseShieldBoost = 400f;
        force.cooldownNormal = 1.75f;
        force.cooldownBrokenBase = 0.35f;

        BasicBulletType healBullet = new BasicBulletType(4f, 10f);
        healBullet.lifetime = 30f;
        healBullet.healPercent = 10f;
        healBullet.healAmount = 40f;

        UnitType support = new UnitType("test-mega");
        Weapon healWeapon = new Weapon("test-heal-w");
        healWeapon.reload = 20f;
        healWeapon.bullet = healBullet;
        support.weapons.add(healWeapon);

        RepairFieldAbility repairAbility = new RepairFieldAbility(20f, 100f, 60f, 5f);
        ShieldRegenFieldAbility shieldAbility = new ShieldRegenFieldAbility(30f, 200f, 100f, 60f);
        RegenAbility regenAbility = new RegenAbility();
        regenAbility.amount = 2f;
        regenAbility.percentAmount = 0.5f;
        support.abilities.add(repairAbility, shieldAbility, regenAbility);

        StatusEffect burning = new StatusEffect("test-burning");
        burning.damage = 0.12f;
        burning.intervalDamage = 10f;

        StatusEffect healingStatus = new StatusEffect("test-healing-status");
        healingStatus.damage = -0.10f; // negative damage = heal, should not be treated as DoT

        CombatPaceBalancer.apply();

        assertEquals(450f, mend.reload, 1e-4f);
        assertEquals(0.11f, regen.healPercent, 1e-5f);

        assertEquals(700f, force.shieldHealth, 1e-4f, "ForceProjector shieldHealth must stay 1.0x");
        assertEquals(220f, force.phaseShieldBoost, 1e-4f);
        assertEquals(1.75f * 0.55f, force.cooldownNormal, 1e-4f);
        assertEquals(0.35f * 0.70f, force.cooldownBrokenBase, 1e-4f);

        assertEquals(5.5f, healBullet.healPercent, 1e-4f);
        assertEquals(22f, healBullet.healAmount, 1e-4f);

        assertEquals(11f, repairAbility.amount, 1e-4f);
        assertEquals(2.75f, repairAbility.healPercent, 1e-4f);
        assertEquals(16.5f, shieldAbility.amount, 1e-4f);
        assertEquals(200f, shieldAbility.max, 1e-4f, "ShieldRegenFieldAbility max shield should stay unchanged");
        assertEquals(1.1f, regenAbility.amount, 1e-4f);
        assertEquals(0.275f, regenAbility.percentAmount, 1e-4f);

        assertEquals(0.06f, burning.damage, 1e-5f);
        assertEquals(5f, burning.intervalDamage, 1e-4f);
        assertEquals(-0.10f, healingStatus.damage, 1e-5f);
    }

    @Test
    void rulesAndCampaignRulesAreIdempotentAndToggleable(){
        Planet planet = new Planet("test-planet", null, 1f);
        CampaignRules campaign = new CampaignRules();
        Rules rules = new Rules();
        float baseWaveSpacing = rules.waveSpacing;

        assertTrue(campaign.slowCombatPace);
        campaign.apply(planet, rules);
        // Apply again to simulate sector reload or rules dialog close.
        campaign.apply(planet, rules);

        assertEquals(0.70f, rules.unitDamageMultiplier, 1e-5f);
        assertEquals(0.70f, rules.blockDamageMultiplier, 1e-5f);
        assertEquals(1.0f, rules.unitCrashDamageMultiplier, 1e-5f);
        assertEquals(0.70f, rules.unitCrashDamage(Team.sharded), 1e-5f);
        assertEquals(0.75f, rules.unitBuildSpeedMultiplier, 1e-5f);
        assertEquals(baseWaveSpacing * 1.80f, rules.waveSpacing, 1e-3f, "waveSpacing must scale by 1.80x exactly once");

        // Disable toggle and verify clean revert.
        campaign.slowCombatPace = false;
        campaign.apply(planet, rules);

        assertEquals(1.0f, rules.unitDamageMultiplier, 1e-5f);
        assertEquals(1.0f, rules.blockDamageMultiplier, 1e-5f);
        assertEquals(1.0f, rules.unitBuildSpeedMultiplier, 1e-5f);
        assertEquals(baseWaveSpacing, rules.waveSpacing, 1e-2f);
    }
}
