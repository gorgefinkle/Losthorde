package dev.deadstreets.core;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One warning per shared player/horde encounter. Only triggers if distance is closing.
 * This is model logic only in R01; real migrating hordes are not enabled yet.
 */
public final class HordeAlertGate {
    private record Key(UUID player,UUID horde) {}
    private record Visit(double lastDistance,long lastSeen,long warnedAt,long leftAt) {}
    private final Map<Key,Visit> history = new HashMap<>();
    private final double announceRange;
    private final long resetMillis;
    public HordeAlertGate(double announceRange,long resetMillis) {
        if(announceRange<=0 || resetMillis<0) throw new IllegalArgumentException();
        this.announceRange=announceRange;this.resetMillis=resetMillis;
    }
    public boolean observe(UUID player,UUID horde,double distance,long nowMillis) {
        if(distance<0) throw new IllegalArgumentException("negative distance");
        Key key=new Key(player,horde);
        Visit prior=history.get(key);
        boolean inside=distance<=announceRange;
        boolean approaching=prior!=null && distance < prior.lastDistance-0.15;
        long leftAt=prior==null?-1:prior.leftAt;
        if(!inside && (prior==null || prior.lastDistance<=announceRange)) leftAt=nowMillis;
        boolean rearmed=prior==null || prior.warnedAt<0 || (leftAt>=0 && nowMillis-leftAt>=resetMillis);
        boolean notify=inside && approaching && rearmed;
        history.put(key,new Visit(distance,nowMillis,notify?nowMillis:(prior==null?-1:prior.warnedAt),notify?-1:leftAt));
        return notify;
    }
    public void forget(UUID player) {history.keySet().removeIf(k -> k.player.equals(player));}
    public void prune(long nowMillis) {history.entrySet().removeIf(e -> nowMillis-e.getValue().lastSeen>600_000L);}
}
