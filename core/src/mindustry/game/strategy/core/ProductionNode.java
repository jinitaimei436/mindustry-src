package mindustry.game.strategy.core;

import java.util.*;

/**
 * A compressed production/consumption unit inside a background sector.
 * Represents a drill, crafter, power generator, or aggregated fallback node.
 */
public class ProductionNode{
    /** Identifier or block name (e.g. "graphite-press", "pneumatic-drill", "fallback-raw"). */
    public String blockName = "";

    /** Number of identical blocks merged into this node to keep the graph compact. */
    public int count = 1;

    /** Items consumed per second at 100% efficiency (key: Item.name). */
    public HashMap<String, Float> inputsPerSec = new HashMap<>();

    /** Items produced per second at 100% efficiency (key: Item.name). */
    public HashMap<String, Float> outputsPerSec = new HashMap<>();

    /** Power produced per second at 100% efficiency (in Mindustry power/sec units). */
    public float powerProducePerSec = 0f;

    /** Power consumed per second at 100% efficiency (in Mindustry power/sec units). */
    public float powerConsumePerSec = 0f;

    /**
     * Calibration factor observed during foreground play (0.0 - 1.0).
     * Captures conveyor congestion, partial ore coverage, or logic throttling.
     */
    public float measuredEfficiency = 1f;

    /** Whether this node stops running when all of its output items reach storage capacity. */
    public boolean stopWhenOutputsFull = true;

    /** Last solved effective efficiency (0.0 - 1.0), computed at runtime. */
    public transient float solvedEfficiency = 0f;

    public ProductionNode(){
    }

    public ProductionNode(String blockName){
        this.blockName = blockName;
    }

    public ProductionNode addInput(String itemName, float perSec){
        if(perSec > 0f){
            inputsPerSec.put(itemName, inputsPerSec.getOrDefault(itemName, 0f) + perSec);
        }
        return this;
    }

    public ProductionNode addOutput(String itemName, float perSec){
        if(perSec > 0f){
            outputsPerSec.put(itemName, outputsPerSec.getOrDefault(itemName, 0f) + perSec);
        }
        return this;
    }

    public ProductionNode withPower(float producePerSec, float consumePerSec){
        this.powerProducePerSec = producePerSec;
        this.powerConsumePerSec = consumePerSec;
        return this;
    }

    public ProductionNode withCalibration(float efficiency){
        this.measuredEfficiency = Math.max(0f, Math.min(1f, efficiency));
        return this;
    }
}
