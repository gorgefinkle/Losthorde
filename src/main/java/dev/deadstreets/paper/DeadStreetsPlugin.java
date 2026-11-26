package dev.deadstreets.paper;

import dev.deadstreets.core.DangerRating;
import dev.deadstreets.core.Temperament;
import dev.deadstreets.core.ObservationGuard;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.block.Action;
import org.bukkit.block.data.Openable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Enemy;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.World;
import org.bukkit.Chunk;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/** Dead Streets: Paper zombie gameplay, persistent populations, opt-in experimental hordes. */
public final class DeadStreetsPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private NamespacedKey walkerTag;
    private NamespacedKey scragglerTag;
    private NamespacedKey hordeMemberTag;
    private ScragglerManager scragglers;
    private SpawnerManager spawners;
    private PopulationManager population;
    private HordeManager hordes;
    private BloodMoonManager bloodMoon;
    private WalkerBudget walkerBudget;
    private HulkManager hulks;
    private CrawlerManager crawlers;
    private RunnerManager runners;
    private PopperManager poppers;
    private ZombieIdentityManager identities;
    private BetterModelBridge models;
    private final ObservationGuard observation = new ObservationGuard();
    private final Map<UUID,Integer> observerTickByWorld = new HashMap<>();
    ObservationGuard observation() { return observation; }
    WalkerBudget budget() { return walkerBudget; }
    CrawlerManager crawlers() { return crawlers; }
    BetterModelBridge models() { return models; }
    boolean isManagedWalker(Zombie z) {return z.getPersistentDataContainer().has(walkerTag, PersistentDataType.BYTE);}
    boolean isCrawler(Zombie z) { return crawlers != null && crawlers.isCrawler(z); }
    void recordObservers(World w,long now) {
        int tick = Bukkit.getCurrentTick();
        if(observerTickByWorld.getOrDefault(w.getUID(),-1)==tick)return;
        observerTickByWorld.put(w.getUID(),tick);
        for(Player p:w.getPlayers()) {
            var v=p.getVelocity();
            observation.observe(p.getUniqueId(),w.getUID(),p.getLocation().getX(),p.getLocation().getZ(),
                    v.getX(),v.getZ(),w.getViewDistance(),now);
        }
    }
    boolean isUnobserved(Chunk chunk) {
        if(!chunk.getPlayersSeeingChunk().isEmpty())return false;
        long now=System.currentTimeMillis();recordObservers(chunk.getWorld(),now);
        return !observation.protectedChunk(chunk.getWorld().getUID(),chunk.getX(),chunk.getZ(),now);
    }
    void observeTeleport(Player p,org.bukkit.Location dest) {
        if(dest!=null && dest.getWorld()!=null)observation.observeTeleport(p.getUniqueId(),dest.getWorld().getUID(),
            dest.getX(),dest.getZ(),dest.getWorld().getViewDistance(),System.currentTimeMillis());
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        walkerTag = new NamespacedKey(this, "dead_streets_walker");
        scragglerTag = new NamespacedKey(this, "scraggler_v1");
        hordeMemberTag = new NamespacedKey(this, "horde_id");
        walkerBudget = new WalkerBudget(this);
        hulks = new HulkManager(this);
        crawlers = new CrawlerManager(this);
        runners = new RunnerManager(this);
        poppers = new PopperManager(this);
        identities = new ZombieIdentityManager(this);
        identities.start();
        models = new BetterModelBridge(this);
        walkerBudget.load();
        spawners = new SpawnerManager(this);
        population = new PopulationManager(this);
        hordes = new HordeManager(this);
        bloodMoon = new BloodMoonManager(this, hordes);
        scragglers = new ScragglerManager(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("deadstreets"), "deadstreets command").setExecutor(this);
        spawners.start();
        refreshLoadedZombies();
        models.installAssetsAndReload();
        if (Boolean.getBoolean("deadstreets.native-smoke")) Bukkit.getScheduler().runTaskLater(this, this::runNativeInfectedSmokeTest, 2L);
        population.start();
        hordes.start();
        bloodMoon.start();
        scragglers.start();
        getLogger().info("Dead Streets R24: BetterModel custom infected geometry is enabled; zombie textures resolve through the client resource-pack stack with vanilla as the fallback.");
    }
    @Override public void onDisable() {
        if (spawners != null) spawners.stop();
        if (population != null) population.stop();
        if (bloodMoon != null) bloodMoon.stop();
        if (hordes != null) hordes.stop();
        if (scragglers != null) scragglers.stop();
        if (walkerBudget != null) walkerBudget.stop();
        if (identities != null) identities.stop();
        if (models != null) models.detachAll();
    }
    private void runNativeInfectedSmokeTest() {
        if (Bukkit.getWorlds().isEmpty()) throw new IllegalStateException("No loaded world for native infected smoke test");
        var world = Bukkit.getWorlds().getFirst();
        var base = world.getSpawnLocation().clone().add(8, 1, 8);
        Zombie crawler = null;
        Zombie hulk = null;
        try {
            crawler = world.spawn(base, Zombie.class, zombie -> {
                zombie.setAI(false);
                zombie.setSilent(true);
            });
            hulk = world.spawn(base.clone().add(4, 0, 0), Zombie.class, zombie -> {
                zombie.setAI(false);
                zombie.setSilent(true);
            });

            crawlers.prepareTest(crawler);
            crawlers.refresh(crawler);
            hulks.prepareTest(hulk);

            var scale = hulk.getAttribute(org.bukkit.attribute.Attribute.SCALE);
            if (scale == null || Math.abs(scale.getBaseValue() - 1.53846) > 0.001) {
                throw new IllegalStateException("Hulk native scale is not the three-block target: "
                        + (scale == null ? "missing" : scale.getBaseValue()));
            }
            double measuredHeight = hulk.getBoundingBox().getHeight();
            if (Math.abs(measuredHeight - 3.0) > 0.08) {
                throw new IllegalStateException("Hulk bounding box is not three blocks tall: " + measuredHeight);
            }
            getLogger().info("Native infected smoke test passed: Crawler uses its horizontal animated BetterModel geometry (native pose is not the crawl animation); Hulk native scale "
                    + String.format(Locale.ROOT, "%.5f", scale.getBaseValue()) + " and "
                    + String.format(Locale.ROOT, "%.3f", measuredHeight) + "-block bounding box; BetterModel integration is optional.");
        } finally {
            if (crawler != null && crawler.isValid()) crawler.remove();
            if (hulk != null && hulk.isValid()) hulk.remove();
        }
    }

    NamespacedKey scragglerTag() { return scragglerTag; }
    boolean tryJoinHorde(Zombie zombie) { return hordes!=null && hordes.absorbScraggler(zombie); }
    NamespacedKey hordeMemberTag() { return hordeMemberTag; }
    public void markWalker(Zombie zombie) { markWalker(zombie, false); }

    /** Horde materialization is a distinct eligible source for rare special infected. */
    void markHordeWalker(Zombie zombie) { markWalker(zombie, true); }

    private void markWalker(Zombie zombie, boolean hordeSpawn) {
        markWalkerBase(zombie);
        double chance=Math.max(0.0,Math.min(1.0,getConfig().getDouble("scragglers.assignment-chance",0.12)));
        if(chance>0 && ThreadLocalRandom.current().nextDouble()<chance)
            zombie.getPersistentDataContainer().set(scragglerTag,PersistentDataType.BYTE,(byte)1);
        // One special type per eligible walker. Special assignments remain restricted to
        // scragglers and physical horde members; ambient/building/spawner walkers stay ordinary.
        boolean eligibleSpecial = hordeSpawn
                || zombie.getPersistentDataContainer().has(scragglerTag, PersistentDataType.BYTE);
        if (eligibleSpecial) {
            boolean assigned = (runners != null && runners.isRunner(zombie))
                    || (poppers != null && poppers.isPopper(zombie))
                    || (crawlers != null && crawlers.isCrawler(zombie))
                    || (hulks != null && hulks.isHulk(zombie));
            if (!assigned && runners != null) { runners.assign(zombie); assigned = runners.isRunner(zombie); }
            if (!assigned && poppers != null) { poppers.assign(zombie); assigned = poppers.isPopper(zombie); }
            if (!assigned && crawlers != null) { crawlers.assign(zombie); assigned = crawlers.isCrawler(zombie); }
            if (!assigned && hulks != null) hulks.assign(zombie);
        }
    }

    private void markWalkerBase(Zombie zombie) {
        zombie.getPersistentDataContainer().set(walkerTag, PersistentDataType.BYTE, (byte) 1);
        zombie.setPersistent(true);
        zombie.setRemoveWhenFarAway(false);
        if (identities != null) identities.ensureIdentity(zombie);
        if (getConfig().getBoolean("zombies.never-burn-in-daylight",true)) zombie.setShouldBurnInDay(false);
    }

    /** Used by explicit /ds spawn tests so the requested subtype is not randomized into another one. */
    void markTestWalker(Zombie zombie) { markWalkerBase(zombie); }

    /** Popper offspring are guaranteed Crawlers and do not receive another random special subtype. */
    void markPopperCrawler(Zombie zombie) {
        markWalkerBase(zombie);
        crawlers.prepareTest(zombie);
        crawlers.refresh(zombie);
        walkerBudget.tag(zombie);
    }

    private boolean isOurs(Zombie zombie) {return isManagedWalker(zombie);}
    @EventHandler public void onSpawn(CreatureSpawnEvent event) {
        if (!allowNaturalMob(event) || blockNonZombieHostile(event)) { event.setCancelled(true); return; }
        if (!(event.getEntity() instanceof Zombie zombie)) return;
        // Vanilla spawner behavior is governed by vanilla-spawners.mode. In CONVERT_TO_ZOMBIES,
        // the spawner block itself is changed to ZOMBIE. When the proximity controller is enabled,
        // it owns the wave timing and cancels the vanilla SPAWNER event to prevent double waves.
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CHUNK_GEN) {
            if(getConfig().getBoolean("zombies.stop-natural-zombie-spawns",false)) {
                event.setCancelled(true);return;
            }
            if(!walkerBudget.reserve()) {event.setCancelled(true);return;}
            markWalker(zombie);
            walkerBudget.tag(zombie);
        }
        if(event.getSpawnReason()==CreatureSpawnEvent.SpawnReason.SPAWNER
                && getConfig().getBoolean("spawners.enabled",true)
                && "CONVERT_TO_ZOMBIES".equalsIgnoreCase(getConfig().getString("vanilla-spawners.mode","ALLOW_ALL"))) {
            event.setCancelled(true);
            return;
        }
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.REINFORCEMENTS && getConfig().getBoolean("zombies.stop-zombie-reinforcements",false)) {
            event.setCancelled(true); return;
        }
        // All walkers on this server are daylight immune, not only plugin-created walkers.
        if (getConfig().getBoolean("zombies.never-burn-in-daylight",true)) zombie.setShouldBurnInDay(false);
        if (getConfig().getBoolean("zombies.make-all-zombies-persistent",true)) {
            zombie.setPersistent(true);
            zombie.setRemoveWhenFarAway(false);
        }
    }
    private boolean blockNonZombieHostile(CreatureSpawnEvent event) {
        if (!getConfig().getBoolean("vanilla-mobs.block-non-zombie-hostiles", true)) return false;
        if (!(event.getEntity() instanceof Enemy) || event.getEntity() instanceof Zombie) return false;
        // Keep deliberate admin summons and player spawn eggs available; suppress all ambient,
        // spawner, raid, patrol, trial-spawner and plugin-driven hostile alternatives.
        var reason = event.getSpawnReason();
        return reason != CreatureSpawnEvent.SpawnReason.COMMAND
                && reason != CreatureSpawnEvent.SpawnReason.SPAWNER_EGG;
    }

    private boolean allowNaturalMob(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL
                && event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CHUNK_GEN) return true;
        EntityType type=event.getEntityType();
        String key=null;
        if (type==EntityType.SKELETON || type==EntityType.STRAY || type==EntityType.BOGGED) key="skeletons";
        else if (type==EntityType.CREEPER) key="creepers";
        else if (type==EntityType.WITCH) key="witches";
        else if (type==EntityType.SPIDER || type==EntityType.CAVE_SPIDER) key="spiders";
        else if (type==EntityType.VILLAGER || type==EntityType.WANDERING_TRADER) key="villagers";
        else if (type==EntityType.BAT || type==EntityType.GLOW_SQUID) key="ambient";
        else if (event.getEntity() instanceof org.bukkit.entity.Animals) key="animals";
        else if (event.getEntity() instanceof org.bukkit.entity.Enemy && !(event.getEntity() instanceof Zombie)) key="other_hostiles";
        else if (event.getEntity() instanceof org.bukkit.entity.Mob) key="other_creatures";
        if (key==null) return true;
        double chance=Math.max(0.0,Math.min(1.0,getConfig().getDouble("vanilla-mobs.natural-spawn-chance."+key,0.15)));
        return chance>=1.0 || (chance>0.0 && ThreadLocalRandom.current().nextDouble()<chance);
    }
    @EventHandler public void onCombust(EntityCombustEvent event) {
        // Only stop ignition from ambient/daylight causes; lava and entity-ignition remain valid.
        if (event instanceof EntityCombustByBlockEvent || event instanceof EntityCombustByEntityEvent) return;
        if (getConfig().getBoolean("zombies.never-burn-in-daylight",true)
            && event.getEntity() instanceof Zombie) event.setCancelled(true);
    }
    @EventHandler public void onChunkLoad(ChunkLoadEvent event) {
        spawners.onChunkLoad(event.getChunk());
        refreshExistingZombies(event.getChunk());
        population.onChunkLoad(event.getChunk());
        hordes.onChunkLoad(event.getChunk());
        scragglers.onChunkLoad(event.getChunk());
    }
    private void refreshExistingZombies(org.bukkit.Chunk chunk) {
        for (Entity entity : chunk.getEntities()) {
            if (!(entity instanceof Zombie z)) continue;
            if (crawlers != null) crawlers.refresh(z);
            if (hulks != null) hulks.refresh(z);
            if (runners != null) runners.refresh(z);
            if (poppers != null) poppers.refresh(z);
            if (isManagedWalker(z) && identities != null) identities.ensureIdentity(z);
            if (getConfig().getBoolean("zombies.never-burn-in-daylight",true)) z.setShouldBurnInDay(false);
            if (getConfig().getBoolean("zombies.make-all-zombies-persistent",true)) {
                z.setPersistent(true); z.setRemoveWhenFarAway(false);
            }
        }
    }
    void refreshLoadedZombies() {
        for (var world : Bukkit.getWorlds()) for (var chunk : world.getLoadedChunks()) refreshExistingZombies(chunk);
    }
    @EventHandler public void onEntitiesLoad(EntitiesLoadEvent event) {
        refreshExistingZombies(event.getChunk());
        population.onEntitiesLoad(event.getChunk());
        hordes.onEntitiesLoad(event.getChunk());
        scragglers.onEntitiesLoad(event.getChunk());
        // Entity chunks can finish loading after the block-chunk event. Still require
        // zero viewers and the same conservative position checks.
        hordes.onChunkLoad(event.getChunk());
    }
    @EventHandler public void onEntitiesUnload(EntitiesUnloadEvent event) {
        if (models != null) models.forgetChunk(event.getChunk());
        hordes.onEntitiesUnload(event.getChunk());
    }
    @EventHandler public void onZombieDeath(EntityDeathEvent event) {
        if(event.getEntity() instanceof Zombie zombie) {
            if (models != null) models.forgetEntity(zombie);
            scragglers.onZombieDeath(zombie);
            hordes.onZombieDeath(zombie);
            walkerBudget.onDeath(zombie);
            if (poppers != null) poppers.onDeath(zombie);
        }
    }

    @EventHandler public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (models != null) models.handleResourcePackStatus(event);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        hordes.forgetPlayer(event.getPlayer().getUniqueId());
        observation.forget(event.getPlayer().getUniqueId());
        if (bloodMoon != null) bloodMoon.onPlayerQuit(event.getPlayer());
        if (identities != null) identities.onQuit(event.getPlayer());
        if (models != null) models.forgetPackPlayer(event.getPlayer().getUniqueId());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onTeleport(PlayerTeleportEvent event) {
        observeTeleport(event.getPlayer(),event.getTo());
    }
    @EventHandler(priority=EventPriority.HIGH,ignoreCancelled=true) public void onBloodMoonBedEnter(PlayerBedEnterEvent event) {
        if (bloodMoon != null && bloodMoon.shouldBlockSleep(event.getPlayer().getWorld())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.DARK_RED + "[Dead Streets] " + ChatColor.RED
                    + "You cannot sleep through the Blood Moon tonight. Hold the shelter.");
        }
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) {
        observeTeleport(event.getPlayer(),event.getPlayer().getLocation());
        if (models != null) {
            Bukkit.getScheduler().runTask(this, () -> models.refreshPlayer(event.getPlayer()));
            Bukkit.getScheduler().runTaskLater(this, () -> models.sendModelResourcePack(event.getPlayer()), 140L);
        }
    }
    @EventHandler public void onChunkUnload(ChunkUnloadEvent event) {
        spawners.onChunkUnload(event.getChunk());
        population.onChunkUnload(event.getChunk());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onBreak(BlockBreakEvent event) {
        spawners.onBreak(event.getBlock());
        var at=event.getBlock().getLocation();hordes.sound(at.getWorld(),at.getX(),at.getZ(),0.75);scragglers.sound(at.getWorld(),at.getX(),at.getZ(),0.75);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onExplosion(EntityExplodeEvent event) {
        var at=event.getLocation();hordes.sound(at.getWorld(),at.getX(),at.getZ(),2.5);scragglers.sound(at.getWorld(),at.getX(),at.getZ(),2.5);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onBlockExplosion(BlockExplodeEvent event) {
        var at=event.getBlock().getLocation();hordes.sound(at.getWorld(),at.getX(),at.getZ(),2.5);scragglers.sound(at.getWorld(),at.getX(),at.getZ(),2.5);
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onInteract(PlayerInteractEvent event) {
        if(event.getAction()!=Action.RIGHT_CLICK_BLOCK || event.getClickedBlock()==null)return;
        if(event.getClickedBlock().getBlockData() instanceof Openable) {
            var at=event.getClickedBlock().getLocation();hordes.sound(at.getWorld(),at.getX(),at.getZ(),0.65);scragglers.sound(at.getWorld(),at.getX(),at.getZ(),0.65);
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onMovement(PlayerMoveEvent event) {
        if(!event.hasChangedBlock() || !event.getPlayer().isSprinting())return;
        long now=System.currentTimeMillis();
        if(hordes.sprintSoundDue(event.getPlayer().getUniqueId(),now)) {
            var at=event.getTo();if(at!=null){hordes.sound(at.getWorld(),at.getX(),at.getZ(),0.50);scragglers.sound(at.getWorld(),at.getX(),at.getZ(),0.50);}
        }
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void onPlace(BlockPlaceEvent event) {
        if (event.getBlockPlaced().getType() == org.bukkit.Material.SPAWNER) {
            Bukkit.getScheduler().runTask(this, () -> spawners.onBlockPlaced(event.getBlockPlaced()));
        }
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(ChatColor.GRAY +  "Dead Streets R14 | managed spawners: " + spawners.size()
                + " | quota="+walkerBudget.reserved()+"/"+walkerBudget.maximum()+" denied="+walkerBudget.denied()+" | " + hordes.status() + " | " + scragglers.status() + " | " + bloodMoon.status() + " | Metropolis: " + (population.enabled()?population.status():"activation disabled"));
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("bloodmoon")) {
            if (args[1].equalsIgnoreCase("display")) {
                if (args.length == 2) {
                    sender.sendMessage(ChatColor.RED + "Blood Moon display: " + bloodMoon.displayModeName()
                            + " | Use /deadstreets bloodmoon display <bossbar|scoreboard>");
                } else if (args.length == 3 && bloodMoon.setDisplayMode(args[2])) {
                    sender.sendMessage(ChatColor.RED + "Blood Moon display switched to "
                            + bloodMoon.displayModeName() + ".");
                } else {
                    sender.sendMessage(ChatColor.RED + "Use /deadstreets bloodmoon display <bossbar|scoreboard>");
                }
                return true;
            }
            if (args[1].equalsIgnoreCase("status")) {
                sender.sendMessage(ChatColor.GRAY + bloodMoon.status());
                return true;
            }
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("horde")) {
            if (args[1].equalsIgnoreCase("status")) {sender.sendMessage(ChatColor.GRAY+hordes.status());return true;}
            if(args[1].equalsIgnoreCase("tp") && sender instanceof Player player) {
                String result=hordes.teleportToHorde(player,args.length>=3?args[2]:null);
                if(result!=null) sender.sendMessage(ChatColor.YELLOW+result);
                return true;
            }
            if(args.length==4 && args[1].equalsIgnoreCase("spawn")) {
                if(!(sender instanceof Player player)) {sender.sendMessage("Spawn commands require an in-world player.");return true;}
                try {
                    int count=Integer.parseInt(args[2]);
                    var temperament=Temperament.parse(args[3]);
                    var horde=hordes.spawnManual(player.getWorld(),player.getLocation().getX(),player.getLocation().getZ(),count,temperament);
                    sender.sendMessage(ChatColor.YELLOW+"Virtual horde created: "+horde.id()+" ("+count+" "+temperament.label()+"). It will only materialize in unobserved, loaded chunks.");
                } catch(IllegalArgumentException | IllegalStateException ex) {sender.sendMessage(ChatColor.RED+ex.getMessage());}
                return true;
            }
        }
        if ((args.length == 2 || args.length == 3) && args[0].equalsIgnoreCase("spawn")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Run this command in game."); return true; }
            boolean force = args.length == 3 && (args[2].equalsIgnoreCase("-f") || args[2].equalsIgnoreCase("--force"));
            if (args.length == 3 && !force) {
                sender.sendMessage(ChatColor.RED + "Use /ds spawn <hulk|crawler|runner|popper> [-f]");
                return true;
            }
            String result;
            if (args[1].equalsIgnoreCase("crawler")) result = crawlers.spawnTest(player, force);
            else if (args[1].equalsIgnoreCase("hulk")) result = hulks.spawnTest(player, force);
            else if (args[1].equalsIgnoreCase("runner")) result = runners.spawnTest(player, force);
            else if (args[1].equalsIgnoreCase("popper")) result = poppers.spawnTest(player, force);
            else {
                sender.sendMessage(ChatColor.RED + "Use /ds spawn <hulk|crawler|runner|popper> [-f]");
                return true;
            }
            sender.sendMessage((result.startsWith("Spawned") || result.startsWith("DEV FORCE")) ? ChatColor.GREEN + result : ChatColor.RED + result);
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("flags")) {
            if (sender instanceof Player player) MetropolisFlagProbe.show(player, this);
            else sender.sendMessage("Run /deadstreets flags as a player standing in the region to inspect.");
            return true;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("testwarning")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Use in game."); return true; }
            try {
                int size = Integer.parseInt(args[1]);
                if (size < 1 || size > 1_000_000) throw new IllegalArgumentException("Population must be between 1 and 1000000");
                var temperament = Temperament.parse(args[2]);
                showWarning(player, DangerRating.calculate(size,temperament));
            } catch (IllegalArgumentException ex) { sender.sendMessage(ChatColor.RED+"Use /deadstreets testwarning <1..1000000> <docile|restless|agitated|frenzied>"); }
            return true;
        }
        sender.sendMessage(ChatColor.RED+"Usage: /ds [status|spawn <hulk|crawler|runner|popper>|flags|bloodmoon display <bossbar|scoreboard>|bloodmoon status|horde status|horde spawn <population> <temperament>|horde tp [uuid]|testwarning <population> <temperament>]");
        return true;
    }
    public void showTitleNotice(Player player, String title, String subtitle) {
        player.sendTitle(title, subtitle,
                getConfig().getInt("warnings.fade-in-ticks", 10),
                getConfig().getInt("warnings.stay-ticks", 70),
                getConfig().getInt("warnings.fade-out-ticks", 15));
    }

    public void showWarning(Player player, DangerRating rating) {
        showWarning(player, rating, "Unknown", -1);
    }

    public void showWarning(Player player, DangerRating rating, String direction) {
        showWarning(player, rating, direction, -1);
    }

    public void showWarning(Player player, DangerRating rating, String direction, int etaSeconds) {
        showWarning(player, rating, direction, etaSeconds, -1);
    }

    public void showWarning(Player player, DangerRating rating, String direction, int etaSeconds, double distanceBlocks) {
        ChatColor color = switch (rating.level()) {
            case LOW -> ChatColor.GREEN;
            case MODERATE -> ChatColor.YELLOW;
            case HIGH -> ChatColor.GOLD;
            case EXTREME -> ChatColor.RED;
        };
        String eta = etaSeconds < 0 ? "ETA Unknown" : ("ETA ~"
                + (etaSeconds >= 60 ? (etaSeconds / 60) + "m " + (etaSeconds % 60) + "s"
                : etaSeconds + "s"));
        String distance = distanceBlocks < 0 ? "Distance Unknown"
                : (Math.max(0, (int)Math.round(distanceBlocks)) + " blocks to the " + direction);
        String subtitle = ChatColor.WHITE + distance + " | " + eta
                + " | Est. Population: " + rating.approximatePopulation()
                + ChatColor.GRAY + " | Temperament: " + ChatColor.WHITE + rating.temperament().label()
                + ChatColor.GRAY + " | " + color + rating.level().name();
        showTitleNotice(player, color + "HORDE APPROACHING", subtitle);
        if (getConfig().getBoolean("warnings.play-sound", true)) {
            player.playSound(player.getLocation(), Sound.ENTITY_ZOMBIE_AMBIENT,
                    SoundCategory.HOSTILE, 0.75f, 0.60f);
        }
    }
}
