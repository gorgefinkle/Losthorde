package dev.deadstreets.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.deadstreets.core.MetropolisFlag;

import java.util.ArrayList;
import java.util.List;

/** Strict schema-v1 JSONL adapter for the optional Metropolis BUILD export. */
final class MetropolisFlagJson {
    private MetropolisFlagJson() {}

    static MetropolisFlag parse(String line) {
        JsonElement parsed=JsonParser.parseString(line);
        if(!parsed.isJsonObject())throw new IllegalArgumentException("Expected JSON object");
        JsonObject root=parsed.getAsJsonObject();
        if(requiredInt(root,"schemaVersion")!=1)throw new IllegalArgumentException("Unsupported schema version");
        String worldId=requiredString(root,"worldId");
        long worldSeed=requiredLong(root,"worldSeed");
        String buildingId=requiredString(root,"buildingId");
        String districtType=requiredString(root,"districtType");
        JsonObject footprint=requiredObject(root,"footprint");
        int minX=requiredInt(footprint,"minX"),maxX=requiredInt(footprint,"maxX");
        int minY=requiredInt(footprint,"minY"),maxY=requiredInt(footprint,"maxY");
        int minZ=requiredInt(footprint,"minZ"),maxZ=requiredInt(footprint,"maxZ");
        List<MetropolisFlag.SpawnPoint> points=new ArrayList<>();
        boolean provided=root.has("interiorCandidates")&&root.get("interiorCandidates").isJsonArray();
        if(provided){
            JsonArray candidates=root.getAsJsonArray("interiorCandidates");
            java.util.HashSet<String> distinct=new java.util.HashSet<>();
            if(candidates.size()>64)throw new IllegalArgumentException("Too many interior candidates");
            for(JsonElement element:candidates){
                if(!element.isJsonObject())throw new IllegalArgumentException("Malformed interior candidate");
                JsonObject candidate=element.getAsJsonObject();
                int x=requiredInt(candidate,"x"),y=requiredInt(candidate,"y"),z=requiredInt(candidate,"z");
                if(!distinct.add(x+":"+y+":"+z))throw new IllegalArgumentException("Duplicate interior candidate");
                points.add(new MetropolisFlag.SpawnPoint(x,y,z));
            }
        }
        return new MetropolisFlag(worldId,worldSeed,buildingId,districtType,
                minX,minY,minZ,maxX,maxY,maxZ,points,provided);
    }

    private static JsonObject requiredObject(JsonObject object,String key) {
        JsonElement value=object.get(key);
        if(value==null||!value.isJsonObject())throw new IllegalArgumentException("Missing object "+key);
        return value.getAsJsonObject();
    }
    private static String requiredString(JsonObject object,String key) {
        JsonElement value=object.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Missing string "+key);
        String result=value.getAsString();
        if(result.isBlank())throw new IllegalArgumentException("Blank string "+key);
        return result;
    }
    private static int requiredInt(JsonObject object,String key) {
        long value=requiredLong(object,key);
        if(value<Integer.MIN_VALUE||value>Integer.MAX_VALUE)throw new IllegalArgumentException("Integer out of range "+key);
        return (int)value;
    }
    private static long requiredLong(JsonObject object,String key) {
        JsonElement value=object.get(key);
        if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Missing number "+key);
        try { return Long.parseLong(value.getAsString()); }
        catch(NumberFormatException malformed) { throw new IllegalArgumentException("Expected an integer "+key,malformed); }
    }
}
