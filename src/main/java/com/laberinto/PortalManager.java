package com.laberinto;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;

/**
 * Portal de entrada fijo + portales de salida rotativos (antes era el plugin LaberintoPortales).
 * - Todos los portales se registran: lo que no este registrado dentro del laberinto se borra solo.
 * - Cualquier cantidad de jugadores puede usar el portal de entrada al mismo tiempo.
 */
final class PortalManager implements Listener {

    /** Rectangulo de bloques END_PORTAL a nivel y (el bloque donde pisa el jugador). */
    record Rect(String world, int x1, int z1, int x2, int z2, int y) {
        boolean containsBlock(int x, int by, int z) {
            return by == y && x >= x1 && x <= x2 && z >= z1 && z <= z2;
        }

        boolean contains(Location l, int yTol) {
            if (l == null || l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            int by = l.getBlockY();
            return by >= y - yTol && by <= y + yTol
                    && l.getBlockX() >= x1 && l.getBlockX() <= x2 && l.getBlockZ() >= z1 && l.getBlockZ() <= z2;
        }

        double cx() {
            return (x1 + x2 + 1) / 2.0;
        }

        double cz() {
            return (z1 + z2 + 1) / 2.0;
        }

        String ser() {
            return world + "|" + x1 + "|" + z1 + "|" + x2 + "|" + z2 + "|" + y;
        }

        static Rect parse(String s) {
            if (s == null) return null;
            String[] p = s.trim().split("\\|");
            if (p.length != 6) return null;
            try {
                return new Rect(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]),
                        Integer.parseInt(p[4]), Integer.parseInt(p[5]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private final JavaPlugin plugin;
    private final Random rnd = new Random();
    private final Map<UUID, Location[]> selections = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final List<Rect> exits = new ArrayList<>();
    private Rect entry;

    private LaberintoMobs.Zone zone;
    private Integer floorCache;
    private String floorSig;

    private boolean enabled = true, partExit = true, partEntry = true, cleanOnStart = true;
    private int maxSide = 8, exitMin = 3, exitMax = 4, exitSize = 2, sepMin = 20, cdSeconds = 3;
    private int warnSeconds = 60, triesRandom = 500, triesExit = 250, particleTicks = 5;
    private double rotMinutes = 20;
    private boolean particlesOk = true, warned, started;
    private long nextRotation;
    private BukkitTask task;
    private BukkitTask sweepTask;

    PortalManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- config / estado

    void load(FileConfiguration c, LaberintoMobs.Zone z) {
        this.zone = z;
        floorCache = null;
        enabled = c.getBoolean("portales.activo", true);
        maxSide = Math.max(1, c.getInt("portales.entrada.max-lado", 8));
        exitMin = Math.max(1, c.getInt("portales.salida.cantidad-min", 3));
        exitMax = Math.max(exitMin, c.getInt("portales.salida.cantidad-max", 4));
        exitSize = Math.max(1, c.getInt("portales.salida.tamano", 2));
        rotMinutes = Math.max(0.5, c.getDouble("portales.salida.rotar-minutos", 20));
        warnSeconds = Math.max(0, c.getInt("portales.salida.aviso-segundos", 60));
        sepMin = Math.max(0, c.getInt("portales.salida.separacion-minima", 20));
        cdSeconds = Math.max(1, c.getInt("portales.salida.cooldown-segundos", 3));
        triesRandom = Math.max(50, c.getInt("portales.avanzado.intentos-aleatorio", 500));
        triesExit = Math.max(50, c.getInt("portales.avanzado.intentos-salida", 250));
        partExit = c.getBoolean("portales.particulas.salida", true);
        partEntry = c.getBoolean("portales.particulas.entrada", true);
        particleTicks = Math.max(1, c.getInt("portales.particulas.intervalo-ticks", 5));
        cleanOnStart = c.getBoolean("portales.limpiar-al-iniciar", true);
    }

    private File stateFile() {
        return new File(plugin.getDataFolder(), "portales.yml");
    }

    private void saveState() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("entrada", entry == null ? "" : entry.ser());
        List<String> l = new ArrayList<>();
        for (Rect r : exits) l.add(r.ser());
        y.set("salidas", l);
        try {
            y.save(stateFile());
        } catch (IOException e) {
            plugin.getLogger().warning("No pude guardar portales.yml: " + e.getMessage());
        }
    }

    private void loadState() {
        entry = null;
        exits.clear();
        File f = stateFile();
        if (f.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            entry = Rect.parse(y.getString("entrada", ""));
            for (String s : y.getStringList("salidas")) {
                Rect r = Rect.parse(s);
                if (r != null) exits.add(r);
            }
            return;
        }
        // migracion desde el plugin LaberintoPortales (estado.properties)
        File old = new File(plugin.getDataFolder().getParentFile(), "LaberintoPortales/estado.properties");
        if (!old.exists()) return;
        Properties pr = new Properties();
        try (FileInputStream in = new FileInputStream(old)) {
            pr.load(in);
        } catch (IOException e) {
            plugin.getLogger().warning("No pude leer el estado del plugin de portales viejo: " + e.getMessage());
            return;
        }
        Rect e = Rect.parse(pr.getProperty("entrada", ""));
        if (e != null) {
            World w = Bukkit.getWorld(e.world());
            if (w != null && w.getBlockAt(e.x1(), e.y(), e.z1()).getType() == Material.END_PORTAL) {
                entry = e;
                plugin.getLogger().info("Portal de entrada importado del plugin LaberintoPortales: " + e.ser());
            } else {
                plugin.getLogger().warning("No pude importar el portal de entrada viejo. Crealo con /laberinto portal crear");
            }
        }
        for (String s : pr.getProperty("salidas", "").split(";")) {
            Rect r = Rect.parse(s);
            if (r != null) exits.add(r); // se borran al iniciar
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    void start() {
        if (task != null) task.cancel();
        if (!enabled) return;
        if (!started) {
            started = true;
            loadState();
            // al arrancar: borra las salidas viejas, limpia basura y genera salidas nuevas
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                World w = zone == null ? null : Bukkit.getWorld(zone.world());
                if (w == null || entry == null) return;
                rotate(false);
                if (cleanOnStart) sweepFull(null);
            }, 100L);
        }
        nextRotation = System.currentTimeMillis() + (long) (rotMinutes * 60_000L);
        warned = false;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, particleTicks);
    }

    void shutdown() {
        if (task != null) task.cancel();
        if (sweepTask != null) sweepTask.cancel();
        // las salidas no deben quedar en el mundo sin un plugin que las controle
        for (Rect r : exits) {
            World w = Bukkit.getWorld(r.world());
            if (w != null) removeBlocks(w, r);
        }
        saveState();
    }

    // ---------------------------------------------------------------- bloques

    private void placeBlocks(World w, Rect r) {
        for (int x = r.x1(); x <= r.x2(); x++) {
            for (int z = r.z1(); z <= r.z2(); z++) {
                Block b = w.getBlockAt(x, r.y(), z);
                if (b.getType() != Material.END_PORTAL) b.setType(Material.END_PORTAL, false);
            }
        }
    }

    private void removeBlocks(World w, Rect r) {
        for (int x = r.x1(); x <= r.x2(); x++) {
            for (int z = r.z1(); z <= r.z2(); z++) {
                Block b = w.getBlockAt(x, r.y(), z);
                if (b.getType() == Material.END_PORTAL) b.setType(Material.AIR, false);
            }
        }
    }

    private static boolean free(Block b) {
        return b.isPassable() && !b.isLiquid() && b.getType() != Material.END_PORTAL;
    }

    /** Suelo solido debajo y 2 bloques libres (el jugador puede estar de pie en y). */
    private boolean standable(World w, int x, int y, int z) {
        if (!w.getBlockAt(x, y - 1, z).isSolid()) return false;
        return free(w.getBlockAt(x, y, z)) && free(w.getBlockAt(x, y + 1, z));
    }

    /** Altura (y donde pisa el jugador) mas comun del suelo del laberinto. -1 si no se detecta. */
    private int floorY(World w) {
        String sig = zone.world() + zone.x1() + "," + zone.y1() + "," + zone.z1() + "," + zone.x2() + "," + zone.y2()
                + "," + zone.z2();
        if (floorCache != null && sig.equals(floorSig)) return floorCache;
        Map<Integer, Integer> count = new HashMap<>();
        for (int i = 0; i < 300; i++) {
            int x = zone.x1() + rnd.nextInt(zone.x2() - zone.x1() + 1);
            int z = zone.z1() + rnd.nextInt(zone.z2() - zone.z1() + 1);
            if (!w.isChunkLoaded(x >> 4, z >> 4) && i < 200) continue;
            for (int y = Math.max(zone.y1(), w.getMinHeight() + 1); y <= Math.min(zone.y2(), w.getMaxHeight() - 3); y++) {
                if (standable(w, x, y, z)) {
                    count.merge(y, 1, Integer::sum);
                    break;
                }
            }
        }
        int best = -1, bn = 0;
        for (Map.Entry<Integer, Integer> e : count.entrySet()) {
            if (e.getValue() > bn) {
                bn = e.getValue();
                best = e.getKey();
            }
        }
        if (best >= 0) {
            floorCache = best;
            floorSig = sig;
        }
        return best;
    }

    private boolean overlapsRegistered(int x1, int z1, int x2, int z2, int y, int gap) {
        List<Rect> all = new ArrayList<>(exits);
        if (entry != null) all.add(entry);
        for (Rect r : all) {
            if (r.y() != y && Math.abs(r.y() - y) > 1) continue;
            if (x1 <= r.x2() + gap && x2 >= r.x1() - gap && z1 <= r.z2() + gap && z2 >= r.z1() - gap) return true;
        }
        return false;
    }

    private Rect findExit(World w, int fy, int size, List<Rect> placedExits) {
        for (int t = 0; t < triesExit; t++) {
            int x = zone.x1() + rnd.nextInt(Math.max(1, zone.x2() - zone.x1() + 2 - size));
            int z = zone.z1() + rnd.nextInt(Math.max(1, zone.z2() - zone.z1() + 2 - size));
            if (t < triesExit / 2 && !w.isChunkLoaded(x >> 4, z >> 4)) continue;
            boolean ok = x + size - 1 <= zone.x2() && z + size - 1 <= zone.z2();
            for (int dx = 0; ok && dx < size; dx++) {
                for (int dz = 0; ok && dz < size; dz++) {
                    if (!standable(w, x + dx, fy, z + dz)) ok = false;
                }
            }
            if (!ok) continue;
            if (entry != null && overlapsRegistered(x, z, x + size - 1, z + size - 1, fy, 3)) continue;
            double cx = x + size / 2.0, cz = z + size / 2.0;
            for (Rect o : placedExits) {
                if (Math.hypot(o.cx() - cx, o.cz() - cz) < sepMin) {
                    ok = false;
                    break;
                }
            }
            if (ok) return new Rect(w.getName(), x, z, x + size - 1, z + size - 1, fy);
        }
        return null;
    }

    private Location randomGround(World w) {
        int fy = floorY(w);
        if (fy < 0) return null;
        for (int t = 0; t < triesRandom; t++) {
            int x = zone.x1() + rnd.nextInt(zone.x2() - zone.x1() + 1);
            int z = zone.z1() + rnd.nextInt(zone.z2() - zone.z1() + 1);
            if (t < triesRandom / 2 && !w.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (!standable(w, x, fy, z)) continue;
            if (overlapsRegistered(x, z, x, z, fy, 2)) continue;
            return new Location(w, x + 0.5, fy, z + 0.5);
        }
        return null;
    }

    // ---------------------------------------------------------------- rotacion

    /** Borra las salidas viejas y genera salidas nuevas. Tambien limpia portales huerfanos ya cargados. */
    void rotate(boolean announce) {
        if (!enabled || zone == null || entry == null) return;
        World w = Bukkit.getWorld(zone.world());
        if (w == null) return;

        for (Rect r : exits) {
            World rw = Bukkit.getWorld(r.world());
            if (rw != null) removeBlocks(rw, r);
        }
        exits.clear();

        int fy = floorY(w);
        if (fy < 0) {
            plugin.getLogger().warning("No pude detectar el suelo del laberinto (zona demasiado chica o sin suelo).");
            saveState();
            return;
        }
        int count = exitMin + rnd.nextInt(exitMax - exitMin + 1);
        int smaller = 0;
        for (int i = 0; i < count; i++) {
            Rect r = null;
            for (int s = exitSize; s >= 1 && r == null; s--) {
                r = findExit(w, fy, s, exits);
                if (r != null && s < exitSize) smaller++;
            }
            if (r == null) break;
            exits.add(r);
            placeBlocks(w, r);
        }
        if (exits.isEmpty()) {
            plugin.getLogger().warning("No encontre ningun lugar libre para los portales de salida.");
        } else if (smaller > 0) {
            plugin.getLogger().info("Algunas salidas son mas chicas porque los pasillos no tienen espacio.");
        }
        sweepLoaded(w);
        nextRotation = System.currentTimeMillis() + (long) (rotMinutes * 60_000L);
        warned = false;
        saveState();
        if (announce) {
            notifyZone("Los portales de salida cambiaron de lugar. Busca una nueva salida.", NamedTextColor.GOLD);
        }
    }

    // ---------------------------------------------------------------- limpieza de portales huerfanos

    private boolean registered(int x, int y, int z) {
        if (entry != null && entry.containsBlock(x, y, z)) return true;
        for (Rect r : exits) if (r.containsBlock(x, y, z)) return true;
        return false;
    }

    private int sweepChunk(Chunk ch, int yMin, int yMax) {
        int removed = 0;
        int bx = ch.getX() << 4, bz = ch.getZ() << 4;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int wx = bx + x, wz = bz + z;
                if (wx < zone.x1() || wx > zone.x2() || wz < zone.z1() || wz > zone.z2()) continue;
                for (int y = yMin; y <= yMax; y++) {
                    Block b = ch.getBlock(x, y, z);
                    if (b.getType() == Material.END_PORTAL && !registered(wx, y, wz)) {
                        b.setType(Material.AIR, false);
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    /** Barrido rapido: solo chunks ya cargados y solo cerca de la altura del suelo. */
    private void sweepLoaded(World w) {
        if (zone == null) return;
        int fy = floorCache != null ? floorCache : zone.y1();
        int removed = 0;
        for (Chunk ch : w.getLoadedChunks()) {
            int cx0 = ch.getX() << 4, cz0 = ch.getZ() << 4;
            if (cx0 + 15 < zone.x1() || cx0 > zone.x2() || cz0 + 15 < zone.z1() || cz0 > zone.z2()) continue;
            removed += sweepChunk(ch, Math.max(w.getMinHeight(), fy - 2), Math.min(w.getMaxHeight() - 1, fy + 3));
        }
        if (removed > 0) plugin.getLogger().info("Portales huerfanos eliminados: " + removed);
    }

    /** Barrido completo de toda la zona (carga los chunks de a poco). */
    void sweepFull(CommandSender who) {
        if (zone == null) return;
        World w = Bukkit.getWorld(zone.world());
        if (w == null) return;
        if (sweepTask != null) sweepTask.cancel();
        Deque<int[]> queue = new ArrayDeque<>();
        for (int cx = zone.x1() >> 4; cx <= zone.x2() >> 4; cx++) {
            for (int cz = zone.z1() >> 4; cz <= zone.z2() >> 4; cz++) queue.add(new int[]{cx, cz});
        }
        final int[] st = {0, 0}; // [0]=eliminados  [1]=pendientes
        final int yMin = Math.max(w.getMinHeight(), zone.y1() - 2);
        final int yMax = Math.min(w.getMaxHeight() - 1, zone.y2());
        sweepTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (int i = 0; i < 4 && !queue.isEmpty(); i++) {
                int[] c = queue.poll();
                st[1]++;
                w.getChunkAtAsync(c[0], c[1], false).thenAccept(ch ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (ch != null) st[0] += sweepChunk(ch, yMin, yMax);
                            st[1]--;
                        }));
            }
            if (queue.isEmpty() && st[1] <= 0) {
                sweepTask.cancel();
                sweepTask = null;
                if (st[0] > 0) plugin.getLogger().info("Limpieza de portales: " + st[0] + " bloques huerfanos eliminados.");
                if (who != null) {
                    who.sendMessage(Component.text("Limpieza lista: " + st[0] + " bloques de portal huerfanos eliminados.",
                            NamedTextColor.GREEN));
                }
            }
        }, 1L, 1L);
    }

    // ---------------------------------------------------------------- tick (rotacion, entrada, particulas)

    private void tick() {
        if (zone == null || entry == null) return;
        long now = System.currentTimeMillis();
        if (now >= nextRotation) {
            rotate(true);
        } else if (warnSeconds > 0 && !warned && nextRotation - now <= warnSeconds * 1000L) {
            warned = true;
            notifyZone("Los portales de salida cambiaran de lugar en " + ((nextRotation - now) / 1000) + " segundos.",
                    NamedTextColor.YELLOW);
        }
        // respaldo del evento: quien este dentro de un portal registrado, entra
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (rectAt(p.getLocation(), 0) != null) handleEnter(p);
        }
        if (cooldown.size() > 200) cooldown.values().removeIf(t -> t < now);
        drawParticles();
    }

    private Rect rectAt(Location l, int yTol) {
        if (entry != null && entry.contains(l, yTol)) return entry;
        for (Rect r : exits) if (r.contains(l, yTol)) return r;
        return null;
    }

    private void handleEnter(Player p) {
        long now = System.currentTimeMillis();
        Long until = cooldown.get(p.getUniqueId()); // el cooldown es POR JUGADOR: nadie bloquea a los demas
        if (until != null && until > now) return;
        Rect r = rectAt(p.getLocation(), 1);
        if (r == null) return;
        cooldown.put(p.getUniqueId(), now + cdSeconds * 1000L);
        if (r == entry) toMaze(p); else toEntry(p);
    }

    private void toMaze(Player p) {
        World w = zone == null ? null : Bukkit.getWorld(zone.world());
        if (w == null) {
            p.sendMessage(Component.text("El laberinto no esta configurado todavia.", NamedTextColor.RED));
            return;
        }
        Location dest = randomGround(w);
        if (dest == null) {
            p.sendMessage(Component.text("No encontre un lugar seguro dentro del laberinto. Avisa a un admin.",
                    NamedTextColor.RED));
            return;
        }
        p.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.setFallDistance(0f);
        p.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        p.sendMessage(Component.text("Entraste al laberinto. Hay " + exits.size()
                + " portales de salida y cambian cada " + fmtMin(rotMinutes) + " minutos.", NamedTextColor.GOLD));
    }

    private void toEntry(Player p) {
        Location dest = entryLanding();
        if (dest == null) return;
        p.teleport(dest, PlayerTeleportEvent.TeleportCause.PLUGIN);
        p.setFallDistance(0f);
        p.playSound(dest, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1.2f);
        p.sendMessage(Component.text("Saliste del laberinto.", NamedTextColor.GREEN));
    }

    /** Lugar libre pegado al portal de entrada (hasta 3 bloques alrededor). */
    private Location entryLanding() {
        if (entry == null) return null;
        World w = Bukkit.getWorld(entry.world());
        if (w == null) return null;
        List<Location> best = new ArrayList<>();
        int bestD = Integer.MAX_VALUE;
        for (int x = entry.x1() - 3; x <= entry.x2() + 3; x++) {
            for (int z = entry.z1() - 3; z <= entry.z2() + 3; z++) {
                int dx = Math.max(Math.max(entry.x1() - x, x - entry.x2()), 0);
                int dz = Math.max(Math.max(entry.z1() - z, z - entry.z2()), 0);
                int d = Math.max(dx, dz);
                if (d == 0 || d > bestD) continue;
                if (!standable(w, x, entry.y(), z)) continue;
                if (d < bestD) {
                    bestD = d;
                    best.clear();
                }
                best.add(new Location(w, x + 0.5, entry.y(), z + 0.5));
            }
        }
        if (best.isEmpty()) return new Location(w, entry.cx(), entry.y() + 1, entry.cz());
        return best.get(rnd.nextInt(best.size()));
    }

    private static String fmtMin(double m) {
        return m == Math.floor(m) ? String.valueOf((int) m) : String.format("%.1f", m);
    }

    private void notifyZone(String text, NamedTextColor color) {
        if (zone == null) return;
        Component c = Component.text(text, color);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (zone.contains(p.getLocation())) p.sendMessage(c);
        }
    }

    // ---------------------------------------------------------------- particulas

    private boolean anyNear(World w, double x, double y, double z) {
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(new Location(w, x, y, z)) < 48 * 48) return true;
        }
        return false;
    }

    private void drawParticles() {
        if (!particlesOk || (!partExit && !partEntry)) return;
        try {
            if (partEntry && entry != null) draw(entry, true);
            if (partExit) for (Rect r : exits) draw(r, false);
        } catch (Throwable t) {
            particlesOk = false;
            plugin.getLogger().warning("Particulas desactivadas por un error del API: " + t.getMessage());
        }
    }

    private void draw(Rect r, boolean isEntry) {
        World w = Bukkit.getWorld(r.world());
        if (w == null || !anyNear(w, r.cx(), r.y(), r.cz())) return;
        double hx = (r.x2() - r.x1() + 1) / 2.0, hz = (r.z2() - r.z1() + 1) / 2.0;
        if (isEntry) {
            w.spawnParticle(Particle.PORTAL, r.cx(), r.y() + 1.0, r.cz(), 18, hx, 0.8, hz, 0.6);
            w.spawnParticle(Particle.REVERSE_PORTAL, r.cx(), r.y() + 0.6, r.cz(), 6, hx, 0.4, hz, 0.02);
        } else {
            w.spawnParticle(Particle.END_ROD, r.cx(), r.y() + 1.2, r.cz(), 3, hx * 0.8, 0.9, hz * 0.8, 0.01);
            w.spawnParticle(Particle.PORTAL, r.cx(), r.y() + 0.8, r.cz(), 14, hx, 0.6, hz, 0.5);
            w.spawnParticle(Particle.REVERSE_PORTAL, r.cx(), r.y() + 0.5, r.cz(), 4, hx, 0.3, hz, 0.02);
        }
    }

    // ---------------------------------------------------------------- eventos

    /** Todo portal del End dentro del laberinto lo controla este plugin: nadie viaja al End por error. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPortal(PlayerPortalEvent e) {
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.END_PORTAL) return;
        Location from = e.getFrom();
        Rect r = rectAt(from, 1);
        boolean inZone = zone != null && zone.contains(from);
        if (r == null && !inZone) return; // portal del End normal, fuera de nuestro laberinto
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (r != null) {
            Bukkit.getScheduler().runTask(plugin, () -> handleEnter(p));
        } else {
            p.sendMessage(Component.text("Ese portal ya no existe, se esta limpiando.", NamedTextColor.YELLOW));
            Bukkit.getScheduler().runTask(plugin, () -> {
                World w = Bukkit.getWorld(zone.world());
                if (w != null) sweepLoaded(w);
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityPortal(EntityPortalEvent e) {
        if (zone == null || e.getFrom() == null) return;
        if (rectAt(e.getFrom(), 1) != null || zone.contains(e.getFrom())
                && e.getFrom().getBlock().getType() == Material.END_PORTAL) {
            e.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- comandos

    private static void say(CommandSender s, String text, NamedTextColor color) {
        s.sendMessage(Component.text(text, color));
    }

    private Block pickBlock(Player p) {
        Block b = p.getTargetBlockExact(6);
        if (b == null || b.getType().isAir()) b = p.getLocation().getBlock().getRelative(0, -1, 0);
        return b;
    }

    void command(CommandSender sender, String[] args, int off) {
        String sub = args.length > off ? args[off].toLowerCase() : "";
        switch (sub) {
            case "pos1", "pos2" -> {
                if (!(sender instanceof Player p)) {
                    say(sender, "Solo jugadores.", NamedTextColor.RED);
                    return;
                }
                Block b = pickBlock(p);
                Location[] sel = selections.computeIfAbsent(p.getUniqueId(), k -> new Location[2]);
                sel[sub.equals("pos1") ? 0 : 1] = b.getLocation();
                say(sender, sub + " guardada (bloque de SUELO) = " + b.getX() + ", " + b.getY() + ", " + b.getZ()
                        + ". Mira el suelo donde va una esquina del portal.", NamedTextColor.GREEN);
            }
            case "crear" -> create(sender);
            case "borrar" -> {
                World w = zone == null ? null : Bukkit.getWorld(zone.world());
                for (Rect r : exits) {
                    World rw = Bukkit.getWorld(r.world());
                    if (rw != null) removeBlocks(rw, r);
                }
                exits.clear();
                if (entry != null) {
                    World ew = Bukkit.getWorld(entry.world());
                    if (ew != null) removeBlocks(ew, entry);
                }
                entry = null;
                saveState();
                say(sender, "Portal de entrada y portales de salida eliminados.", NamedTextColor.GREEN);
            }
            case "rotar" -> {
                if (entry == null) {
                    say(sender, "Primero crea el portal de entrada (/laberinto portal crear).", NamedTextColor.RED);
                    return;
                }
                rotate(true);
                say(sender, "Portales de salida regenerados: " + exits.size(), NamedTextColor.GREEN);
            }
            case "limpiar" -> {
                if (zone == null) {
                    say(sender, "El laberinto no esta configurado todavia.", NamedTextColor.RED);
                    return;
                }
                say(sender, "Buscando portales huerfanos en todo el laberinto (tarda unos segundos)...",
                        NamedTextColor.YELLOW);
                sweepFull(sender);
            }
            case "info" -> info(sender);
            default -> say(sender, "Uso: /laberinto portal <pos1|pos2|crear|borrar|rotar|limpiar|info>",
                    NamedTextColor.YELLOW);
        }
    }

    void info(CommandSender s) {
        say(s, entry == null ? "Entrada: sin crear." : "Entrada: " + entry.x1() + "," + entry.y() + "," + entry.z1()
                + " -> " + entry.x2() + "," + entry.z2(), NamedTextColor.AQUA);
        say(s, "Salidas activas: " + exits.size(), NamedTextColor.AQUA);
        for (Rect r : exits) {
            say(s, "  salida en " + r.x1() + "," + r.y() + "," + r.z1() + " (" + (r.x2() - r.x1() + 1) + "x"
                    + (r.z2() - r.z1() + 1) + ")", NamedTextColor.GRAY);
        }
        long left = Math.max(0, nextRotation - System.currentTimeMillis());
        say(s, "Proxima rotacion en " + (left / 60000) + " min " + ((left / 1000) % 60) + " s", NamedTextColor.AQUA);
    }

    private void create(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            say(sender, "Solo jugadores.", NamedTextColor.RED);
            return;
        }
        Location[] sel = selections.get(p.getUniqueId());
        if (sel == null || sel[0] == null || sel[1] == null) {
            say(sender, "Marca primero las dos esquinas del suelo con /laberinto portal pos1 y pos2.",
                    NamedTextColor.RED);
            return;
        }
        if (!sel[0].getWorld().equals(sel[1].getWorld())) {
            say(sender, "pos1 y pos2 estan en mundos distintos.", NamedTextColor.RED);
            return;
        }
        World w = sel[0].getWorld();
        if (sel[0].getBlockY() != sel[1].getBlockY()) {
            say(sender, "pos1 y pos2 estan a distinta altura: uso la altura de pos1 (" + sel[0].getBlockY() + ").",
                    NamedTextColor.YELLOW);
        }
        int floor = sel[0].getBlockY();
        int x1 = Math.min(sel[0].getBlockX(), sel[1].getBlockX()), x2 = Math.max(sel[0].getBlockX(), sel[1].getBlockX());
        int z1 = Math.min(sel[0].getBlockZ(), sel[1].getBlockZ()), z2 = Math.max(sel[0].getBlockZ(), sel[1].getBlockZ());
        if (x2 - x1 + 1 > maxSide || z2 - z1 + 1 > maxSide) {
            say(sender, "El portal es demasiado grande (maximo " + maxSide + ").", NamedTextColor.RED);
            return;
        }
        for (int x = x1; x <= x2; x++) {
            for (int z = z1; z <= z2; z++) {
                if (!w.getBlockAt(x, floor, z).isSolid() || !free(w.getBlockAt(x, floor + 1, z))
                        && w.getBlockAt(x, floor + 1, z).getType() != Material.END_PORTAL) {
                    say(sender, "En " + x + "," + z + " el suelo no es solido o hay un bloque encima del suelo.",
                            NamedTextColor.RED);
                    return;
                }
            }
        }
        if (entry != null) {
            World ew = Bukkit.getWorld(entry.world());
            if (ew != null) removeBlocks(ew, entry);
        }
        entry = new Rect(w.getName(), x1, z1, x2, z2, floor + 1);
        placeBlocks(w, entry);
        saveState();
        say(sender, "Portal de entrada creado (" + (x2 - x1 + 1) + "x" + (z2 - z1 + 1) + ").", NamedTextColor.GREEN);
        if (zone != null) {
            rotate(false);
            say(sender, "Portales de salida generados: " + exits.size(), NamedTextColor.GREEN);
        } else {
            say(sender, "Falta configurar el laberinto con /laberinto pos1 y pos2 para generar las salidas.",
                    NamedTextColor.YELLOW);
        }
    }

    List<String> complete(String[] args, int off) {
        if (args.length == off + 1) return List.of("pos1", "pos2", "crear", "borrar", "rotar", "limpiar", "info");
        return List.of();
    }
}
