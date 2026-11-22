package dev.deadstreets.paper;

import dev.deadstreets.core.DangerRating;
import dev.deadstreets.core.HordeAlertGate;
import dev.deadstreets.core.HordeArrivalPolicy;
import dev.deadstreets.core.HordeState;
import dev.deadstreets.core.ObservationGuard;
import dev.deadstreets.core.Temperament;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * R04 conservative horde lifecycle. Virtual groups never load chunks. Walkers are only
 * materialized in loaded chunks which no player is currently watching; once physical,
 * they remain ordinary persistent entities, including across chunk unloads.
 *
 * R04 does not virtualize existing physical entities. That deliberate restriction
 * avoids unsafe removal/duplication in front of players, at the cost of limited
 * long-range movement once a whole horde has materialized.
 */
final class HordeManager {
    private final DeadStreetsPlugin plugin;
    private final NamespacedKey hordeTag;
    private final SplittableRandom random = new SplittableRandom();
    private final ObservationGuard observation;
    private record ChunkKey(UUID worldId, int cx, int cz) {}
    private record Prefetch(long expiresAt) {}
    private final Map<ChunkKey,Prefetch> tickets = new HashMap<>();
    private final HashSet<ChunkKey> inFlight = new HashSet<>();
    private final Map<ChunkKey,Long> retryAfter = new HashMap<>();
    private long requestedChunks;
    private long rejectedUnobserved;
    private long movedWalkers;
    private final Map<UUID,HordeState> hordes = new HashMap<>();
    private final Map<UUID,Long> lastInterest = new HashMap<>();
    private long retiredVirtual;
    private final Map<UUID,HashSet<UUID>> loadedPhysical = new HashMap<>();
    private final Map<UUID,Long> nextAmbient = new HashMap<>();
    private final Map<UUID,Long> sprintSound = new HashMap<>();
    private HordeAlertGate alertGate;
    private BukkitTask tickTask;
    private long tickNumber;
    private long spawnedPhysical;
    private long blockedVisible;
    private long missingGround;
    private long soundsAccepted;
    private long warningsSent;
    private long budgetSecond = -1;
    private int remainingMaterializationBudget;

    HordeManager(DeadStreetsPlugin plugin) {
        this.plugin=plugin;
        this.observation=plugin.observation();
        this.hordeTag = new NamespacedKey(plugin,"horde_id");
    }
    boolean enabled() {return plugin.getConfig().getBoolean("hordes.enabled",false);}
    void start() {
        load();
        if(!enabled()) return;
        alertGate=new HordeAlertGate(Math.max(24,plugin.getConfig().getDouble("hordes.warning-distance",150)),
                Math.max(10,plugin.getConfig().getLong("hordes.warning-reset-seconds",240))*1000L);
        for(World w:Bukkit.getWorlds()) for(Chunk c:w.getLoadedChunks()) onEntitiesLoad(c);
        long now=System.currentTimeMillis();
        for(World w:Bukkit.getWorlds()) nextAmbient.put(w.getUID(),now + intervalMs());
        tickTask=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20,20);
        plugin.getLogger().warning("R06 experimental migrating hordes ON: unobserved chunk activation only; use a backup world.");
    }
    void stop() {
        if(tickTask!=null) tickTask.cancel();
        save();
        for(ChunkKey key : new ArrayList<>(tickets.keySet())) {
            World w=Bukkit.getWorld(key.worldId());
            if(w!=null) w.removePluginChunkTicket(key.cx(),key.cz(),plugin);
        }
        tickets.clear(); inFlight.clear(); retryAfter.clear();
        loadedPhysical.clear(); sprintSound.clear();
    }
    int count() {return hordes.size();}
    String status() {
        int virtual=0;for(HordeState h:hordes.values()) virtual+=h.pending();
        return "hordes="+hordes.size()+", virtual="+virtual+", physical-created="+spawnedPhysical
                +", viewed-skips="+blockedVisible+", ground-skips="+missingGround
                +", noise="+soundsAccepted+", warnings="+warningsSent
                +", lookahead-tickets="+tickets.size()+", lookahead-requests="+requestedChunks
                +", movement-goals="+movedWalkers+", visibility-rejections="+rejectedUnobserved
                +", retired-virtual="+retiredVirtual;
    }
    HordeState spawnManual(World world, double playerX, double playerZ, int size, Temperament temperament) {
        if(!enabled()) throw new IllegalStateException("Hordes are disabled in config.yml");
        if(size<1 || size>500) throw new IllegalArgumentException("Population must be 1–500");
        long now=System.currentTimeMillis();
        for(int attempt=0;attempt<40;attempt++) {
            double direction=random.nextDouble(0,Math.PI*2);
            double distance=random.nextDouble(320,480);
            double x=playerX+Math.cos(direction)*distance;
            double z=playerZ+Math.sin(direction)*distance;
            if(!candidateUnobserved(world,x,z)) continue;
            // An incoming migration, not a homing missile: offset trajectory by up to 60 degrees.
            HordeState h=new HordeState(UUID.randomUUID(),world.getUID(),size,temperament,
                    x,z,direction+Math.PI+random.nextDouble(-Math.PI/3,Math.PI/3),now);
            hordes.put(h.id(),h);lastInterest.put(h.id(),now);save();return h;
        }
        throw new IllegalStateException("Could not find a suitably distant, unobserved approach area");
    }
    private boolean candidateUnobserved(World world,double x,double z) {
        plugin.recordObservers(world,System.currentTimeMillis());
        if(observation.protectedPoint(world.getUID(),x,z,System.currentTimeMillis())) return false;
        for(Player p:world.getPlayers()) {
            double dx=x-p.getLocation().getX(),dz=z-p.getLocation().getZ();
            int threshold=Math.max(256,world.getViewDistance()*16+80);
            if(dx*dx+dz*dz<threshold*(double)threshold) return false;
        }
        return true;
    }
    private long intervalMs() {return 1000L*Math.max(30,plugin.getConfig().getLong("hordes.ambient-every-seconds",420));}
    private int availableBudget() {
        long second = System.currentTimeMillis()/1000L;
        if (second != budgetSecond) {
            budgetSecond = second;
            remainingMaterializationBudget = Math.max(1,Math.min(50,plugin.getConfig().getInt("hordes.max-materializations-per-second",8)));
        }
        return remainingMaterializationBudget;
    }
    private void tick() {
        tickNumber++;
        long now=System.currentTimeMillis();
        for(World w:Bukkit.getWorlds()) plugin.recordObservers(w,now);
        if(tickNumber%2==0) {
            observation.prune(now);
            cleanPrefetch(now);
        }
        for(World w:Bukkit.getWorlds()) {
            long next=nextAmbient.getOrDefault(w.getUID(),now+intervalMs());
            if(now>=next) {
                nextAmbient.put(w.getUID(),now+intervalMs());
                if(plugin.getConfig().getBoolean("hordes.ambient-creation",true) && !w.getPlayers().isEmpty()
                        && countWorld(w)<Math.max(1,plugin.getConfig().getInt("hordes.max-groups-per-world",12))) {
                    List<Player> players=w.getPlayers();
                    Player p=players.get(random.nextInt(players.size()));
                    int lo=Math.max(1,plugin.getConfig().getInt("hordes.min-population",24));
                    int hi=Math.max(lo,plugin.getConfig().getInt("hordes.max-population",100));
                    try {spawnManual(w,p.getLocation().getX(),p.getLocation().getZ(),random.nextInt(lo,Math.min(500,hi)+1),
                            Temperament.values()[random.nextInt(Temperament.values().length)]);
                    } catch(IllegalStateException skip) { /* no safe approach area */ }
                }
            }
        }
        double speed=Math.max(0,Math.min(2.0,plugin.getConfig().getDouble("hordes.virtual-speed-blocks-per-second",0.75)));
        long retireAfter=1000L*Math.max(600,plugin.getConfig().getLong("hordes.virtual-retire-after-seconds",1800));
        int budget=availableBudget();
        for(HordeState h:new ArrayList<>(hordes.values())) {
            World w=Bukkit.getWorld(h.worldId());
            if(w==null) continue;
            if(nearSomePlayer(w,h.x(),h.z(),512))lastInterest.put(h.id(),now);
            else if(h.pending()>0 && now-lastInterest.getOrDefault(h.id(),now)>=retireAfter
                    && !observation.protectedPoint(w.getUID(),h.x(),h.z(),now)) {
                h.retirePending();retiredVirtual++;
                lastInterest.remove(h.id());
                if(h.empty()){hordes.remove(h.id());loadedPhysical.remove(h.id());}
                save();continue;
            }
            // Do not advance invisible walkers into an observed area. Wait for a safe
            // chunk activation; an already-physical portion can keep walking naturally.
            // Advance virtual groups toward the edge of the shared observation buffer.
            // They may not jump into observed space; physical members cross that edge on foot.
            double direction=h.desiredHeading(now);
            double step=Math.max(0,Math.min(2.0,speed))*1.0;
            double nx=h.x()+Math.cos(direction)*step,nz=h.z()+Math.sin(direction)*step;
            if(HordeArrivalPolicy.advanceVirtual(observation.protectedPoint(w.getUID(),nx,nz,now))) h.advance(now,speed);
            boolean nearBoundary=observation.protectedPoint(w.getUID(),
                h.x()+Math.cos(h.desiredHeading(now))*24,
                h.z()+Math.sin(h.desiredHeading(now))*24,now);
            if(h.pending()>0 && budget>0 && nearSomePlayer(w,h.x(),h.z(),HordeArrivalPolicy.MATERIALIZATION_NEAR_PLAYER)
                    && nearBoundary) {
                // For chunks already loaded, don't spawn inside any player's observation buffer.
                // Newly loading chunks have their own, earlier activation hook below.
                int cx=(int)Math.floor(h.x())>>4,cz=(int)Math.floor(h.z())>>4;
                for(int dx=-2;dx<=2 && budget>0 && h.pending()>0;dx++)
                    for(int dz=-2;dz<=2 && budget>0 && h.pending()>0;dz++) {
                        if(!w.isChunkLoaded(cx+dx,cz+dz)) continue;
                        Chunk c=w.getChunkAt(cx+dx,cz+dz);
                        if(!safeToMaterialize(c,false)) {blockedVisible++;continue;}
                        budget-=materialize(h,c,Math.min(3,budget),false);
                    }
            }
            for(Player p:w.getPlayers()) {
                double dx=p.getLocation().getX()-h.x(),dz=p.getLocation().getZ()-h.z();
                double dist=Math.sqrt(dx*dx+dz*dz);
                // Once physical walkers walk away from their original anchor, report their
                // actual proximity, not only the parked virtual horde position.
                var tracked=loadedPhysical.get(h.id());
                if(tracked!=null) for(UUID member:tracked) {
                    Entity live=Bukkit.getEntity(member);
                    if(live instanceof Zombie zombie && zombie.isValid()
                            && zombie.getWorld().getUID().equals(w.getUID())) {
                        Location loc=zombie.getLocation();
                        dist=Math.min(dist,Math.hypot(p.getLocation().getX()-loc.getX(),
                                p.getLocation().getZ()-loc.getZ()));
                    }
                }
                // Shared horde, one alert per nearby player. Gate also ensures closing distance.
                if(h.population()>0 && alertGate.observe(p.getUniqueId(),h.id(),dist,now)) {
                    plugin.showWarning(p,DangerRating.calculate(h.population(),h.temperament()));
                    warningsSent++;
                }
            }
        }
        if(tickNumber%4==0) steerPhysical(now);
        prefetchApproaches(now);
        if(tickNumber%30==0) {alertGate.prune(now);save();}
    }
    // Only virtual groups consume ambient migration slots. Fully-materialized
    // groups remain tracked for deaths/warnings but no longer block new migrations.
    private int countWorld(World w) {int n=0;for(HordeState h:hordes.values())
        if(h.worldId().equals(w.getUID()) && h.pending()>0)n++;return n;}
    private boolean nearSomePlayer(World w,double x,double z,double radius) {
        for(Player p:w.getPlayers()) {double dx=p.getLocation().getX()-x,dz=p.getLocation().getZ()-z;
            if(dx*dx+dz*dz<=radius*radius) return true;}
        return false;
    }
    void onChunkLoad(Chunk chunk) {
        if(!enabled() || !chunk.isEntitiesLoaded()) return;
        if(!safeToMaterialize(chunk,true)) return;
        World w=chunk.getWorld();
        int budget=Math.min(availableBudget(),Math.max(1,Math.min(8,plugin.getConfig().getInt("hordes.max-materializations-per-chunk",4))));
        for(HordeState h:new ArrayList<>(hordes.values())) {
            if(budget<=0)break;
            if(!h.worldId().equals(w.getUID()) || h.pending()<=0)continue;
            double dx=h.x()-(chunk.getX()*16+8.0),dz=h.z()-(chunk.getZ()*16+8.0);
            if(dx*dx+dz*dz>48*48)continue;
            if(!nearSomePlayer(w,h.x(),h.z(),HordeArrivalPolicy.MATERIALIZATION_NEAR_PLAYER)) continue;
            double angle=h.desiredHeading(System.currentTimeMillis());
            if(!observation.protectedPoint(w.getUID(),h.x()+Math.cos(angle)*24,
                    h.z()+Math.sin(angle)*24,System.currentTimeMillis()))continue;
            budget-=materialize(h,chunk,Math.min(3,budget),true);
        }
    }
    private void recordObservers(World w,long now) {plugin.recordObservers(w,now);}
    void observeTeleport(Player player,Location destination) {
        if(!enabled() || destination==null || destination.getWorld()==null)return;
        plugin.observeTeleport(player,destination);
    }
    private boolean safeToMaterialize(Chunk chunk,boolean fromLoad) {
        if(!chunk.isEntitiesLoaded() || !chunk.getPlayersSeeingChunk().isEmpty()) return false;
        World w=chunk.getWorld();
        long now=System.currentTimeMillis();
        plugin.recordObservers(w,now);
        if(!plugin.isUnobserved(chunk)) {
            rejectedUnobserved++;
            return false;
        }
        // Both naturally loaded and prepared chunks use the SAME conservative rule.
        // A chunk load callback alone does not prove that all players have not seen it.
        return true;
    }

    /**
     * Bounded corridor loading is distinct from zombie MATERIALIZATION. Loading an
     * existing chunk in a player's observation zone is fine; spawning there isn't.
     * This bridges the 64-block safety buffer between the natural player view
     * radius and the original exterior horde activation point.
     */
    private void prefetchApproaches(long now) {
        if(!plugin.getConfig().getBoolean("hordes.prefetch.enabled",true))return;
        int maxTickets=Math.max(0,Math.min(32,plugin.getConfig().getInt("hordes.prefetch.max-tickets",8)));
        int perTick=Math.max(0,Math.min(4,plugin.getConfig().getInt("hordes.prefetch.requests-per-second",2)));
        if(maxTickets==0 || perTick==0)return;
        int sent=0;
        for(HordeState h:hordes.values()) {
            if(sent>=perTick || tickets.size()+inFlight.size()>=maxTickets)break;
            World w=Bukkit.getWorld(h.worldId());
            if(w==null || !nearSomePlayer(w,h.x(),h.z(),1024))continue;
            double ax=h.x(),az=h.z();
            HashSet<UUID> physical=loadedPhysical.get(h.id());
            if(physical!=null)for(UUID id:physical) {
                Entity e=Bukkit.getEntity(id);
                if(e instanceof Zombie z && z.isValid()) {ax=z.getLocation().getX();az=z.getLocation().getZ();break;}
            }
            double heading=h.desiredHeading(now);
            // Load a short corridor through the buffer toward the horde's direction.
            // Real zombies remain saved physical entities. No virtual entity is moved here.
            for(int step=0;step<=7;step++) {
                if(sent>=perTick || tickets.size()+inFlight.size()>=maxTickets)break;
                int cx=(int)Math.floor(ax+Math.cos(heading)*step*16)>>4;
                int cz=(int)Math.floor(az+Math.sin(heading)*step*16)>>4;
                ChunkKey key=new ChunkKey(w.getUID(),cx,cz);
                if(tickets.containsKey(key) || inFlight.contains(key)
                        || now<retryAfter.getOrDefault(key,0L))continue;
                inFlight.add(key);sent++;requestedChunks++;
                if(w.isChunkLoaded(cx,cz)) {
                    finishPrefetch(key,w.getChunkAt(cx,cz));
                } else {
                    try {
                        w.getChunkAtAsync(cx,cz,false,chunk -> {
                            Runnable complete = () -> finishPrefetch(key,chunk);
                            if(Bukkit.isPrimaryThread())complete.run();
                            else Bukkit.getScheduler().runTask(plugin,complete);
                        });
                    } catch(RuntimeException ex) {
                        inFlight.remove(key);retryAfter.put(key,now+60_000L);
                        plugin.getLogger().warning("Horde look-ahead failed: "+ex.getMessage());
                    }
                }
            }
        }
    }
    private void finishPrefetch(ChunkKey key,Chunk chunk) {
        inFlight.remove(key);
        if(!enabled() || chunk==null) {retryAfter.put(key,System.currentTimeMillis()+60_000L);return;}
        if(tickets.containsKey(key))return;
        int max=Math.max(0,Math.min(32,plugin.getConfig().getInt("hordes.prefetch.max-tickets",8)));
        if(tickets.size()>=max) {
            retryAfter.put(key,System.currentTimeMillis()+10_000L);return;
        }
        // Admission must never depend on this prefetch: the shared guard is
        // evaluated separately in materialize().
        if(!chunk.addPluginChunkTicket(plugin))return;
        int seconds=Math.max(5,Math.min(90,plugin.getConfig().getInt("hordes.prefetch.ticket-seconds",30)));
        tickets.put(key,new Prefetch(System.currentTimeMillis()+seconds*1000L));
        if(chunk.isEntitiesLoaded())onChunkLoad(chunk);
    }
    private void cleanPrefetch(long now) {
        for(Iterator<Map.Entry<ChunkKey,Prefetch>> it=tickets.entrySet().iterator();it.hasNext();) {
            var entry=it.next();
            if(entry.getValue().expiresAt()>now)continue;
            ChunkKey key=entry.getKey();World w=Bukkit.getWorld(key.worldId());
            if(w!=null)w.removePluginChunkTicket(key.cx(),key.cz(),plugin);
            it.remove();
        }
        retryAfter.entrySet().removeIf(e->e.getValue()<now);
    }

    /** Local steering only: the vanilla pathfinder handles obstacles. The plugin never
     * makes a virtual horde chase a player or force-loads a path through wilderness. */
    private void steerPhysical(long now) {
        int budget=Math.max(0,Math.min(80,plugin.getConfig().getInt("hordes.max-navigation-goals-per-4s",24)));
        for(HordeState h:hordes.values()) {
            if(budget<=0)break;
            World w=Bukkit.getWorld(h.worldId());
            if(w==null)continue;
            HashSet<UUID> memberSet=loadedPhysical.get(h.id());
            if(memberSet==null || memberSet.isEmpty())continue;
            var members=new ArrayList<>(memberSet);
            int offset=(int)(tickNumber*7%members.size());
            for(int i=0;i<members.size() && budget>0;i++) {
                Entity entity=Bukkit.getEntity(members.get((i+offset)%members.size()));
                if(!(entity instanceof Zombie zombie) || !zombie.isValid() || zombie.getTarget()!=null)continue;
                var loc=zombie.getLocation();
                if(!nearSomePlayer(w,loc.getX(),loc.getZ(),HordeArrivalPolicy.STEERING_NEAR_PLAYER))continue;
                double angle=h.desiredHeading(now);
                double tx=loc.getX()+Math.cos(angle)*13,tz=loc.getZ()+Math.sin(angle)*13;
                int bx=(int)Math.floor(tx),bz=(int)Math.floor(tz);
                if(!w.isChunkLoaded(bx>>4,bz>>4))continue;
                int ground=w.getHighestBlockYAt(bx,bz)+1;
                if(Math.abs(ground-loc.getBlockY())>3)continue;
                if(!w.getBlockAt(bx,ground,bz).isPassable() || !w.getBlockAt(bx,ground+1,bz).isPassable()
                    || !w.getBlockAt(bx,ground-1,bz).getType().isSolid())continue;
                zombie.getPathfinder().moveTo(new Location(w,bx+0.5,ground,bz+0.5),1.0);
                movedWalkers++;budget--;
            }
        }
    }

    private int materialize(HordeState h,Chunk c,int cap,boolean fromLoad) {
        int made=0;
        if (availableBudget()<=0 || !safeToMaterialize(c,fromLoad)) return 0;
        World w=c.getWorld();
        int count=Math.min(Math.min(cap,h.pending()),5);
        for(int i=0;i<count;i++) {
            boolean success=false;
            for(int attempt=0;attempt<12;attempt++) {
                int x=c.getX()*16+random.nextInt(16),z=c.getZ()*16+random.nextInt(16);
                int y=w.getHighestBlockYAt(x,z)+1;
                if(y<=w.getMinHeight()+1 || y>=w.getMaxHeight()-2) continue;
                Block feet=w.getBlockAt(x,y,z),head=w.getBlockAt(x,y+1,z),floor=w.getBlockAt(x,y-1,z);
                if(!feet.isPassable() || !head.isPassable() || feet.isLiquid() || head.isLiquid()
                    || !floor.getType().isSolid() || floor.getType()==Material.MAGMA_BLOCK)continue;
                if(!safeToMaterialize(c,fromLoad)) return made;
                if(!plugin.budget().reserve())return made;
                // Write-ahead debit: if the process dies while Paper writes its entity region,
                // the ordinal remains consumed rather than resurrecting a duplicate walker.
                h.materialized();
                if(!save())return made;
                try {
                    Zombie zombie=w.spawn(new Location(w,x+0.5,y,z+0.5),Zombie.class,created -> {
                        plugin.markWalker(created);
                        plugin.budget().tag(created);
                        created.getPersistentDataContainer().set(hordeTag,PersistentDataType.STRING,h.id().toString());
                    });
                    loadedPhysical.computeIfAbsent(h.id(),ignore->new HashSet<>()).add(zombie.getUniqueId());
                    made++;spawnedPhysical++;remainingMaterializationBudget--;success=true;
                } catch(RuntimeException ex) {
                    // Fail closed if a Java-side error occurs after the entity may have been
                    // created: a lost slot is safer than a second zombie for the same ordinal.
                    plugin.getLogger().warning("Horde spawn admission consumed, entity creation uncertain: "+ex.getMessage());
                }
                if(success)break;
            }
            if(!success) {missingGround++;break;}
        }
        if(made>0) save(); // record decrement promptly; physical entity persistence is managed by Paper
        return made;
    }
    void onEntitiesLoad(Chunk chunk) {
        if(!enabled())return;
        for(Entity e:chunk.getEntities()) {
            if(!(e instanceof Zombie z))continue;
            String id=z.getPersistentDataContainer().get(hordeTag,PersistentDataType.STRING);
            if(id==null)continue;
            try {UUID uuid=UUID.fromString(id);
                if(hordes.containsKey(uuid))loadedPhysical.computeIfAbsent(uuid,k->new HashSet<>()).add(z.getUniqueId());
            } catch(IllegalArgumentException ignored){}
        }
    }
    void onEntitiesUnload(Chunk chunk) {
        if(!enabled())return;
        for(Entity e:chunk.getEntities()) {
            if(!(e instanceof Zombie z)) continue;
            String id=z.getPersistentDataContainer().get(hordeTag,PersistentDataType.STRING);
            if(id==null)continue;
            try {UUID uuid=UUID.fromString(id);HashSet<UUID> set=loadedPhysical.get(uuid);
                if(set!=null)set.remove(z.getUniqueId());
            } catch(IllegalArgumentException ignored){}
        }
    }
    void onZombieDeath(Zombie z) {
        String id=z.getPersistentDataContainer().get(hordeTag,PersistentDataType.STRING);
        if(id==null)return;
        try {
            UUID uuid=UUID.fromString(id);
            HordeState h=hordes.get(uuid);
            if(h!=null) {h.died(); if(h.empty()){hordes.remove(uuid);loadedPhysical.remove(uuid);lastInterest.remove(uuid);}save();}
            var set=loadedPhysical.get(uuid);if(set!=null)set.remove(z.getUniqueId());
        }catch(IllegalArgumentException ignored){}
    }
    void forgetPlayer(UUID id) {if(alertGate!=null) alertGate.forget(id);sprintSound.remove(id);}
    boolean sprintSoundDue(UUID id,long now) {
        if(!enabled())return false;
        long prev=sprintSound.getOrDefault(id,0L);
        if(now-prev<2250)return false;
        sprintSound.put(id,now);return true;
    }
    void sound(World w,double x,double z,double loudness) {
        if(!enabled())return;
        long now=System.currentTimeMillis();
        int navigationBudget=32;
        for(HordeState h:hordes.values()) {
            if(!h.worldId().equals(w.getUID()))continue;
            if(h.hear(x,z,loudness,now)) soundsAccepted++;
            // Actual walkers use their own hearing range; the horde's advertised
            // temperament is the shared sensitivity, not a speed or damage modifier.
            var tracked=loadedPhysical.get(h.id());
            if(tracked==null || tracked.isEmpty())continue;
            for(UUID entityId:tracked) {
                if(navigationBudget<=0)break;
                Entity entity=Bukkit.getEntity(entityId);
                if(!(entity instanceof Zombie zombie) || !zombie.isValid() || zombie.getTarget()!=null)continue;
                Location at=zombie.getLocation();
                double dx=x-at.getX(),dz=z-at.getZ();
                double radius=h.temperament().baseHearingRange()*Math.min(3,loudness);
                if(dx*dx+dz*dz<=radius*radius && dx*dx+dz*dz>9) {
                    // Pathfind in already-loaded local terrain only; do not route virtual entities.
                    if(dx*dx+dz*dz<64*64) {
                        zombie.getPathfinder().moveTo(new Location(w,x,at.getY(),z),1.0);
                        navigationBudget--;
                    }
                }
            }
        }
    }
    private Path stateFile() {return plugin.getDataFolder().toPath().resolve("hordes-v1.txt");}
    private void load() {
        Path p=stateFile();if(!Files.isRegularFile(p))return;
        try {
            int n=0;
            for(String line:Files.readAllLines(p,StandardCharsets.UTF_8)) {
                if(line.isBlank() || line.startsWith("#"))continue;
                if(++n>100_000)throw new IOException("Horde state exceeds record limit");
                try {HordeState h=HordeState.restore(line);hordes.putIfAbsent(h.id(),h);
                    lastInterest.put(h.id(),System.currentTimeMillis());}
                catch(RuntimeException bad){plugin.getLogger().warning("Skipping malformed horde state record");}
            }
        } catch(IOException error) {plugin.getLogger().severe("Cannot read horde state: "+error.getMessage());}
    }
    private boolean save() {
        try {
            Path file=stateFile();Files.createDirectories(file.getParent());
            Path tmp=file.resolveSibling("hordes-v1.txt.tmp");
            List<String> lines=new ArrayList<>();lines.add("# Dead Streets horde population state v1");
            for(HordeState h:hordes.values()) if(!h.empty())lines.add(h.save());
            Files.write(tmp,lines,StandardCharsets.UTF_8);
            try {Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(java.nio.file.AtomicMoveNotSupportedException ex) {Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
            return true;
        }catch(IOException ex){plugin.getLogger().severe("Could not save horde state: "+ex.getMessage());return false;}
    }
}
