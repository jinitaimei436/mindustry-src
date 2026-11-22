package mindustry.io;

import arc.graphics.*;
import arc.math.geom.*;
import arc.util.*;
import arc.util.serialization.*;
import arc.util.serialization.Json.*;
import mindustry.*;
import mindustry.audio.*;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.game.*;
import mindustry.game.MapObjectives.*;
import mindustry.game.strategy.core.*;
import mindustry.maps.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.meta.*;

import java.io.*;
import java.util.*;

@SuppressWarnings("unchecked")
public class JsonIO{
    public static final Json json = new Json(){
        @Override
        public void writeValue(Object value, Class knownType, Class elementType){
            if(value instanceof MappableContent c){
                try{
                    getWriter().value(c.name);
                }catch(IOException e){
                    throw new RuntimeException(e);
                }
            }else{
                super.writeValue(value, knownType, elementType);
            }
        }

        @Override
        protected String convertToString(Object object){
            if(object instanceof MappableContent c) return c.name;
            return super.convertToString(object);
        }
    };

    public static void writeBytes(Object value, Class<?> elementType, DataOutputStream output){
        json.setWriter(new UBJsonWriter(output));
        json.writeValue(value, value == null ? null : value.getClass(), elementType);
    }

    public static <T> T readBytes(Class<T> type, Class<?> elementType, DataInputStream input) throws IOException{
        return json.readValue(type, elementType, new UBJsonReader().parseWihoutClosing(input));
    }

    public static String write(Object object){
        return json.toJson(object, object.getClass());
    }

    public static <T> T copy(T object, T dest){
        json.copyFields(object, dest);
        return dest;
    }

    public static <T> T copy(T object){
        return read((Class<T>)object.getClass(), write(object));
    }

    public static <T> T read(Class<T> type, String string){
        return json.fromJson(type, string.replace("io.anuke.", ""));
    }

    public static <T> T read(T base, String string){
        json.readFields(base, new JsonReader().parse(string.replace("io.anuke.", "")));
        return base;
    }

    public static String print(String in){
        return json.prettyPrint(in);
    }

    public static void classTag(String tag, Class<?> type){
        json.addClassTag(tag, type);
    }

    static{
        json.setElementType(Rules.class, "spawns", SpawnGroup.class);
        json.setElementType(Rules.class, "loadout", ItemStack.class);

        json.setSerializer(MusicContainer.class, new Serializer<>(){
            @Override
            public void write(Json json, MusicContainer object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public MusicContainer read(Json json, JsonValue jsonData, Class type){
                return new MusicContainer(jsonData.isString() ? jsonData.asString() : "");
            }
        });

        json.setSerializer(Color.class, new Serializer<>(){
            @Override
            public void write(Json json, Color object, Class knownType){
                json.writeValue(object.toString());
            }

            @Override
            public Color read(Json json, JsonValue jsonData, Class type){
                if(jsonData.isString()){
                    return Color.valueOf(jsonData.asString());
                }
                Color out = new Color();
                json.readFields(out, jsonData);
                return out;
            }
        });

        json.setSerializer(Sector.class, new Serializer<>(){
            @Override
            public void write(Json json, Sector object, Class knownType){
                json.writeValue(object.planet.name + "-" + object.id);
            }

            @Override
            public Sector read(Json json, JsonValue jsonData, Class type){
                String name = jsonData.asString();
                int idx = name.lastIndexOf('-');
                return Vars.content.<Planet>getByName(ContentType.planet, name.substring(0, idx)).sectors.get(Integer.parseInt(name.substring(idx + 1)));
            }
        });

        json.setSerializer(SectorPreset.class, new Serializer<>(){
            @Override
            public void write(Json json, SectorPreset object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public SectorPreset read(Json json, JsonValue jsonData, Class type){
                return Vars.content.getByName(ContentType.sector, jsonData.asString());
            }
        });

        json.setSerializer(Liquid.class, new Serializer<>(){
            @Override
            public void write(Json json, Liquid object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public Liquid read(Json json, JsonValue jsonData, Class type){
                if(jsonData.asString() == null) return Liquids.water;
                Liquid i = Vars.content.getByName(ContentType.liquid, jsonData.asString());
                return i == null ? Liquids.water : i;
            }
        });

        json.setSerializer(Attribute.class, new Serializer<>(){
            @Override
            public void write(Json json, Attribute object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public Attribute read(Json json, JsonValue jsonData, Class type){
                return Attribute.get(jsonData.asString());
            }
        });

        json.setSerializer(Item.class, new Serializer<>(){
            @Override
            public void write(Json json, Item object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public Item read(Json json, JsonValue jsonData, Class type){
                if(jsonData.asString() == null) return Items.copper;
                Item i = Vars.content.getByName(ContentType.item, jsonData.asString());
                return i == null ? Items.copper : i;
            }
        });

        json.setSerializer(Team.class, new Serializer<>(){
            @Override
            public void write(Json json, Team object, Class knownType){
                json.writeValue(object.id);
            }

            @Override
            public Team read(Json json, JsonValue jsonData, Class type){
                return Team.get(jsonData.asInt());
            }
        });

        json.setSerializer(Block.class, new Serializer<>(){
            @Override
            public void write(Json json, Block object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public Block read(Json json, JsonValue jsonData, Class type){
                Block block = Vars.content.getByName(ContentType.block, jsonData.asString());
                if(block == null) block = Vars.content.getByName(ContentType.block, SaveVersion.fallback.get(jsonData.asString(), ""));
                return block == null ? Blocks.air : block;
            }
        });

        json.setSerializer(Planet.class, new Serializer<>(){
            @Override
            public void write(Json json, Planet object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public Planet read(Json json, JsonValue jsonData, Class type){
                if(jsonData.asString() == null){
                    return null;
                }
                Planet block = Vars.content.getByName(ContentType.planet, jsonData.asString());
                return block == null ? Planets.serpulo : block;
            }
        });

        json.setSerializer(Weather.class, new Serializer<>(){
            @Override
            public void write(Json json, Weather object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public Weather read(Json json, JsonValue jsonData, Class type){
                return Vars.content.getByName(ContentType.weather, jsonData.asString());
            }
        });

        json.setSerializer(UnitType.class, new Serializer<>(){
            @Override
            public void write(Json json, UnitType object, Class knownType){
                json.writeValue(object.name);
            }

            @Override
            public UnitType read(Json json, JsonValue jsonData, Class type){
                if(jsonData.asString() == null) return UnitTypes.dagger;
                UnitType u = Vars.content.getByName(ContentType.unit, jsonData.asString());
                return u == null ? UnitTypes.dagger : u;
            }
        });

        json.setSerializer(ItemStack.class, new Serializer<>(){
            @Override
            public void write(Json json, ItemStack object, Class knownType){
                json.writeObjectStart();
                json.writeValue("item", object.item);
                json.writeValue("amount", object.amount);
                json.writeObjectEnd();
            }

            @Override
            public ItemStack read(Json json, JsonValue jsonData, Class type){
                return new ItemStack(json.getSerializer(Item.class).read(json, jsonData.get("item"), Item.class), jsonData.getInt("amount"));
            }
        });

        json.setSerializer(UnlockableContent.class, new Serializer<>(){
            @Override
            public void write(Json json, UnlockableContent object, Class knownType){
                json.writeValue(object == null ? null : object.name);
            }

            @Override
            public UnlockableContent read(Json json, JsonValue jsonData, Class type){
                if(jsonData.isNull()) return null;
                String str = jsonData.asString();
                var map = Vars.content.byName(str);
                return map instanceof UnlockableContent u ? u : null;
            }
        });

        json.setSerializer(MapObjectives.class, new Serializer<>(){
            @Override
            public void write(Json json, MapObjectives exec, Class knownType){
                json.writeArrayStart();
                for(var obj : exec){
                    json.writeObjectStart(obj.getClass().isAnonymousClass() ? obj.getClass().getSuperclass() : obj.getClass(), null);
                    json.writeFields(obj);

                    json.writeArrayStart("parents");
                    for(var parent : obj.parents){
                        json.writeValue(exec.all.indexOf(parent));
                    }

                    json.writeArrayEnd();

                    json.writeValue("editorPos", Point2.pack(obj.editorX, obj.editorY));
                    json.writeObjectEnd();
                }

                json.writeArrayEnd();
            }

            @Override
            public MapObjectives read(Json json, JsonValue data, Class type){
                var exec = new MapObjectives();
                // First iteration to instantiate the objectives.
                for(var value = data.child; value != null; value = value.next){
                    //glenn why did you implement this in the least backwards compatible way possible
                    //the old objectives had lowercase class tags, now they're uppercase and either way I can't deserialize them without errors
                    if(value.has("class") && Character.isLowerCase(value.getString("class").charAt(0))){
                        return new MapObjectives();
                    }

                    MapObjective obj = json.readValue(MapObjective.class, value);

                    if(value.has("editorPos")){
                        int pos = value.getInt("editorPos");
                        obj.editorX = Point2.x(pos);
                        obj.editorY = Point2.y(pos);
                    }

                    exec.all.add(obj);
                    obj.validate();
                }

                // Second iteration to map the parents.
                int i = 0;
                for(var value = data.child; value != null; value = value.next, i++){
                    for(var parent = value.get("parents").child; parent != null; parent = parent.next){
                        int val = parent.asInt();
                        if(val >= 0 && val < exec.all.size){
                            exec.all.get(i).parents.add(exec.all.get(val));
                        }
                    }
                }

                return exec;
            }
        });

        json.setSerializer(ProductionNode.class, new Serializer<>(){
            @Override
            public void write(Json json, ProductionNode node, Class knownType){
                json.writeObjectStart();
                json.writeValue("blockName", node.blockName);
                json.writeValue("count", node.count);
                json.writeValue("powerProducePerSec", node.powerProducePerSec);
                json.writeValue("powerConsumePerSec", node.powerConsumePerSec);
                json.writeValue("measuredEfficiency", node.measuredEfficiency);
                json.writeValue("stopWhenOutputsFull", node.stopWhenOutputsFull);

                json.writeObjectStart("inputsPerSec");
                for(java.util.Map.Entry<String, Float> e : node.inputsPerSec.entrySet()){
                    json.writeValue(e.getKey(), e.getValue());
                }
                json.writeObjectEnd();

                json.writeObjectStart("outputsPerSec");
                for(java.util.Map.Entry<String, Float> e : node.outputsPerSec.entrySet()){
                    json.writeValue(e.getKey(), e.getValue());
                }
                json.writeObjectEnd();
                json.writeObjectEnd();
            }

            @Override
            public ProductionNode read(Json json, JsonValue data, Class type){
                if(data == null || data.isNull()) return null;
                ProductionNode node = new ProductionNode(data.getString("blockName", ""));
                node.count = data.getInt("count", 1);
                node.powerProducePerSec = data.getFloat("powerProducePerSec", 0f);
                node.powerConsumePerSec = data.getFloat("powerConsumePerSec", 0f);
                node.measuredEfficiency = data.getFloat("measuredEfficiency", 1f);
                node.stopWhenOutputsFull = data.getBoolean("stopWhenOutputsFull", true);

                JsonValue inputs = data.get("inputsPerSec");
                if(inputs != null){
                    for(JsonValue v = inputs.child; v != null; v = v.next){
                        if(v.isNumber()) node.inputsPerSec.put(v.name, v.asFloat());
                    }
                }
                JsonValue outputs = data.get("outputsPerSec");
                if(outputs != null){
                    for(JsonValue v = outputs.child; v != null; v = v.next){
                        if(v.isNumber()) node.outputsPerSec.put(v.name, v.asFloat());
                    }
                }
                return node;
            }
        });

        json.setSerializer(SectorStrategyProfile.class, new Serializer<>(){
            @Override
            public void write(Json json, SectorStrategyProfile p, Class knownType){
                json.writeObjectStart();
                json.writeValue("sectorId", p.sectorId);
                json.writeValue("hasBase", p.hasBase);
                json.writeValue("enemyBase", p.enemyBase);
                json.writeValue("underAttack", p.underAttack);
                json.writeValue("storageCapacity", p.storageCapacity);
                json.writeValue("defaultDestinationSectorId", p.defaultDestinationSectorId);

                json.writeObjectStart("storedItems");
                for(java.util.Map.Entry<String, Float> e : p.storedItems.entrySet()){
                    json.writeValue(e.getKey(), e.getValue());
                }
                json.writeObjectEnd();

                json.writeArrayStart("nodes");
                for(ProductionNode node : p.nodes){
                    json.writeValue(node, ProductionNode.class);
                }
                json.writeArrayEnd();

                json.writeObjectStart("exportCapacities");
                for(java.util.Map.Entry<String, Float> e : p.exportCapacities.entrySet()){
                    json.writeValue(e.getKey(), e.getValue());
                }
                json.writeObjectEnd();

                json.writeObjectStart("importDemands");
                for(java.util.Map.Entry<String, Float> e : p.importDemands.entrySet()){
                    json.writeValue(e.getKey(), e.getValue());
                }
                json.writeObjectEnd();

                json.writeArrayStart("adjacentSectorIds");
                for(Integer id : p.adjacentSectorIds){
                    json.writeValue(id);
                }
                json.writeArrayEnd();

                json.writeObjectStart("garrisonUnits");
                for(java.util.Map.Entry<String, Integer> e : p.garrisonUnits.entrySet()){
                    json.writeValue(e.getKey(), e.getValue());
                }
                json.writeObjectEnd();
                json.writeObjectEnd();
            }

            @Override
            public SectorStrategyProfile read(Json json, JsonValue data, Class type){
                if(data == null || data.isNull()) return null;
                SectorStrategyProfile p = new SectorStrategyProfile(data.getInt("sectorId", -1));
                p.hasBase = data.getBoolean("hasBase", false);
                p.enemyBase = data.getBoolean("enemyBase", false);
                p.underAttack = data.getBoolean("underAttack", false);
                p.storageCapacity = data.getInt("storageCapacity", 4000);
                p.defaultDestinationSectorId = data.getInt("defaultDestinationSectorId", -1);

                JsonValue stored = data.get("storedItems");
                if(stored != null){
                    for(JsonValue v = stored.child; v != null; v = v.next){
                        if(v.isNumber()) p.storedItems.put(v.name, v.asFloat());
                    }
                }
                JsonValue nodes = data.get("nodes");
                if(nodes != null){
                    for(JsonValue v = nodes.child; v != null; v = v.next){
                        ProductionNode node = json.readValue(ProductionNode.class, v);
                        if(node != null) p.nodes.add(node);
                    }
                }
                JsonValue exports = data.get("exportCapacities");
                if(exports != null){
                    for(JsonValue v = exports.child; v != null; v = v.next){
                        if(v.isNumber()) p.exportCapacities.put(v.name, v.asFloat());
                    }
                }
                JsonValue imports = data.get("importDemands");
                if(imports != null){
                    for(JsonValue v = imports.child; v != null; v = v.next){
                        if(v.isNumber()) p.importDemands.put(v.name, v.asFloat());
                    }
                }
                JsonValue adj = data.get("adjacentSectorIds");
                if(adj != null){
                    for(JsonValue v = adj.child; v != null; v = v.next){
                        if(v.isNumber()) p.adjacentSectorIds.add(v.asInt());
                    }
                }
                JsonValue garrison = data.get("garrisonUnits");
                if(garrison != null){
                    for(JsonValue v = garrison.child; v != null; v = v.next){
                        if(v.isNumber()) p.garrisonUnits.put(v.name, v.asInt());
                    }
                }
                return p;
            }
        });

        //use short names for all filter types
        for(var filter : Maps.allFilterTypes){
            var i = filter.get();
            json.addClassTag(Strings.camelize(i.getClass().getSimpleName().replace("Filter", "")), i.getClass());
        }
    }
}