package com.tndmadman.rts;
import java.util.LinkedHashMap;
import java.util.Map;
/** Optional solo manifest extension, backward compatible with archive format 6. */
final class GalaxyGenerationSettingsCodec {
    private GalaxyGenerationSettingsCodec() { }
    static Map<String,Object> capture(GalaxyGenerationSettings s) {
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("procedural", s.procedural());
        out.put("targetSystemCount", s.targetSystemCount());
        out.put("topologyStyle", s.topologyStyle().name());
        out.put("permanentConnectivityDensity", s.permanentConnectivityDensity());
        out.put("frontierFrequency", s.frontierFrequency());
        out.put("resourceRichness", s.resourceRichness());
        out.put("rareResourceFrequency", s.rareResourceFrequency());
        out.put("hazardFrequency", s.hazardFrequency());
        out.put("npcDensity", s.npcDensity());
        out.put("startingSeparation", s.startingSeparation());
        out.put("templateWeights", new LinkedHashMap<>(s.templateWeights()));
        return out;
    }
    static GalaxyGenerationSettings restore(Object value, int copies) {
        Map<String,Object> raw = ServerSaveStore.object(value);
        if (raw.isEmpty()) return GalaxyGenerationSettings.legacy(copies);
        Map<String,Double> weights = new LinkedHashMap<>();
        for (Map.Entry<String,Object> entry : ServerSaveStore.object(raw.get("templateWeights")).entrySet()) {
            if (!(entry.getValue() instanceof Number number)) throw new IllegalArgumentException("Invalid galaxy template weight.");
            weights.put(entry.getKey(), number.doubleValue());
        }
        return new GalaxyGenerationSettings(
            ServerSaveStore.boolValue(raw,"procedural",false),
            ServerSaveStore.intValue(raw,"targetSystemCount",GalaxyGenerationSettings.legacy(copies).targetSystemCount()),
            ServerSaveStore.enumValue(GalaxyTopologyStyle.class,raw.get("topologyStyle"),GalaxyTopologyStyle.MIXED),
            ServerSaveStore.doubleValue(raw,"permanentConnectivityDensity",0.20),
            ServerSaveStore.doubleValue(raw,"frontierFrequency",0.0),
            ServerSaveStore.doubleValue(raw,"resourceRichness",1.0),
            ServerSaveStore.doubleValue(raw,"rareResourceFrequency",0.15),
            ServerSaveStore.doubleValue(raw,"hazardFrequency",0.15),
            ServerSaveStore.doubleValue(raw,"npcDensity",0.5),
            ServerSaveStore.intValue(raw,"startingSeparation",2),
            weights);
    }
}
