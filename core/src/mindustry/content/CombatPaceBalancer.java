package mindustry.content;

import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.entities.abilities.*;
import mindustry.entities.bullet.*;
import mindustry.type.*;
import mindustry.type.unit.*;
import mindustry.world.*;
import mindustry.world.blocks.defense.*;
import mindustry.world.blocks.defense.turrets.*;

/**
 * Centralized v1.6 combat pace balancer (Plan A + B).
 *
 * Slows overall combat DPS to ~0.50x (~2.0x TTK) without altering mining,
 * conveyor logistics, or block construction pacing.
 *
 * Designed to execute strictly once after content loading completes.
 */
public final class CombatPaceBalancer{
    /** Rule-layer multipliers (applied via Rules#applyCombatPace). */
    public static final float DAMAGE_RULE_MULT = 0.70f;
    public static final float UNIT_CRASH_DAMAGE_MULT = 1.0f;
    public static final float WAVE_SPACING_MULT = 1.80f;
    public static final float UNIT_BUILD_SPEED_MULT = 0.75f;

    /** Content-layer turret & weapon multipliers. */
    public static final float RELOAD_MULT = 1.40f;
    public static final float TURRET_ROTATE_MULT = 0.85f;
    public static final float CONTINUOUS_DAMAGE_MULT = 0.714f;
    public static final float LASER_TURRET_DAMAGE_MULT = 0.78f;

    /** Content-layer unit & projectile multipliers. */
    public static final float UNIT_SPEED_MULT = 0.75f;
    public static final float UNIT_ROTATE_MULT = 0.85f;
    public static final float ARMOR_MULT = 0.70f;
    public static final float PROJECTILE_SPEED_MULT = 0.75f;
    public static final float PROJECTILE_LIFETIME_MULT = 1.333f;

    /** Healing, repair, shield & status DoT multipliers. */
    public static final float HEAL_MULT = 0.55f;
    public static final float MEND_RELOAD_MULT = 1.80f;
    public static final float SHIELD_COOLDOWN_BROKEN_MULT = 0.70f;
    public static final float STATUS_DOT_MULT = 0.50f;

    /** Minimum bullet speed to be treated as a physical flying projectile. */
    private static final float MIN_PROJECTILE_SPEED = 0.01f;

    /** Static re-entry guard so global content scaling strictly runs once. */
    private static boolean applied = false;

    private CombatPaceBalancer(){
    }

    /** @return whether global content scaling has already been applied. */
    public static boolean isApplied(){
        return applied;
    }

    /** Resets the static re-entry guard (for isolated unit tests only). */
    public static void resetForTesting(){
        applied = false;
    }

    static void setAppliedForTesting(boolean value){
        applied = value;
    }

    /**
     * Applies v1.6 combat pace scaling to all loaded game content in {@link Vars#content}.
     * Guarded by a static flag so repeated calls are safe no-ops.
     */
    public static synchronized void apply(){
        if(applied || Vars.content == null) return;
        applied = true;

        applyToCollections(
            Vars.content.blocks(),
            Vars.content.units(),
            Vars.content.statusEffects()
        );
    }

    /**
     * Applies v1.6 combat pace scaling to explicit content collections.
     * Useful for both {@link #apply()} and isolated unit tests.
     */
    public static void applyToCollections(
        @Nullable Seq<Block> blocks,
        @Nullable Seq<UnitType> units,
        @Nullable Seq<StatusEffect> statusEffects
    ){
        ObjectSet<BulletType> visitedBullets = new ObjectSet<>();
        ObjectSet<BulletType> visitedDamageBullets = new ObjectSet<>();
        ObjectSet<UnitType> visitedUnits = new ObjectSet<>();

        if(blocks != null){
            for(Block block : blocks){
                scaleBlock(block, visitedBullets, visitedDamageBullets, visitedUnits);
            }
        }

        if(units != null){
            for(UnitType unit : units){
                scaleUnit(unit, visitedBullets, visitedDamageBullets, visitedUnits);
            }
        }

        if(statusEffects != null){
            for(StatusEffect effect : statusEffects){
                scaleStatusEffect(effect);
            }
        }
    }

    private static void scaleBlock(
        @Nullable Block block,
        ObjectSet<BulletType> visitedBullets,
        ObjectSet<BulletType> visitedDamageBullets,
        ObjectSet<UnitType> visitedUnits
    ){
        if(block == null) return;

        // 1. Linearly align fixed armor on all blocks with 0.70x rule damage.
        if(block.armor > 0f){
            block.armor *= ARMOR_MULT;
        }

        // 2. Turrets (order matters: check concrete subclasses before Turret).
        if(block instanceof ContinuousLiquidTurret clt){
            clt.rotateSpeed *= TURRET_ROTATE_MULT;
            if(clt.ammoTypes != null){
                for(BulletType bullet : clt.ammoTypes.values()){
                    scaleBulletDamageOnce(bullet, CONTINUOUS_DAMAGE_MULT, visitedDamageBullets);
                    scaleBullet(bullet, visitedBullets, visitedDamageBullets, visitedUnits);
                }
            }
            if(clt.shootType != null && clt.shootType != Bullets.placeholder){
                scaleBulletDamageOnce(clt.shootType, CONTINUOUS_DAMAGE_MULT, visitedDamageBullets);
                scaleBullet(clt.shootType, visitedBullets, visitedDamageBullets, visitedUnits);
            }
        }else if(block instanceof ContinuousTurret ct){
            ct.rotateSpeed *= TURRET_ROTATE_MULT;
            if(ct.shootType != null){
                scaleBulletDamageOnce(ct.shootType, CONTINUOUS_DAMAGE_MULT, visitedDamageBullets);
                scaleBullet(ct.shootType, visitedBullets, visitedDamageBullets, visitedUnits);
            }
        }else if(block instanceof LaserTurret lt){
            lt.reload *= RELOAD_MULT;
            lt.rotateSpeed *= TURRET_ROTATE_MULT;
            if(lt.shootType != null){
                scaleBulletDamageOnce(lt.shootType, LASER_TURRET_DAMAGE_MULT, visitedDamageBullets);
                scaleBullet(lt.shootType, visitedBullets, visitedDamageBullets, visitedUnits);
            }
        }else if(block instanceof Turret turret){
            turret.reload *= RELOAD_MULT;
            turret.rotateSpeed *= TURRET_ROTATE_MULT;
            scaleTurretBullets(turret, visitedBullets, visitedDamageBullets, visitedUnits);
        }else if(block instanceof PointDefenseTurret pdt){
            // PointDefenseTurret extends ReloadTurret, not Turret.
            pdt.reload *= RELOAD_MULT;
        }

        // 3. Repair blocks & force shields.
        if(block instanceof MendProjector mend){
            mend.reload *= MEND_RELOAD_MULT;
        }else if(block instanceof RegenProjector regen){
            regen.healPercent *= HEAL_MULT;
        }else if(block instanceof ForceProjector force){
            // Keep shieldHealth at 1.0x; slow regen & broken shield recovery.
            force.phaseShieldBoost *= HEAL_MULT;
            force.cooldownNormal *= HEAL_MULT;
            force.cooldownBrokenBase *= SHIELD_COOLDOWN_BROKEN_MULT;
        }
    }

    private static void scaleTurretBullets(
        Turret turret,
        ObjectSet<BulletType> visitedBullets,
        ObjectSet<BulletType> visitedDamageBullets,
        ObjectSet<UnitType> visitedUnits
    ){
        if(turret instanceof ItemTurret it && it.ammoTypes != null){
            for(BulletType bullet : it.ammoTypes.values()){
                scaleBullet(bullet, visitedBullets, visitedDamageBullets, visitedUnits);
            }
        }else if(turret instanceof LiquidTurret lt && lt.ammoTypes != null){
            for(BulletType bullet : lt.ammoTypes.values()){
                scaleBullet(bullet, visitedBullets, visitedDamageBullets, visitedUnits);
            }
        }else if(turret instanceof PayloadAmmoTurret pat && pat.ammoTypes != null){
            for(BulletType bullet : pat.ammoTypes.values()){
                scaleBullet(bullet, visitedBullets, visitedDamageBullets, visitedUnits);
            }
        }else if(turret instanceof PowerTurret pt){
            scaleBullet(pt.shootType, visitedBullets, visitedDamageBullets, visitedUnits);
        }
    }

    private static void scaleUnit(
        @Nullable UnitType unit,
        ObjectSet<BulletType> visitedBullets,
        ObjectSet<BulletType> visitedDamageBullets,
        ObjectSet<UnitType> visitedUnits
    ){
        if(unit == null || !visitedUnits.add(unit)) return;

        unit.speed *= UNIT_SPEED_MULT;
        unit.rotateSpeed *= UNIT_ROTATE_MULT;
        if(unit.armor > 0f){
            unit.armor *= ARMOR_MULT;
        }

        // Preserve missile real range (speed * lifetime) when missile speed is scaled to 0.75x.
        if(unit instanceof MissileUnitType){
            unit.lifetime *= PROJECTILE_LIFETIME_MULT;
        }

        if(unit.weapons != null){
            for(Weapon weapon : unit.weapons){
                if(weapon == null) continue;
                weapon.reload *= RELOAD_MULT;
                if(weapon.bullet != null){
                    if(weapon.continuous || weapon.alwaysContinuous){
                        scaleBulletDamageOnce(weapon.bullet, CONTINUOUS_DAMAGE_MULT, visitedDamageBullets);
                    }
                    scaleBullet(weapon.bullet, visitedBullets, visitedDamageBullets, visitedUnits);
                }
            }
        }

        if(unit.abilities != null){
            for(Ability ability : unit.abilities){
                if(ability instanceof RepairFieldAbility repair){
                    repair.amount *= HEAL_MULT;
                    repair.healPercent *= HEAL_MULT;
                }else if(ability instanceof ShieldRegenFieldAbility shield){
                    shield.amount *= HEAL_MULT;
                }else if(ability instanceof RegenAbility regen){
                    regen.amount *= HEAL_MULT;
                    regen.percentAmount *= HEAL_MULT;
                }
            }
        }
    }

    private static void scaleBulletDamageOnce(
        @Nullable BulletType bullet,
        float multiplier,
        ObjectSet<BulletType> visitedDamageBullets
    ){
        if(bullet == null || !visitedDamageBullets.add(bullet)) return;
        bullet.damage *= multiplier;
    }

    private static void scaleBullet(
        @Nullable BulletType bullet,
        ObjectSet<BulletType> visitedBullets,
        ObjectSet<BulletType> visitedDamageBullets,
        ObjectSet<UnitType> visitedUnits
    ){
        if(bullet == null || !visitedBullets.add(bullet)) return;

        // Only slow physical flying projectiles and extend lifetime proportionally to preserve range.
        if(isPhysicalProjectile(bullet)){
            bullet.speed *= PROJECTILE_SPEED_MULT;
            bullet.lifetime *= PROJECTILE_LIFETIME_MULT;
        }

        if(bullet.healPercent > 0f){
            bullet.healPercent *= HEAL_MULT;
        }
        if(bullet.healAmount > 0f){
            bullet.healAmount *= HEAL_MULT;
        }

        // Recursively scale nested bullets (all deduplicated via visitedBullets).
        scaleBullet(bullet.fragBullet, visitedBullets, visitedDamageBullets, visitedUnits);
        scaleBullet(bullet.intervalBullet, visitedBullets, visitedDamageBullets, visitedUnits);

        if(bullet.spawnBullets != null){
            for(BulletType sub : bullet.spawnBullets){
                scaleBullet(sub, visitedBullets, visitedDamageBullets, visitedUnits);
            }
        }

        if(bullet instanceof MultiBulletType multi && multi.bullets != null){
            for(BulletType sub : multi.bullets){
                scaleBullet(sub, visitedBullets, visitedDamageBullets, visitedUnits);
            }
        }

        // Ensure spawned missile units are scaled even if not registered in the main units list.
        if(bullet.spawnUnit != null){
            scaleUnit(bullet.spawnUnit, visitedBullets, visitedDamageBullets, visitedUnits);
        }
        if(bullet.despawnUnit != null){
            scaleUnit(bullet.despawnUnit, visitedBullets, visitedDamageBullets, visitedUnits);
        }
    }

    public static boolean isPhysicalProjectile(@Nullable BulletType bullet){
        if(bullet == null || bullet.speed <= MIN_PROJECTILE_SPEED) return false;
        return !(bullet instanceof ContinuousBulletType)
            && !(bullet instanceof PointLaserBulletType)
            && !(bullet instanceof LaserBulletType)
            && !(bullet instanceof LightningBulletType)
            && !(bullet instanceof RailBulletType)
            && !(bullet instanceof PointBulletType);
    }

    private static void scaleStatusEffect(@Nullable StatusEffect effect){
        if(effect == null) return;
        if(effect.damage > 0f){
            effect.damage *= STATUS_DOT_MULT;
        }
        if(effect.intervalDamage > 0f){
            effect.intervalDamage *= STATUS_DOT_MULT;
        }
    }
}
