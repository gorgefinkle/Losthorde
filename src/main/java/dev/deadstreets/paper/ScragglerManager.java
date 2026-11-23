package dev.deadstreets.paper;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Zombie;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Scragglers are ordinary persistent walkers drawn from the existing zombie
 * admission paths. They are never spawned or teleported by this manager:
 * it only gives already-existing, non-horde walkers occasional local wander
 * goals in chunks that are already loaded.
 */
final class ScragglerManager {
    private final DeadStreetsPlugin plugin;
    private final Random random=new Random();
    private final Map<UUID,Long> nextWander=new HashMap<>();
    private BukkitTask task;
    private int cursor;
    private long goals;
    private long skipped;
    ScragglerManager(DeadStreetsPlugin plugin){this.plugin=plugin;}
    void start(){
        if(!plugin.getConfig().getBoolean("scragglers.enabled",true))return;
        task=Bukkit.getScheduler().runTaskTimer(plugin,this::tick,100L,100L);
    }
    void stop(){if(task!=null)task.cancel();nextWander.clear();}
    void onChunkLoad(Chunk chunk){if(plugin.getConfig().getBoolean("scragglers.enabled",true))inspect(chunk);}
    void onEntitiesLoad(Chunk chunk){if(plugin.getConfig().getBoolean("scragglers.enabled",true))inspect(chunk);}
    void onZombieDeath(Zombie zombie){nextWander.remove(zombie.getUniqueId());}
    private void tick(){
        if(!plugin.getConfig().getBoolean("scragglers.enabled",true))return;
        long now=System.currentTimeMillis();
        int budget=Math.max(1,Math.min(256,plugin.getConfig().getInt("scragglers.max-chunks-per-cycle",32)));
        for(World world:Bukkit.getWorlds()){
            Chunk[] loaded=world.getLoadedChunks();
            if(loaded.length==0)continue;
            int count=Math.min(budget,loaded.length);
            for(int i=0;i<count;i++)inspect(loaded[Math.floorMod(cursor++,loaded.length)],now);
            budget-=count;if(budget<=0)break;
        }
        nextWander.entrySet().removeIf(e->now-e.getValue()>300_000L);
    }
    private void inspect(Chunk chunk){inspect(chunk,System.currentTimeMillis());}
    private void inspect(Chunk chunk,long now){
        if(!chunk.isEntitiesLoaded())return;
        for(Entity entity:chunk.getEntities()){
            if(!(entity instanceof Zombie zombie)||!zombie.isValid())continue;
            var pdc=zombie.getPersistentDataContainer();
            if(!pdc.has(plugin.scragglerTag(),PersistentDataType.BYTE)
                    || pdc.has(plugin.hordeMemberTag(),PersistentDataType.STRING))continue;
            if(now<nextWander.getOrDefault(zombie.getUniqueId(),0L))continue;
            nextWander.put(zombie.getUniqueId(),now+random.nextLong(12_000L,35_001L));
            if(zombie.getTarget()!=null||zombie.isDead())continue;
            Location destination=chooseDestination(zombie);
            if(destination==null){skipped++;continue;}
            try{zombie.getPathfinder().moveTo(destination,0.82);goals++;}
            catch(RuntimeException failure){skipped++;}
        }
    }
    private Location chooseDestination(Zombie zombie){
        World world=zombie.getWorld();Location origin=zombie.getLocation();
        for(int attempt=0;attempt<16;attempt++){
            int x=origin.getBlockX()+random.nextInt(-18,19);
            int z=origin.getBlockZ()+random.nextInt(-18,19);
            if(!world.isChunkLoaded(x>>4,z>>4))continue;
            int y=origin.getBlockY()+random.nextInt(-3,4);
            for(int dy=0;dy<=5;dy++){
                int feetY=y+dy;
                if(feetY<=world.getMinHeight()+1||feetY>=world.getMaxHeight()-2)continue;
                Block feet=world.getBlockAt(x,feetY,z),head=world.getBlockAt(x,feetY+1,z),floor=world.getBlockAt(x,feetY-1,z);
                if(feet.isPassable()&&head.isPassable()&&!feet.isLiquid()&&!head.isLiquid()&&floor.getType().isSolid())
                    return new Location(world,x+.5,feetY,z+.5);
            }
        }
        return null;
    }
    String status(){return "wander-goals="+goals+", no-local-route="+skipped+", tracked="+nextWander.size();}
}
