package dev.deadstreets.core;

import java.util.Locale;
import java.util.UUID;

/** One shared population/position record. Not a record for each zombie. All mutations are server-thread only. */
public final class HordeState {
    private final UUID id;
    private final UUID worldId;
    private final Temperament temperament;
    private int population;
    private int pending;
    private double x;
    private double z;
    private double heading;
    private long updatedAt;
    private Double soundX;
    private Double soundZ;
    private long soundExpires;

    public HordeState(UUID id, UUID worldId, int population, Temperament temperament,
                      double x, double z, double heading, long updatedAt) {
        if (id == null || worldId == null || temperament == null || population <= 0
                || !Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(heading))
            throw new IllegalArgumentException("Invalid horde");
        this.id = id; this.worldId = worldId; this.population = this.pending = population;
        this.temperament = temperament; this.x = x; this.z = z;
        this.heading = heading; this.updatedAt = updatedAt;
    }
    public UUID id() { return id; }
    public UUID worldId() { return worldId; }
    public int population() { return population; }
    public int pending() { return pending; }
    public Temperament temperament() { return temperament; }
    public double x() { return x; }
    public double z() { return z; }
    public double heading() { return heading; }
    public long updatedAt() { return updatedAt; }
    public boolean hasInterest(long now) { return soundX != null && now < soundExpires; }
    public boolean hasPhysicalWalkers() { return population > pending; }
    public double desiredHeading(long now) {
        return hasInterest(now) ? Math.atan2(soundZ - z, soundX - x) : heading;
    }

    public boolean hear(double originX, double originZ, double loudness, long now) {
        if (!Double.isFinite(originX) || !Double.isFinite(originZ) || !Double.isFinite(loudness)) return false;
        if (loudness <= 0) return false;
        double radius = temperament.baseHearingRange() * Math.min(3.0, loudness);
        double dx = originX-x, dz = originZ-z;
        if (dx*dx + dz*dz > radius*radius) return false;
        soundX = originX; soundZ = originZ;
        soundExpires = now + 45_000L;
        return true;
    }

    /** Advance in unloaded terrain without force-loading or accumulating hours of offline travel. */
    public void advance(long now, double blocksPerSecond) {
        if (now <= updatedAt) return;
        double dt = Math.min(5.0, (now-updatedAt)/1000.0);
        updatedAt = now;
        // The virtual remainder continues migrating after partial materialization; physical walkers are tracked independently.
        if (pending <= 0 || dt <= 0 || blocksPerSecond <= 0) return;
        double direction = heading;
        if (hasInterest(now)) direction = Math.atan2(soundZ-z,soundX-x);
        else { soundX=null; soundZ=null; }
        double distance = blocksPerSecond * dt;
        if (hasInterest(now)) distance = Math.min(distance, Math.hypot(soundX-x,soundZ-z));
        x += Math.cos(direction)*distance;
        z += Math.sin(direction)*distance;
        heading = direction;
    }
    public void turn(double radians) {if (Double.isFinite(radians)) heading = radians;}
    /** Called only after a successfully spawned physical walker, never for a failed attempt. */
    public void materialized() {if (pending <= 0) throw new IllegalStateException("No pending walkers");pending--;}
    public void died() {if (population > 0) population--;}
    /** Retire unmaterialized walkers only when a group has been completely absent
     * from all active gameplay for a long time; physical entities are untouched. */
    public void retirePending() {
        population -= pending;
        pending = 0;
    }
    public boolean empty() {return population <= 0;}
    public String save() {
        return id+"|"+worldId+"|"+population+"|"+pending+"|"+temperament.name()+"|"
            +x+"|"+z+"|"+heading+"|"+updatedAt+"|"
            +(soundX==null?"":soundX)+"|"+(soundZ==null?"":soundZ)+"|"+soundExpires;
    }
    public static HordeState restore(String line) {
        String[] s=line.split("\\|",-1);
        if (s.length!=12) throw new IllegalArgumentException("Malformed record");
        HordeState h=new HordeState(UUID.fromString(s[0]),UUID.fromString(s[1]),
            Integer.parseInt(s[2]),Temperament.parse(s[4]),Double.parseDouble(s[5]),
            Double.parseDouble(s[6]),Double.parseDouble(s[7]),Long.parseLong(s[8]));
        int remaining=Integer.parseInt(s[3]);
        if (remaining<0 || remaining>h.population) throw new IllegalArgumentException("Malformed pending count");
        h.pending=remaining;
        if (!s[9].isBlank() && !s[10].isBlank()) {
            h.soundX=Double.parseDouble(s[9]);h.soundZ=Double.parseDouble(s[10]);
            if (!Double.isFinite(h.soundX) || !Double.isFinite(h.soundZ)) throw new IllegalArgumentException("Invalid noise");
        }
        h.soundExpires=Long.parseLong(s[11]);
        return h;
    }
}
