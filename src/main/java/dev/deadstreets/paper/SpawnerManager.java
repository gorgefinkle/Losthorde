package dev.deadstreets.paper;

import dev.deadstreets.core.SpawnerCycle;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Zombie;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.SplittableRandom;
import java.util.UUID;

/** One tick pass per tracked zombie-spawner block, NOT per player. No forced chunk loading. */
public final class SpawnerManager {
    private record SpawnerId(UUID worldId, int x, int y, int z) {
        String key() { return worldId+":"+x+":"+y+":"+z; }
    }
    private static final class Managed {
        final SpawnerId id;
        final SpawnerCycle cycle;
        TextDisplay countdown;
        String lastText = "";
        int pendingWave;
        Managed(SpawnerId id, SpawnerCycle cycle) { this.id=id; this.cycle=cycle; }
    }
    private final DeadStreetsPlugin plugin;
    private final NamespacedKey originalTypeKey;
    private final NamespacedKey countdownDisplayKey;
    private final Map<SpawnerId,Managed> tracked = new HashMap<>();
    private final Map<String,String> checkpoints = new HashMap<>();
    private final SplittableRandom random = new SplittableRandom();
    private BukkitTask task;
    private long ticks;
    private int scanCursor;
    private record ScanKey(UUID world,int cx,int cz) {}
    private final Map<ScanKey,Long> recentlyScanned = new HashMap<>();
    SpawnerManager(DeadStreetsPlugin plugin) {
        this.plugin=plugin;
        this.originalTypeKey=new NamespacedKey(plugin,"vanilla_spawner_original_type");
        this.countdownDisplayKey=new NamespacedKey(plugin,"spawner_countdown_display");
    }
    int size() { return tracked.size(); }
    void start() {
        readCheckpoints();
        for (World world:Bukkit.getWorlds()) for (Chunk chunk: world.getLoadedChunks()) onChunkLoad(chunk);
        task = Bukkit.getScheduler().runTaskTimer(plugin,this::tick,20L,20L);
    }
    void stop() {
        if (task!=null) task.cancel();
        for (Managed m:tracked.values()) { checkpoint(m); removeDisplay(m); }
        tracked.clear();
        writeCheckpoints();
    }
    void onChunkLoad(Chunk chunk) {
        for (BlockState tile : chunk.getTileEntities()) {
            if (tile instanceof CreatureSpawner) prepareSpawner(tile.getBlock());
        }
    }
    void onBlockPlaced(Block block) { if(block.getType()==Material.SPAWNER) prepareSpawner(block); }
    private void prepareSpawner(Block block) {
        if (block.getType()!=Material.SPAWNER || !(block.getState() instanceof CreatureSpawner spawner)) return;
        String mode=plugin.getConfig().getString("vanilla-spawners.mode","ALLOW_ALL");
        var data=spawner.getPersistentDataContainer();
        if ("CONVERT_TO_ZOMBIES".equalsIgnoreCase(mode)) {
            // Preserve the original mob type once so switching back to ALLOW_ALL is reversible.
            if(!data.has(originalTypeKey,PersistentDataType.STRING))
                data.set(originalTypeKey,PersistentDataType.STRING,spawner.getSpawnedType().name());
            if(spawner.getSpawnedType()!=EntityType.ZOMBIE) {
                spawner.setSpawnedType(EntityType.ZOMBIE);
                spawner.update(true,false);
            }
            if(plugin.getConfig().getBoolean("spawners.enabled",true))register(block);
        } else {
            String original=data.get(originalTypeKey,PersistentDataType.STRING);
            if(original!=null) {
                try {
                    EntityType restored=EntityType.valueOf(original);
                    if(spawner.getSpawnedType()!=restored)spawner.setSpawnedType(restored);
                    data.remove(originalTypeKey);
                    spawner.update(true,false);
                } catch(IllegalArgumentException badType) {
                    plugin.getLogger().warning("Cannot restore vanilla spawner type '"+original+"' at "+block.getLocation());
                }
            }
            Managed managed=tracked.remove(id(block));
            if(managed!=null)removeDisplay(managed);
        }
    }
    private void register(Block block) {
        var id = id(block);
        if (tracked.containsKey(id)) return;
        String saved = checkpoints.get(id.key());
        SpawnerCycle cycle;
        try {
            if(saved==null) cycle=new SpawnerCycle();
            else {
                String[] state=saved.split("\\|",-1);
                cycle=SpawnerCycle.restore(state[0]);
                
            }
        }
        catch (RuntimeException ex) { plugin.getLogger().warning("Ignoring corrupt spawner state " +id.key());cycle=new SpawnerCycle(); }
        Managed managed=new Managed(id,cycle);
        if(saved!=null) {
            String[] state=saved.split("\\|",-1);
            if(state.length>1) try { managed.pendingWave=Math.max(0,Math.min(256,Integer.parseInt(state[1]))); }
            catch(NumberFormatException ignored) { managed.pendingWave=0; }
        }
        tracked.put(id,managed);
    }
    void onChunkUnload(Chunk chunk) {
        var iter = tracked.values().iterator();
        while (iter.hasNext()) {
            Managed m = iter.next();
            if (m.id.worldId.equals(chunk.getWorld().getUID()) && (m.id.x>>4)==chunk.getX() && (m.id.z>>4)==chunk.getZ()) {
                // Pause the cycle at unload so no waves accumulate while it is inactive.
                m.cycle.tick(System.currentTimeMillis(), false,0,rules(),random);
                m.pendingWave=0;
                checkpoint(m); removeDisplay(m); iter.remove();
            }
        }
    }
    void onBreak(Block block) {
        if (block.getType()!=Material.SPAWNER) return;
        var id=id(block); Managed m=tracked.remove(id);
        if (m!=null) removeDisplay(m);
        checkpoints.remove(id.key());
    }
    private void tick() {
        ticks++;
        if(!plugin.getConfig().getBoolean("spawners.enabled",true)) return;
        SpawnerCycle.Rules rules = rules();
        long now = System.currentTimeMillis();
        scanNearPlayers(now);
        int newZombieBudget = Math.max(1,plugin.getConfig().getInt("spawners.max-new-zombies-per-second",30));
        // Index each player ONCE per world/chunk. Spawners don't scan the full online player list.
        Map<UUID,Map<Long,java.util.List<Player>>> players = new HashMap<>();
        for(World w:Bukkit.getWorlds()) {
            var cells = new HashMap<Long,java.util.List<Player>>();
            for(Player p:w.getPlayers()) {
                if(p.getGameMode()==org.bukkit.GameMode.SPECTATOR) continue;
                var l=p.getLocation();
                cells.computeIfAbsent(cellKey(l.getBlockX()>>4,l.getBlockZ()>>4),ignored -> new java.util.ArrayList<>()).add(p);
            }
            players.put(w.getUID(),cells);
        }
        var iterator = tracked.values().iterator();
        while(iterator.hasNext()) {
            Managed m=iterator.next();
            World world=Bukkit.getWorld(m.id.worldId);
            if(world==null || !world.isChunkLoaded(m.id.x>>4,m.id.z>>4)) {
                m.cycle.tick(now,false,0,rules,random);checkpoint(m);removeDisplay(m);iterator.remove();continue;
            }
            Block block=world.getBlockAt(m.id.x,m.id.y,m.id.z);
            if (block.getType()!=Material.SPAWNER) {
                removeDisplay(m);checkpoints.remove(m.id.key());iterator.remove();continue;
            }
            if(!(block.getState() instanceof CreatureSpawner spawner) || spawner.getSpawnedType()!=EntityType.ZOMBIE) {
                m.cycle.tick(now,false,0,rules,random);removeDisplay(m);continue;
            }
            Location center=block.getLocation().add(.5,.5,.5);
            int radius=Math.max(1,plugin.getConfig().getInt("spawners.activation-radius",16));
            boolean playerNear=isPlayerNear(players.get(world.getUID()),center,radius);
            int nearby=playerNear?countZombies(center,Math.max(2,plugin.getConfig().getInt("spawners.nearby-cap-radius",12))):0;
            var result=m.cycle.tick(now,playerNear,nearby + m.pendingWave,rules,random);
            if(result.spawnCount()>0) { m.pendingWave = Math.min(rules.nearbyCap(), m.pendingWave+result.spawnCount()); checkpoint(m); }
            if(!result.active()) { if(m.pendingWave!=0) {m.pendingWave=0;checkpoint(m);} removeDisplay(m); }
            if(result.active() && m.pendingWave>0 && newZombieBudget>0) {
                int available = Math.max(0,rules.nearbyCap() - nearby);
                int allowed = Math.min(newZombieBudget, Math.min(available,m.pendingWave));
                int created = spawnWave(center,allowed);
                newZombieBudget -= created;
                m.pendingWave -= created;
                checkpoint(m);
            }
            if(!result.active()) removeDisplay(m);
            else if(plugin.getConfig().getBoolean("spawners.show-countdown",true)) updateDisplay(m,center,result);
            else removeDisplay(m);
        }
        if(ticks%60==0) writeCheckpoints();
    }
    /** Bounded local re-scan discovers zombie spawners pasted AFTER ChunkLoadEvent.
     * Only looks at already-loaded chunks near players; never touches generation. */
    private void scanNearPlayers(long now) {
        int scanned=0;
        int limit=Math.max(1,Math.min(32,plugin.getConfig().getInt("spawners.late-scan-chunks-per-second",16)));
        for(World w:Bukkit.getWorlds()) for(Player p:w.getPlayers()) {
            int px=p.getLocation().getBlockX()>>4,pz=p.getLocation().getBlockZ()>>4;
            // Rotate the scan across a 5x5 window, covering approaching spawners.
            for(int i=0;i<25 && scanned<limit;i++) {
                int index=(scanCursor+i)%25;
                int cx=px+index%5-2,cz=pz+index/5-2;
                ScanKey key=new ScanKey(w.getUID(),cx,cz);
                if(now-recentlyScanned.getOrDefault(key,0L)<10_000L || !w.isChunkLoaded(cx,cz))continue;
                recentlyScanned.put(key,now);
                onChunkLoad(w.getChunkAt(cx,cz));scanned++;
            }
            scanCursor=(scanCursor+7)%25;
            if(scanned>=limit)break;
        }
        if(ticks%300==0)recentlyScanned.entrySet().removeIf(e->now-e.getValue()>120_000L);
    }

    private SpawnerCycle.Rules rules() {
        var c=plugin.getConfig();
        int lo=Math.max(1,c.getInt("spawners.wave-min",2));
        int hi=Math.max(lo,c.getInt("spawners.wave-max",5));
        int min=Math.max(1,c.getInt("spawners.interval-min-seconds",30));
        int max=Math.max(min,c.getInt("spawners.interval-max-seconds",60));
        int cap=Math.max(1,c.getInt("spawners.nearby-cap",16));
        long cooldown=Math.max(0,c.getLong("spawners.reset-cooldown-seconds",60))*1000L;
        return new SpawnerCycle.Rules(lo,hi,min,max,cap,cooldown);
    }
    private static long cellKey(int chunkX,int chunkZ) {
        return ((long)chunkX<<32) ^ (chunkZ & 0xffff_ffffL);
    }
    private static boolean isPlayerNear(Map<Long,java.util.List<Player>> cells,Location center,int radius) {
        if(cells==null || cells.isEmpty()) return false;
        int x=center.getBlockX()>>4,z=center.getBlockZ()>>4;
        int scan=(radius>>4)+1;
        for(int cx=x-scan;cx<=x+scan;cx++) for(int cz=z-scan;cz<=z+scan;cz++) {
            var nearby = cells.get(cellKey(cx,cz));
            if(nearby==null) continue;
            for(Player p:nearby) if(p.getLocation().distanceSquared(center)<=radius*radius) return true;
        }
        return false;
    }
    private int countZombies(Location center,int radius) {
        int n=0;
        for (Entity e : center.getWorld().getNearbyEntities(center,radius,radius,radius)) {
            if(e instanceof Zombie && e.getLocation().distanceSquared(center)<=radius*radius) n++;
        }
        return n;
    }
    private int spawnWave(Location center,int count) {
        int spawned=0;
        for(int tries=0;tries<count*14 && spawned<count;tries++) {
            int x=center.getBlockX()+random.nextInt(-3,4);
            int z=center.getBlockZ()+random.nextInt(-3,4);
            if(x==center.getBlockX() && z==center.getBlockZ()) continue;
            World w=center.getWorld();
            if(!w.isChunkLoaded(x>>4,z>>4)) continue;
            for(int delta : new int[]{0,1,-1,2,-2,3}) {
                int y=center.getBlockY()+delta;
                if(y<=w.getMinHeight()+1 || y>=w.getMaxHeight()-2) continue;
                Block foot=w.getBlockAt(x,y,z);
                Block head=w.getBlockAt(x,y+1,z);
                Block floor=w.getBlockAt(x,y-1,z);
                if(foot.isPassable() && head.isPassable() && floor.getType().isSolid() && !foot.isLiquid() && !head.isLiquid()) {
                    try {
                        if(!plugin.budget().reserve())return spawned;
                        Zombie zombie=w.spawn(new Location(w,x+.5,y,z+.5),Zombie.class,created->{
                            plugin.markWalker(created);plugin.budget().tag(created);
                        });
                        spawned++;
                    } catch (RuntimeException ex) { plugin.getLogger().warning("Spawner zombie creation failed: " +ex.getMessage()); }
                    break;
                }
            }
        }
        return spawned;
    }
    private void updateDisplay(Managed m,Location center,SpawnerCycle.Result state) {
        if(m.countdown==null || !m.countdown.isValid()) {
            m.countdown=center.getWorld().spawn(center.clone().add(0,1.35,0),TextDisplay.class);
            m.countdown.setPersistent(false);
            m.countdown.getPersistentDataContainer().set(countdownDisplayKey,PersistentDataType.BYTE,(byte)1);
            m.countdown.setBillboard(Display.Billboard.CENTER);
            m.countdown.setShadowed(true);
        }
        long seconds=state.remainingSeconds();
        String text=state.capped() ? ChatColor.RED+"Wave Paused: Zombie Limit"
            : ((seconds<=5 ? ChatColor.RED : ChatColor.GOLD)+"Next Wave: " + String.format("%02d:%02d", seconds/60, seconds%60));
        if(!m.lastText.equals(text)) {m.countdown.setText(text);m.lastText=text;}
    }
    private void removeDisplay(Managed m) {
        if(m.countdown!=null && m.countdown.isValid()) m.countdown.remove();
        m.countdown=null;m.lastText="";
    }
    private static SpawnerId id(Block block) { return new SpawnerId(block.getWorld().getUID(),block.getX(),block.getY(),block.getZ()); }
    private void checkpoint(Managed m) { checkpoints.put(m.id.key(),m.cycle.save(System.currentTimeMillis())+"|"+m.pendingWave); }
    private Path dataFile() { return plugin.getDataFolder().toPath().resolve("spawner-states.properties"); }
    private void readCheckpoints() {
        if(!Files.isRegularFile(dataFile())) return;
        Properties p=new Properties();
        try(InputStream in=Files.newInputStream(dataFile())) {p.load(in);for(var key:p.stringPropertyNames()) checkpoints.put(key,p.getProperty(key));}
        catch(IOException ex) {plugin.getLogger().warning("Could not load spawner states: "+ex.getMessage());}
    }
    private void writeCheckpoints() {
        for(Managed m:tracked.values()) checkpoint(m);
        Properties p=new Properties();p.putAll(checkpoints);
        try {
            Files.createDirectories(dataFile().getParent());
            Path tmp=dataFile().resolveSibling("spawner-states.properties.tmp");
            try(OutputStream out=Files.newOutputStream(tmp)) {p.store(out,"DeadStreets spawner countdown checkpoints");}
            try {Files.move(tmp,dataFile(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(java.nio.file.AtomicMoveNotSupportedException ex) {Files.move(tmp,dataFile(),StandardCopyOption.REPLACE_EXISTING);}
        } catch(IOException ex) {plugin.getLogger().warning("Could not checkpoint spawners: "+ex.getMessage());}
    }
}
