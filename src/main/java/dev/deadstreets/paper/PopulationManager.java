package dev.deadstreets.paper;

import dev.deadstreets.core.MetropolisFlag;
import dev.deadstreets.core.PopulationPlan;
import dev.deadstreets.core.PopulationRoller;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Opt-in, fail-closed v1 Metropolis flag consumer. Chunk PDC records which build-slices
 * were attempted so a reload cannot repeatedly manufacture walkers. Never force-loads chunks.
 *
 * IMPORTANT: ChunkLoadEvent timing relative to client delivery must be proven on the target
 * Paper build. Disabled by default until the in-game no-pop-in and stress gates pass.
 */
final class PopulationManager {
    private record ChunkRef(UUID worldId, int x, int z) {}
    private record Snapshot(long size, long mtime, long checkedMillis, List<MetropolisFlag> flags) {}

    private final DeadStreetsPlugin plugin;
    private final PopulationJournal journal;
    private final ArrayDeque<ChunkRef> pending = new ArrayDeque<>();
    private final HashSet<ChunkRef> pendingSet = new HashSet<>();
    private final LinkedHashMap<ChunkRef,Long> lateRetry = new LinkedHashMap<>();
    private int retryTick;
    private final LinkedHashMap<Path,Snapshot> cache = new LinkedHashMap<>(64,0.75f,true);
    private BukkitTask task;
    private int remainingBudget;
    private int lastTick = -1;
    private long processedSlices;
    private long createdZombies;
    private long skippedLocations;
    private long invalidFlags;
    private long deferredObserved;
    private long droppedQueue;

    PopulationManager(DeadStreetsPlugin plugin) { this.plugin = plugin;this.journal=new PopulationJournal(plugin); }

    boolean enabled() { return plugin.getConfig().getBoolean("population.activation.enabled",false); }

    void start() {
        if (!enabled()) return;
        journal.load();
        // Startup scan is bounded per tick. It does not load new chunks.
        for (World w : Bukkit.getWorlds()) for (Chunk chunk : w.getLoadedChunks()) enqueue(chunk);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        plugin.getLogger().warning("EXPERIMENTAL population activation enabled: use only on a backup/test world.");
    }
    void stop() { if(task!=null) task.cancel(); pending.clear();pendingSet.clear();lateRetry.clear();cache.clear(); }

    void onChunkLoad(Chunk chunk) {
        if (!enabled()) return;
        if (!Bukkit.isPrimaryThread()) { Bukkit.getScheduler().runTask(plugin,() -> { if(chunk.isLoaded()) enqueue(chunk); }); return; }
        // Try before client receives a newly loaded chunk. No observer may already see it.
        if (chunk.isEntitiesLoaded() && plugin.isUnobserved(chunk)) activate(chunk, true);
        else enqueue(chunk);
    }
    void onEntitiesLoad(Chunk chunk) {
        if (enabled()) enqueue(chunk);
    }
    void onChunkUnload(Chunk chunk) {
        // Keep this key in pendingSet until its queued entry is consumed. Dropping only
        // the key allows every unload/reload to add an unbounded duplicate queue entry.
        lateRetry.remove(new ChunkRef(chunk.getWorld().getUID(),chunk.getX(),chunk.getZ()));
    }
    private void enqueue(Chunk chunk) {
        var key=new ChunkRef(chunk.getWorld().getUID(),chunk.getX(),chunk.getZ());
        if(pendingSet.contains(key)) return;
        // Never accumulate an unbounded queue during a huge Metropolis/Chunky pregeneration.
        int cap=Math.max(128,Math.min(32768,plugin.getConfig().getInt("population.activation.max-queued-chunks",8192)));
        if(pendingSet.size()>=cap) {
            droppedQueue++;
            if(droppedQueue <= 3 || droppedQueue%1000==0)
                plugin.getLogger().warning("Population scan backlog at capacity; deferring unloaded regions to a later chunk load (dropped="+droppedQueue+")");
            return;
        }
        pendingSet.add(key);pending.addLast(key);
    }
    private void tick() {
        if (!enabled()) return;
        resetBudget();
        if(++retryTick%20==0) {
            int attempts=0;
            long now=System.currentTimeMillis();
            for(var it=lateRetry.entrySet().iterator();it.hasNext() && attempts<4;) {
                var entry=it.next();
                if(entry.getValue()>now)continue;
                ChunkRef ref=entry.getKey(); it.remove(); attempts++;
                World w=Bukkit.getWorld(ref.worldId());
                if(w!=null && w.isChunkLoaded(ref.x(),ref.z()))enqueue(w.getChunkAt(ref.x(),ref.z()));
            }
        }
        int limit = Math.max(1, Math.min(16, plugin.getConfig().getInt("population.activation.max-chunks-per-tick",2)));
        for(int i=0; i<limit && !pending.isEmpty();i++) {
            ChunkRef key=pending.removeFirst();pendingSet.remove(key);
            World world=Bukkit.getWorld(key.worldId());
            if(world==null || !world.isChunkLoaded(key.x(),key.z())) continue;
            Chunk chunk=world.getChunkAt(key.x(),key.z());
            if(!chunk.isEntitiesLoaded()) {enqueue(chunk);continue;}
            if (!plugin.isUnobserved(chunk)) { deferredObserved++; scheduleLateRetry(key,System.currentTimeMillis()); continue; }
            activate(chunk,false);
        }
        // Entries declined because players saw the area are reconsidered on a future chunk load.
        // No continuously repeated scanning of observed chunks.
    }
    private void scheduleLateRetry(ChunkRef ref,long now) {
        int retryCap=Math.max(128,Math.min(8192,plugin.getConfig().getInt("population.activation.max-late-retries",2048)));
        if(lateRetry.containsKey(ref)||lateRetry.size()<retryCap)
            lateRetry.put(ref,now+1000L*Math.max(3,Math.min(600,plugin.getConfig().getInt("population.activation.late-retry-seconds",30))));
    }

    private void resetBudget() {
        int current = Bukkit.getCurrentTick();
        if (current != lastTick) {
            lastTick=current;
            remainingBudget=Math.max(1,Math.min(200,plugin.getConfig().getInt("population.activation.max-new-zombies-per-tick",12)));
        }
    }
    private void activate(Chunk chunk, boolean fromChunkLoad) {
        Plugin metropolis=Bukkit.getPluginManager().getPlugin("Metropolis");
        if(metropolis==null || !metropolis.isEnabled() || !chunk.isEntitiesLoaded()) return;
        if(!plugin.isUnobserved(chunk)) {deferredObserved++;scheduleLateRetry(new ChunkRef(chunk.getWorld().getUID(),chunk.getX(),chunk.getZ()),System.currentTimeMillis());return;}
        resetBudget();
        if(remainingBudget<=0) {enqueue(chunk);return;}
        // The exporter may finish a BUILD after the first chunk-load callback. Retry
        // currently loaded chunks at low frequency while they remain unobserved.
        ChunkRef ref = new ChunkRef(chunk.getWorld().getUID(),chunk.getX(),chunk.getZ());
        int retryCap=Math.max(128,Math.min(8192,plugin.getConfig().getInt("population.activation.max-late-retries",2048)));
        if(lateRetry.size()<retryCap)lateRetry.put(ref,System.currentTimeMillis()+1000L*Math.max(3,Math.min(600,
            plugin.getConfig().getInt("population.activation.late-retry-seconds",30))));
        World w=chunk.getWorld();
        int cx=chunk.getX(),cz=chunk.getZ();
        var unique=new HashSet<String>();
        for(int rx=Math.floorDiv(cx,32)-1;rx<=Math.floorDiv(cx,32)+1;rx++)
            for(int rz=Math.floorDiv(cz,32)-1;rz<=Math.floorDiv(cz,32)+1;rz++) {
                Path file=metropolis.getDataFolder().toPath().resolve("deadstreets-build-flags-v1")
                    .resolve(w.getUID().toString()).resolve(rx+"."+rz+".tsv");
                for(MetropolisFlag flag:read(file)) {
                    if(!flag.worldId().equals(w.getUID().toString()) || flag.worldSeed()!=w.getSeed()) {invalidFlags++;continue;}
                    if(!unique.add(flag.buildId())) continue;
                    if(Math.floorDiv(flag.minX(),16)>cx || Math.floorDiv(flag.maxX(),16)<cx
                            || Math.floorDiv(flag.minZ(),16)>cz || Math.floorDiv(flag.maxZ(),16)<cz) continue;
                    String path="population.ranges."+flag.districtType();
                    if(!plugin.getConfig().isConfigurationSection(path)) continue;
                    int lo=plugin.getConfig().getInt(path+".min",0);
                    int hi=plugin.getConfig().getInt(path+".max",0);
                    int maxBuild=Math.max(1,Math.min(256,plugin.getConfig().getInt("population.activation.max-per-build",64)));
                    if(lo<0 || hi<lo || hi>maxBuild) {invalidFlags++;continue;}
                    List<PopulationPlan.Candidate> assigned;
                    try {assigned=PopulationPlan.inChunk(flag,new PopulationRoller.Range(lo,hi),cx,cz,maxBuild);}
                    catch(IllegalArgumentException bad) {invalidFlags++;continue;}
                    if(assigned.isEmpty()) continue;
                    NamespacedKey key=new NamespacedKey(plugin, PopulationPlan.activationKey(flag));
                    // Backwards compatibility: R03's initial test build used a BYTE completion key.
                    if(chunk.getPersistentDataContainer().has(key,PersistentDataType.BYTE)) continue;
                    Integer checkpoint=chunk.getPersistentDataContainer().get(key,PersistentDataType.INTEGER);
                    String journalKey=PopulationJournal.key(flag,chunk);
                    int cursor=Math.max(checkpoint==null?0:checkpoint,journal.cursor(journalKey));
                    if(cursor>=assigned.size())continue;
                    // Progress is recorded BEFORE each attempted spawn. This permits large builds
                    // to use a strict global spawn budget without either duplicating earlier
                    // ordinals or blocking forever when a build exceeds the per-tick budget.
                    while(cursor<assigned.size() && remainingBudget>0) {
                        var candidate=assigned.get(cursor++);
                        // An invalid location consumes the attempt without quota admission.
                        Location at=findSafeStandingLocation(w,flag,candidate);
                        if(at==null) {
                            if(!journal.write(journalKey,cursor))return;
                            chunk.getPersistentDataContainer().set(key,PersistentDataType.INTEGER,cursor);
                            skippedLocations++;remainingBudget--;continue;
                        }
                        if(!plugin.isUnobserved(chunk)) {cursor--;deferredObserved++;break;}
                        if(!plugin.budget().reserve()) {cursor--;break;}
                        // Independent durable log survives any interleaving of chunk and
                        // entity saves. Never spawn unless the ordinal is committed.
                        if(!journal.write(journalKey,cursor))return;
                        chunk.getPersistentDataContainer().set(key,PersistentDataType.INTEGER,cursor);
                        remainingBudget--;
                        try {
                            Zombie zombie=w.spawn(at,Zombie.class,z->{plugin.markWalker(z);plugin.budget().tag(z);});
                            createdZombies++;
                        } catch(RuntimeException ex) {
                            skippedLocations++;
                            plugin.getLogger().warning("Population admission consumed, entity creation uncertain for " + flag.buildId()+": "+ex.getMessage());
                        }
                    }
                    if(cursor==assigned.size()) processedSlices++;
                    else if(plugin.budget().reserved()<plugin.budget().maximum())enqueue(chunk);
                }
            }
    }

    private Location findSafeStandingLocation(World world,MetropolisFlag flag,PopulationPlan.Candidate c) {
        if(flag.interiorPointsProvided() && c.y()==-1) {
            if(!world.isChunkLoaded(c.x()>>4,c.z()>>4))return null;
            int minY=Math.max(world.getMinHeight()+2,flag.minY()+1),maxY=Math.min(world.getMaxHeight()-2,flag.maxY()-1);
            for(int y=minY;y<=maxY;y++) {
                Block feet=world.getBlockAt(c.x(),y,c.z()),head=world.getBlockAt(c.x(),y+1,c.z()),floor=world.getBlockAt(c.x(),y-1,c.z());
                if(!feet.isPassable()||!head.isPassable()||feet.isLiquid()||head.isLiquid()||!floor.getType().isSolid())continue;
                boolean roof=false;for(int dy=2;dy<=8&&y+dy<world.getMaxHeight();dy++)if(world.getBlockAt(c.x(),y+dy,c.z()).getType().isSolid()){roof=true;break;}
                if(roof)return new Location(world,c.x()+.5,y,c.z()+.5);
            }
            return null;
        }
        if(c.y()>=0) {
            if(!world.isChunkLoaded(c.x()>>4,c.z()>>4))return null;
            Block feet=world.getBlockAt(c.x(),c.y(),c.z()),head=world.getBlockAt(c.x(),c.y()+1,c.z()),floor=world.getBlockAt(c.x(),c.y()-1,c.z());
            if(!feet.isPassable()||!head.isPassable()||feet.isLiquid()||head.isLiquid()||!floor.getType().isSolid())return null;
            boolean roof=false;for(int dy=2;dy<=8&&c.y()+dy<world.getMaxHeight();dy++)if(world.getBlockAt(c.x(),c.y()+dy,c.z()).getType().isSolid()){roof=true;break;}
            return roof?new Location(world,c.x()+.5,c.y(),c.z()+.5):null;
        }
        int minY=Math.max(world.getMinHeight()+2,flag.minY()+1),maxY=Math.min(world.getMaxHeight()-2,flag.maxY()-1);if(maxY<minY)return null;
        long noise=c.salt();int minX=Math.max(flag.minX(),c.chunkX()*16),maxX=Math.min(flag.maxX(),c.chunkX()*16+15),minZ=Math.max(flag.minZ(),c.chunkZ()*16),maxZ=Math.min(flag.maxZ(),c.chunkZ()*16+15);
        for(int attempt=0;attempt<24;attempt++){noise=PopulationPlan.mix64(noise+attempt+0x9e3779b97f4a7c15L);int x=attempt==0?c.x():minX+(int)Math.floorMod(noise,(long)maxX-minX+1),z=attempt==0?c.z():minZ+(int)Math.floorMod(PopulationPlan.mix64(noise),(long)maxZ-minZ+1);int span=maxY-minY+1,yStart=minY+(int)Math.floorMod(noise>>>1,(long)span);
            for(int step=0;step<span;step++){int y=minY+(yStart-minY+step)%span;Block feet=world.getBlockAt(x,y,z),head=world.getBlockAt(x,y+1,z),floor=world.getBlockAt(x,y-1,z);if(feet.isPassable()&&head.isPassable()&&!feet.isLiquid()&&!head.isLiquid()&&floor.getType().isSolid())return new Location(world,x+.5,y,z+.5);}
        }return null;
    }

    private List<MetropolisFlag> read(Path path) {
        long now=System.currentTimeMillis();
        Snapshot prev=cache.get(path);
        if(prev!=null && now-prev.checkedMillis()<3000L) return prev.flags();
        long size=-1,stamp=-1;
        try {
            if(Files.isRegularFile(path)) {size=Files.size(path);stamp=Files.getLastModifiedTime(path).toMillis();}
        } catch(IOException e) {return List.of();}
        if(prev!=null && prev.size()==size && prev.mtime()==stamp) {
            cache.put(path,new Snapshot(size,stamp,now,prev.flags()));return prev.flags();
        }
        List<MetropolisFlag> records=new ArrayList<>();
        if(size>0 && size<=Math.max(256*1024L,plugin.getConfig().getLong("population.activation.max-region-file-bytes",4*1024*1024L))) {
            int maxRows=Math.max(100,Math.min(30000,plugin.getConfig().getInt("population.activation.max-region-records",12000)));
            HashSet<String> seen=new HashSet<>();
            try(BufferedReader lines=Files.newBufferedReader(path,StandardCharsets.UTF_8)) {
                for(int row=0;row<maxRows;row++) {
                    String raw=lines.readLine();if(raw==null)break;
                    try {var flag=MetropolisFlag.parse(raw);if(seen.add(flag.buildId())) records.add(flag);}
                    catch(RuntimeException malformed) {invalidFlags++;}
                }
            } catch(IOException e) {
                plugin.getLogger().warning("Could not read Metropolis population flags: "+path+" "+e);
            }
        }
        List<MetropolisFlag> safe=List.copyOf(records);
        cache.put(path,new Snapshot(size,stamp,now,safe));
        int maxCache=Math.max(8,Math.min(512,plugin.getConfig().getInt("population.activation.cached-regions",64)));
        while(cache.size()>maxCache) cache.remove(cache.keySet().iterator().next());
        return safe;
    }
    String status() {
        return "slices="+processedSlices+", zombies="+createdZombies+", no-space="+skippedLocations
                +", invalid="+invalidFlags+", journal-slices="+journal.distinctSlices()+", observed-deferred="+deferredObserved+", queue="+pending.size()+", late-retry="+lateRetry.size()+", queued-dropped="+droppedQueue;
    }
}
