package com.laberinto;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class LaberintoMobs extends JavaPlugin implements Listener, TabExecutor {

    record Zone(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        boolean contains(Location l) {
            if (l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            return contains(l.getX(), l.getY(), l.getZ());
        }

        boolean contains(double x, double y, double z) {
            return x >= x1 && x < x2 + 1 && y >= y1 && y < y2 + 1 && z >= z1 && z < z2 + 1;
        }
    }

    private final LootManager loot = new LootManager(this);
    private final DifficultyManager diff = new DifficultyManager(this);
    private final Habilidades habilidades = new Habilidades(this);
    private final Map<UUID, Mob> mobs = new HashMap<>();
    private final Random rnd = new Random();

    private Zone zone;
    private long emptySince = -1;
    private Location pos1, pos2;

    private int intervalTicks, deleteAfterSeconds;
    private double minDist, maxDist;
    private boolean protectFire = true;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // agrega al config.yml existente las secciones nuevas (ej. loot) sin tocar lo que ya tenias
        getConfig().options().copyDefaults(true);
        saveConfig();
        loadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(loot, this);
        habilidades.setLoot(loot);
        getServer().getPluginManager().registerEvents(habilidades, this);
        var cmd = getCommand("laberinto");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        var nivel = getCommand("nivel");
        if (nivel != null) nivel.setExecutor(this);
        startTask();
        if (zone == null) {
            getLogger().warning("La zona del laberinto aun no esta configurada. Usa /laberinto pos1 y /laberinto pos2.");
        }
    }

    @Override
    public void onDisable() {
        getServer().getScheduler().cancelTasks(this);
        diff.stop();
        loot.removeAll();
        removeAll();
    }

    private void startTask() {
        getServer().getScheduler().cancelTasks(this);
        getServer().getScheduler().runTaskTimer(this, this::tick, 40L, Math.max(5, intervalTicks));
        loot.startTasks();
        diff.startTasks();
        habilidades.startTasks();
    }

    private void loadSettings() {
        reloadConfig();
        FileConfiguration c = getConfig();

        if (c.getBoolean("zona.activa", false)) {
            int ax = c.getInt("zona.min.x"), ay = c.getInt("zona.min.y"), az = c.getInt("zona.min.z");
            int bx = c.getInt("zona.max.x"), by = c.getInt("zona.max.y"), bz = c.getInt("zona.max.z");
            zone = new Zone(c.getString("zona.mundo", "world"),
                    Math.min(ax, bx), Math.min(ay, by), Math.min(az, bz),
                    Math.max(ax, bx), Math.max(ay, by), Math.max(az, bz));
        } else {
            zone = null;
        }

        intervalTicks = c.getInt("spawn.intervalo-ticks", 30);
        minDist = c.getDouble("spawn.distancia-min", 8);
        maxDist = Math.max(minDist + 1, c.getDouble("spawn.distancia-max", 28));
        deleteAfterSeconds = c.getInt("spawn.borrar-tras-segundos", 10);
        protectFire = c.getBoolean("proteccion.fuego", true);

        diff.load(c, zone);
        loot.load(c, zone);
        loot.setLevelLookup(diff::levelOf);
        loot.setLevel(diff.level());
        habilidades.load(c);
    }

    // ---------------------------------------------------------------- bucle principal

    private void tick() {
        if (zone == null) return;
        World w = Bukkit.getWorld(zone.world());
        if (w == null) return;

        mobs.values().removeIf(m -> !m.isValid());
        LevelConfig lv = diff.level();

        List<Player> active = new ArrayList<>();
        for (Player p : w.getPlayers()) {
            GameMode gm = p.getGameMode();
            if ((gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE) && !p.isDead() && zone.contains(p.getLocation())) {
                active.add(p);
            }
        }

        if (active.isEmpty()) {
            if (mobs.isEmpty()) {
                emptySince = -1;
            } else if (emptySince < 0) {
                emptySince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - emptySince >= deleteAfterSeconds * 1000L) {
                removeAll();
                emptySince = -1;
            }
            return;
        }
        emptySince = -1;

        // mantener mobs dentro de la zona y apuntando a un jugador
        for (Mob m : new ArrayList<>(mobs.values())) {
            if (!zone.contains(m.getLocation())) {
                m.remove();
                mobs.remove(m.getUniqueId());
                continue;
            }
            LivingEntity t = m.getTarget();
            if (!(t instanceof Player tp) || tp.isDead() || !active.contains(tp)) {
                m.setTarget(nearest(active, m.getLocation()));
            }
        }

        // aparecer mobs nuevos
        if (lv.types.isEmpty()) return;
        List<Player> order = new ArrayList<>(active);
        Collections.shuffle(order, rnd);
        int spawned = 0;
        for (Player p : order) {
            int near = countNear(p, 48);
            int tries = 0;
            while (spawned < lv.perCycle && mobs.size() < lv.maxTotal && near < lv.maxPerPlayer && tries < 8) {
                tries++;
                if (spawnOne(p, w, lv)) {
                    spawned++;
                    near++;
                }
            }
            if (spawned >= lv.perCycle || mobs.size() >= lv.maxTotal) break;
        }
    }

    private Player nearest(List<Player> players, Location from) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : players) {
            double d = p.getLocation().distanceSquared(from);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private int countNear(Player p, double radius) {
        double r2 = radius * radius;
        int n = 0;
        for (Mob m : mobs.values()) {
            if (m.getWorld().equals(p.getWorld()) && m.getLocation().distanceSquared(p.getLocation()) <= r2) n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- spawn

    private boolean spawnOne(Player target, World w, LevelConfig lv) {
        EntityType type = lv.pickType(rnd);
        if (type == null) return false;
        Location loc = findSpot(target, type, w);
        if (loc == null) return false;

        Entity e = w.spawnEntity(loc, type);
        if (!(e instanceof Mob m)) {
            e.remove();
            return false;
        }
        m.setPersistent(false);          // no se guarda en el disco
        m.setRemoveWhenFarAway(false);
        if (lv.strength > 0) {
            m.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH,
                    PotionEffect.INFINITE_DURATION, lv.strength - 1, false, false, false));
        }
        if (lv.speed > 0) {
            m.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,
                    PotionEffect.INFINITE_DURATION, lv.speed - 1, false, false, false));
        }
        if (Math.abs(lv.health - 1.0) > 0.01) {
            AttributeInstance ai = m.getAttribute(Attribute.MAX_HEALTH);
            if (ai != null) {
                ai.setBaseValue(ai.getBaseValue() * lv.health);
                m.setHealth(ai.getValue());
            }
        }
        m.setTarget(target);
        mobs.put(m.getUniqueId(), m);
        return true;
    }

    private Location findSpot(Player p, EntityType type, World w) {
        int clearance = (type == EntityType.RAVAGER || type == EntityType.ZOGLIN
                || type == EntityType.HOGLIN || type == EntityType.SPIDER) ? 1 : 0; // el Ravager es ancho: necesita espacio
        Location pl = p.getLocation();
        int baseY = pl.getBlockY();
        int[] offsets = {0, -1, 1, -2};
        for (int a = 0; a < 12; a++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double dist = minDist + rnd.nextDouble() * (maxDist - minDist);
            int x = (int) Math.floor(pl.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(pl.getZ() + Math.sin(ang) * dist);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            for (int off : offsets) {
                int y = baseY + off;
                if (!zone.contains(x + 0.5, y, z + 0.5)) continue;
                if (fits(w, x, y, z, clearance)) return new Location(w, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    private boolean fits(World w, int x, int y, int z, int r) {
        if (!w.getBlockAt(x, y - 1, z).isSolid()) return false;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    Block b = w.getBlockAt(x + dx, y + dy, z + dz);
                    if (!b.isPassable() || b.isLiquid()) return false;
                }
            }
        }
        return true;
    }

    private void removeAll() {
        for (Mob m : mobs.values()) {
            if (m.isValid()) m.remove();
        }
        mobs.clear();
    }

    // ---------------------------------------------------------------- eventos

    private boolean tracked(Entity e) {
        return e != null && mobs.containsKey(e.getUniqueId());
    }

    /** Los mobs del laberinto solo atacan jugadores. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (tracked(e.getEntity()) && e.getTarget() != null && !(e.getTarget() instanceof Player)) {
            e.setCancelled(true);
        }
    }

    /** Los mobs del laberinto no se hacen dano entre si. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!tracked(e.getEntity())) return;
        Entity src = e.getDamager();
        if (src instanceof Projectile pr) {
            ProjectileSource ps = pr.getShooter();
            if (ps instanceof Entity se) src = se;
        } else if (src instanceof EvokerFangs fangs && fangs.getOwner() != null) {
            src = fangs.getOwner();
        }
        if (tracked(src)) e.setCancelled(true);
    }

    /** Los endermen no pueden teletransportarse fuera de la zona. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(EntityTeleportEvent e) {
        if (!tracked(e.getEntity()) || zone == null) return;
        Location to = e.getTo();
        if (to == null || !zone.contains(to)) e.setCancelled(true);
    }

    /** Los endermen no se llevan bloques del laberinto. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockChange(EntityChangeBlockEvent e) {
        if (tracked(e.getEntity()) && e.getEntityType() == EntityType.ENDERMAN) e.setCancelled(true);
    }

    /** Recompensa por matar mobs: mas XP y, a veces, un premio extra segun la dificultad. */
    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Mob dead = mobs.remove(e.getEntity().getUniqueId());
        if (dead == null) return;
        LevelConfig lv = diff.level();
        if (Math.abs(lv.xpMult - 1.0) > 0.01) {
            e.setDroppedExp((int) Math.round(e.getDroppedExp() * lv.xpMult));
        }
        if (lv.dropChance > 0 && rnd.nextDouble() < lv.dropChance) {
            for (ItemStack it : loot.rollForMob(lv.dropMax)) e.getDrops().add(it);
        }
    }

    /** Los fuegos de bolas de fuego, rayos y flechas no queman el laberinto. */
    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (!protectFire || zone == null || !zone.contains(e.getBlock().getLocation())) return;
        switch (e.getCause()) {
            case FIREBALL, LIGHTNING, EXPLOSION, ARROW, SPREAD -> e.setCancelled(true);
            default -> {
            }
        }
    }

    /** Llamado por DifficultyManager cuando cambia el nivel. */
    void onDifficultyChanged(LevelConfig lv) {
        if (diff.clearMobsOnChange()) removeAll();
        loot.setLevel(lv);
        loot.regenerate();
    }

    // ---------------------------------------------------------------- comandos

    private void msg(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(Component.text(text, color));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("nivel")) {
            diff.info(sender);
            return true;
        }
        if (args.length == 0) {
            msg(sender, "Uso: /laberinto <pos1|pos2|info|limpiar|dificultad|loot|reload>", NamedTextColor.YELLOW);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "pos1", "pos2" -> {
                if (!(sender instanceof Player p)) {
                    msg(sender, "Solo jugadores.", NamedTextColor.RED);
                    return true;
                }
                Location l = p.getLocation().getBlock().getLocation();
                if (args[0].equalsIgnoreCase("pos1")) pos1 = l; else pos2 = l;
                msg(sender, args[0] + " guardada en " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ(),
                        NamedTextColor.GREEN);
                if (pos1 != null && pos2 != null) {
                    if (!pos1.getWorld().equals(pos2.getWorld())) {
                        msg(sender, "pos1 y pos2 estan en mundos distintos.", NamedTextColor.RED);
                        return true;
                    }
                    FileConfiguration c = getConfig();
                    c.set("zona.activa", true);
                    c.set("zona.mundo", pos1.getWorld().getName());
                    c.set("zona.min.x", Math.min(pos1.getBlockX(), pos2.getBlockX()));
                    c.set("zona.min.y", Math.min(pos1.getBlockY(), pos2.getBlockY()));
                    c.set("zona.min.z", Math.min(pos1.getBlockZ(), pos2.getBlockZ()));
                    c.set("zona.max.x", Math.max(pos1.getBlockX(), pos2.getBlockX()));
                    c.set("zona.max.y", Math.max(pos1.getBlockY(), pos2.getBlockY()) + 5);
                    c.set("zona.max.z", Math.max(pos1.getBlockZ(), pos2.getBlockZ()));
                    saveConfig();
                    loadSettings();
                    loot.regenerate();
                    msg(sender, "Zona guardada y ACTIVA. Los mobs apareceran solos cuando alguien entre.",
                            NamedTextColor.GREEN);
                }
            }
            case "info" -> {
                if (zone == null) {
                    msg(sender, "Zona sin configurar.", NamedTextColor.RED);
                } else {
                    msg(sender, "Zona: " + zone.world() + " (" + zone.x1() + "," + zone.y1() + "," + zone.z1()
                            + ") -> (" + zone.x2() + "," + zone.y2() + "," + zone.z2() + ")", NamedTextColor.AQUA);
                    msg(sender, "Mobs activos: " + mobs.size(), NamedTextColor.AQUA);
                    msg(sender, "Botines activos: " + loot.count(), NamedTextColor.AQUA);
                    diff.info(sender);
                }
            }
            case "limpiar" -> {
                removeAll();
                msg(sender, "Mobs del laberinto eliminados.", NamedTextColor.GREEN);
            }
            case "loot" -> loot.command(sender, args);
            case "dificultad" -> diff.command(sender, args);
            case "reload" -> {
                loadSettings();
                startTask();
                msg(sender, "Configuracion recargada.", NamedTextColor.GREEN);
            }
            default -> msg(sender, "Uso: /laberinto <pos1|pos2|info|limpiar|dificultad|loot|reload>", NamedTextColor.YELLOW);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("pos1", "pos2", "info", "limpiar", "dificultad", "loot", "reload");
        if (args.length == 2 && args[0].equalsIgnoreCase("loot"))
            return List.of("regenerar", "limpiar", "chances", "ubicaciones");
        if (args.length == 2 && args[0].equalsIgnoreCase("dificultad"))
            return List.of("facil", "medio", "dificil", "rotar", "pausar", "reanudar", "info");
        if (args.length == 3 && args[0].equalsIgnoreCase("loot") && args[1].equalsIgnoreCase("chances"))
            return List.of("facil", "medio", "dificil");
        return List.of();
    }
}
