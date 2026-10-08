package com.laberinto;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Rota la dificultad del laberinto (FACIL / MEDIO / DIFICIL) cada X minutos.
 * Cambia mobs, botin y recompensas, y lo avisa a TODO el servidor.
 */
final class DifficultyManager {

    private final LaberintoMobs plugin;
    private final Random rnd = new Random();
    private final Map<Difficulty, LevelConfig> levels = new EnumMap<>(Difficulty.class);
    private final Map<Difficulty, Double> randomWeights = new EnumMap<>(Difficulty.class);
    private LevelConfig base;
    private LaberintoMobs.Zone zone;

    private Difficulty diff = Difficulty.MEDIO;
    private boolean enabled = true, cycleMode, clearMobs = true, useBar = true, paused;
    private boolean stateLoaded, warned;
    private long periodMs = 30 * 60_000L;
    private long nextChange;
    private long frozenLeft;
    private int warnSeconds = 60;

    private BossBar bar;
    private final Set<UUID> barViewers = new HashSet<>();

    DifficultyManager(LaberintoMobs plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- config

    void load(FileConfiguration c, LaberintoMobs.Zone z) {
        this.zone = z;
        enabled = c.getBoolean("dificultad.activa", true);
        periodMs = (long) (Math.max(0.5, c.getDouble("dificultad.cambiar-cada-minutos", 30)) * 60_000L);
        cycleMode = "CICLO".equalsIgnoreCase(c.getString("dificultad.modo", "ALEATORIO"));
        warnSeconds = Math.max(0, c.getInt("dificultad.aviso-previo-segundos", 60));
        clearMobs = c.getBoolean("dificultad.limpiar-mobs-al-cambiar", true);
        useBar = c.getBoolean("dificultad.barra", true);

        randomWeights.clear();
        for (Difficulty d : Difficulty.values()) {
            randomWeights.put(d, Math.max(0, c.getDouble("dificultad.pesos-aleatorio." + d.name(), 1)));
        }

        base = LevelConfig.base(c, plugin.getLogger());
        levels.clear();
        for (Difficulty d : Difficulty.values()) {
            levels.put(d, LevelConfig.level(c, d, base, plugin.getLogger()));
        }

        if (!stateLoaded) {
            stateLoaded = true;
            readState(c);
        }
        if (!useBar) hideBar();
    }

    LevelConfig level() {
        return enabled ? levels.get(diff) : base;
    }

    LevelConfig levelOf(Difficulty d) {
        return d == null ? level() : levels.get(d);
    }

    Difficulty difficulty() {
        return diff;
    }

    boolean clearMobsOnChange() {
        return clearMobs;
    }

    // ---------------------------------------------------------------- estado en disco

    private File stateFile() {
        return new File(plugin.getDataFolder(), "estado.yml");
    }

    private void readState(FileConfiguration c) {
        long remaining = periodMs;
        Difficulty d = null;
        File f = stateFile();
        if (f.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            d = Difficulty.parse(y.getString("dificultad"));
            paused = y.getBoolean("pausada", false);
            remaining = y.getLong("restante-ms", periodMs);
        }
        if (d == null) d = Difficulty.parse(c.getString("dificultad.inicial", "MEDIO"));
        diff = d == null ? Difficulty.MEDIO : d;
        remaining = Math.max(1000L, Math.min(remaining, periodMs));
        nextChange = System.currentTimeMillis() + remaining;
        frozenLeft = remaining;
    }

    void saveState() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("dificultad", diff.name());
        y.set("pausada", paused);
        y.set("restante-ms", paused ? frozenLeft : Math.max(0, nextChange - System.currentTimeMillis()));
        try {
            y.save(stateFile());
        } catch (IOException ex) {
            plugin.getLogger().warning("No pude guardar estado.yml: " + ex.getMessage());
        }
    }

    // ---------------------------------------------------------------- tareas

    void startTasks() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    void stop() {
        saveState();
        hideBar();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (enabled) {
            if (paused) {
                nextChange = now + frozenLeft;
            } else {
                long left = nextChange - now;
                if (left <= 0) {
                    change(pickNext(), false);
                } else if (warnSeconds > 0 && !warned && left <= warnSeconds * 1000L) {
                    warned = true;
                    warnZone(left);
                }
            }
        }
        updateBar(now);
    }

    private Difficulty pickNext() {
        if (cycleMode) return diff.next();
        double total = 0;
        for (Difficulty d : Difficulty.values()) {
            if (d != diff) total += randomWeights.getOrDefault(d, 1.0);
        }
        if (total <= 0) return diff.next();
        double r = rnd.nextDouble() * total;
        for (Difficulty d : Difficulty.values()) {
            if (d == diff) continue;
            r -= randomWeights.getOrDefault(d, 1.0);
            if (r < 0) return d;
        }
        return diff.next();
    }

    /** Cambia la dificultad, avisa a todo el servidor y reinicia mobs y botin. */
    void change(Difficulty d, boolean manual) {
        Difficulty old = diff;
        diff = d;
        nextChange = System.currentTimeMillis() + periodMs;
        frozenLeft = periodMs;
        warned = false;
        saveState();
        announce(old, d, manual);
        plugin.onDifficultyChanged(level());
    }

    // ---------------------------------------------------------------- avisos

    private void announce(Difficulty old, Difficulty now, boolean manual) {
        LevelConfig lv = levels.get(now);
        Component head = Component.text("[Laberinto] ", NamedTextColor.GOLD).decorate(TextDecoration.BOLD)
                .append(Component.text("La dificultad cambio a ", NamedTextColor.WHITE)
                        .decoration(TextDecoration.BOLD, false))
                .append(Component.text(lv.name.toUpperCase(), now.color).decorate(TextDecoration.BOLD));
        Bukkit.broadcast(head);
        if (!lv.description.isEmpty()) {
            Bukkit.broadcast(Component.text("[Laberinto] " + lv.description, NamedTextColor.GRAY));
        }

        Title title = Title.title(
                Component.text("Laberinto: " + lv.name, now.color).decorate(TextDecoration.BOLD),
                Component.text(lv.description, NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(4), Duration.ofMillis(900)));
        Sound sound = switch (now) {
            case FACIL -> Sound.ENTITY_PLAYER_LEVELUP;
            case MEDIO -> Sound.BLOCK_BEACON_ACTIVATE;
            case DIFICIL -> Sound.ENTITY_WITHER_SPAWN;
        };
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            p.playSound(p.getLocation(), sound, 0.8f, 1f);
        }
    }

    private void warnZone(long leftMs) {
        if (zone == null) return;
        Component msg = Component.text("La dificultad del laberinto cambia en " + (leftMs / 1000)
                + " segundos...", NamedTextColor.GOLD);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (zone.contains(p.getLocation())) p.sendMessage(msg);
        }
    }

    // ---------------------------------------------------------------- barra de jefe

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format("%d:%02d", s / 60, s % 60);
    }

    private void updateBar(long now) {
        if (!enabled || !useBar || zone == null) {
            hideBar();
            return;
        }
        long left = paused ? frozenLeft : Math.max(0, nextChange - now);
        Component name = Component.text("Laberinto: ", NamedTextColor.WHITE)
                .append(Component.text(levels.get(diff).name, diff.color).decorate(TextDecoration.BOLD))
                .append(Component.text(paused ? "  (en pausa)" : "  - cambia en " + fmt(left), NamedTextColor.GRAY));
        float progress = (float) Math.max(0, Math.min(1, (double) left / periodMs));
        if (bar == null) {
            bar = BossBar.bossBar(name, progress, diff.barColor, BossBar.Overlay.PROGRESS);
        } else {
            bar.name(name);
            bar.progress(progress);
            bar.color(diff.barColor);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean inside = zone.contains(p.getLocation());
            boolean shown = barViewers.contains(p.getUniqueId());
            if (inside && !shown) {
                p.showBossBar(bar);
                barViewers.add(p.getUniqueId());
            } else if (!inside && shown) {
                p.hideBossBar(bar);
                barViewers.remove(p.getUniqueId());
            }
        }
        barViewers.removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private void hideBar() {
        if (bar != null) {
            for (UUID id : barViewers) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) p.hideBossBar(bar);
            }
        }
        barViewers.clear();
    }

    // ---------------------------------------------------------------- comandos

    void info(CommandSender s) {
        if (!enabled) {
            s.sendMessage(Component.text("El sistema de dificultad esta desactivado (dificultad.activa).",
                    NamedTextColor.RED));
            return;
        }
        LevelConfig lv = levels.get(diff);
        long left = paused ? frozenLeft : Math.max(0, nextChange - System.currentTimeMillis());
        s.sendMessage(Component.text("Dificultad del laberinto: ", NamedTextColor.WHITE)
                .append(Component.text(lv.name, diff.color).decorate(TextDecoration.BOLD)));
        s.sendMessage(Component.text(paused ? "Cambio automatico en PAUSA." : "Cambia en " + fmt(left)
                + " (" + (cycleMode ? "ciclo" : "aleatorio") + ")", NamedTextColor.GRAY));
        if (!lv.description.isEmpty()) s.sendMessage(Component.text(lv.description, NamedTextColor.GRAY));
    }

    void command(CommandSender sender, String[] args) {
        if (!enabled) {
            info(sender);
            return;
        }
        String sub = args.length > 1 ? args[1].toLowerCase() : "info";
        switch (sub) {
            case "info" -> {
                info(sender);
                sender.sendMessage(Component.text("Uso: /laberinto dificultad <facil|medio|dificil|rotar|pausar|reanudar>",
                        NamedTextColor.DARK_GRAY));
            }
            case "rotar", "siguiente" -> change(pickNext(), true);
            case "pausar" -> {
                frozenLeft = Math.max(0, nextChange - System.currentTimeMillis());
                paused = true;
                saveState();
                sender.sendMessage(Component.text("Rotacion automatica en pausa.", NamedTextColor.GREEN));
            }
            case "reanudar" -> {
                paused = false;
                nextChange = System.currentTimeMillis() + frozenLeft;
                saveState();
                sender.sendMessage(Component.text("Rotacion automatica reanudada.", NamedTextColor.GREEN));
            }
            default -> {
                Difficulty d = Difficulty.parse(sub);
                if (d == null) {
                    sender.sendMessage(Component.text("Uso: /laberinto dificultad <facil|medio|dificil|rotar|pausar|reanudar>",
                            NamedTextColor.YELLOW));
                } else {
                    change(d, true);
                }
            }
        }
    }
}
