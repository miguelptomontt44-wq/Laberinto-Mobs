package com.laberinto;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Habilidades unicas de armas y armaduras del laberinto.
 * Cada item lleva una etiqueta (habilidad) invisible; este listener la lee y aplica el efecto.
 */
final class Habilidades implements Listener {

    record Info(String nombre, List<String> lineas) {
    }

    static final Map<String, Info> INFO = new LinkedHashMap<>();

    private static void reg(String id, String nombre, String... lineas) {
        INFO.put(id, new Info(nombre, List.of(lineas)));
    }

    private static final String SET_LINE = "Set completo (casco+peto+grebas+botas): Resistencia I y Regeneracion I.";

    static {
        // ---- armas legendarias
        reg("espada_vampirica", "Sed de Sangre",
                "Curas el 25% del dano que infliges.", "20% de aplicar Wither II (4s).");
        reg("hacha_berserker", "Furia del Berserker",
                "30% de ganar Fuerza II (4s) al golpear.", "20% de aplicar Debilidad II (5s).");
        reg("pico_minero", "Luz del Minero",
                "Al sostenerlo: Prisa II y Vision nocturna.");
        reg("pala_arena", "Tormenta de Arena",
                "Al golpear: Ceguera (3s) y Lentitud II (3s).", "Al sostenerla: Velocidad I.");
        reg("arco_tormenta", "Tormenta Electrica",
                "Marca al enemigo con Resplandor (8s).", "25% de invocar un rayo (+6 de dano).");
        reg("ballesta_cazadora", "Cazador Implacable",
                "Resplandor (8s) y Lentitud II (3s).", "Ejecuta a enemigos con menos del 25% de vida (+8).");
        reg("tridente_trueno", "Furia del Trueno",
                "35% de invocar un rayo al golpear o lanzar (+6).", "Al sostenerlo: respiras bajo el agua.");
        reg("maza_sismica", "Impacto Sismico",
                "Golpear en caida libre crea una onda de choque.", "Lentitud II (3s) al golpeado.");
        // ---- armadura legendaria
        reg("casco_ojo", "Ojo del Laberinto",
                "Vision nocturna permanente.", "Resalta a los monstruos a 10 bloques.", SET_LINE);
        reg("peto_espinas", "Coraza de Espinas",
                "Refleja el 30% del dano cuerpo a cuerpo.", "20% de aplicar Debilidad al atacante.",
                "Inmunidad al fuego.", SET_LINE);
        reg("grebas_sombra", "Paso Sombrio",
                "Velocidad I.", "18% de esquivar un golpe.", SET_LINE);
        reg("botas_viento", "Pies de Viento",
                "Velocidad I y Salto II.", "Sin dano por caida.", SET_LINE);
        reg("alas_fantasma", "Vuelo Fantasma",
                "Inmune a los choques al planear.", "Dejas una estela de nubes al volar.");
        // ---- items epicos / raros
        reg("espada_gelida", "Filo Gelido", "25% de aplicar Lentitud II (3s).");
        reg("pico_veloz", "Veta Veloz", "Al sostenerlo: Prisa I.");
        reg("peto_firme", "Coraza Firme", "Inmunidad al fuego.");
        reg("botas_ligeras", "Pisada Ligera", "Velocidad I.", "Sin dano por caida.");
        reg("cana_gancho", "Gancho del Laberinto",
                "Atrae hacia ti a lo que enganches.", "Lentitud (2s) al enganchado.");
        reg("escudo_reflejo", "Escudo Reflejo",
                "Al bloquear un golpe, empuja al atacante y le hace 4 de dano.");
    }

    static NamespacedKey key;

    private final JavaPlugin plugin;
    private final Random rnd = new Random();
    private boolean enabled = true, pvp = true, setComplete = true;
    private boolean busy; // evita que un efecto dispare otros efectos en cadena

    Habilidades(JavaPlugin plugin) {
        this.plugin = plugin;
        key = new NamespacedKey(plugin, "habilidad");
    }

    void load(FileConfiguration c) {
        enabled = c.getBoolean("habilidades.activo", true);
        pvp = c.getBoolean("habilidades.pvp", true);
        setComplete = c.getBoolean("habilidades.set-completo", true);
    }

    void startTasks() {
        Bukkit.getScheduler().runTaskTimer(plugin, this::passive, 20L, 20L);
    }

    // ---------------------------------------------------------------- etiquetas y lore

    static String idOf(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    static List<Component> lore(String id, NamedTextColor color) {
        List<Component> out = new ArrayList<>();
        Info info = INFO.get(id);
        if (info == null) return out;
        out.add(Component.text("Habilidad: " + info.nombre(), color)
                .decoration(TextDecoration.ITALIC, false).decorate(TextDecoration.BOLD));
        for (String l : info.lineas()) {
            out.add(Component.text(" " + l, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        return out;
    }

    // ---------------------------------------------------------------- utilidades

    private boolean chance(double p) {
        return rnd.nextDouble() < p;
    }

    private void effect(LivingEntity e, PotionEffectType t, int ticks, int amp) {
        e.addPotionEffect(new PotionEffect(t, ticks, amp, false, true, true));
    }

    private void give(Player p, PotionEffectType t, int ticks, int amp) {
        p.addPotionEffect(new PotionEffect(t, ticks, amp, true, false, true));
    }

    private void heal(Player p, double amount) {
        if (p.isDead() || amount <= 0) return;
        AttributeInstance ai = p.getAttribute(Attribute.MAX_HEALTH);
        double max = ai == null ? 20.0 : ai.getValue();
        p.setHealth(Math.min(max, p.getHealth() + amount));
        p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 1.8, 0), 2, 0.3, 0.2, 0.3, 0);
    }

    private double hpFraction(LivingEntity e) {
        AttributeInstance ai = e.getAttribute(Attribute.MAX_HEALTH);
        double max = ai == null ? 20.0 : ai.getValue();
        return e.getHealth() / max;
    }

    /** Dano extra un tick despues (asi no interfiere con el golpe original). */
    private void hurtLater(Player by, LivingEntity target, double dmg) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!target.isValid() || target.isDead()) return;
            busy = true;
            try {
                target.setNoDamageTicks(0);
                if (by != null && by.isOnline()) target.damage(dmg, by); else target.damage(dmg);
            } finally {
                busy = false;
            }
        });
    }

    private void lightning(Player by, LivingEntity target, double dmg) {
        Location l = target.getLocation();
        l.getWorld().strikeLightningEffect(l);
        hurtLater(by, target, dmg);
    }

    private void shockwave(Player attacker, LivingEntity center) {
        Location c = center.getLocation();
        c.getWorld().spawnParticle(Particle.CLOUD, c, 40, 1.6, 0.2, 1.6, 0.08);
        c.getWorld().playSound(c, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.2f, 0.6f);
        for (Entity en : center.getNearbyEntities(4, 2, 4)) {
            if (!(en instanceof LivingEntity le) || le.equals(attacker)) continue;
            if (le instanceof Player && !pvp) continue;
            Vector v = le.getLocation().toVector().subtract(c.toVector());
            v.setY(0);
            if (v.lengthSquared() > 0.01) {
                v.normalize().multiply(0.9);
            }
            v.setY(0.45);
            le.setVelocity(v);
            hurtLater(attacker, le, 4.0);
        }
    }

    // ---------------------------------------------------------------- ataque

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!enabled || busy) return;

        if (e.getEntity() instanceof Player victim) {
            defend(e, victim);
            if (e.isCancelled()) return;
        }
        if (!(e.getEntity() instanceof LivingEntity target)) return;

        Player attacker = null;
        String id = null;
        boolean ranged = false;
        Entity d = e.getDamager();
        if (d instanceof Player p) {
            attacker = p;
            id = idOf(p.getInventory().getItemInMainHand());
        } else if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) {
            attacker = p;
            id = pr.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            ranged = true;
        }
        if (attacker == null || id == null || target.equals(attacker)) return;
        if (target instanceof Player && !pvp) return;

        attack(e, attacker, target, id, ranged);
    }

    private void attack(EntityDamageByEntityEvent e, Player a, LivingEntity t, String id, boolean ranged) {
        Location tl = t.getLocation().add(0, 1, 0);
        switch (id) {
            case "espada_vampirica" -> {
                heal(a, e.getFinalDamage() * 0.25);
                if (chance(0.20)) {
                    effect(t, PotionEffectType.WITHER, 80, 1);
                    t.getWorld().spawnParticle(Particle.CRIT, tl, 12, 0.3, 0.4, 0.3, 0.1);
                }
            }
            case "hacha_berserker" -> {
                if (chance(0.30)) {
                    effect(a, PotionEffectType.STRENGTH, 80, 1);
                    a.getWorld().spawnParticle(Particle.CRIT, a.getLocation().add(0, 1, 0), 12, 0.4, 0.5, 0.4, 0.1);
                }
                if (chance(0.20)) effect(t, PotionEffectType.WEAKNESS, 100, 1);
            }
            case "pala_arena" -> {
                effect(t, PotionEffectType.BLINDNESS, 60, 0);
                effect(t, PotionEffectType.SLOWNESS, 60, 1);
            }
            case "arco_tormenta" -> {
                if (!ranged) return;
                effect(t, PotionEffectType.GLOWING, 160, 0);
                if (chance(0.25)) lightning(a, t, 6.0);
            }
            case "ballesta_cazadora" -> {
                if (!ranged) return;
                effect(t, PotionEffectType.GLOWING, 160, 0);
                effect(t, PotionEffectType.SLOWNESS, 60, 1);
                if (hpFraction(t) < 0.25) {
                    t.getWorld().spawnParticle(Particle.CRIT, tl, 25, 0.3, 0.4, 0.3, 0.3);
                    hurtLater(a, t, 8.0);
                }
            }
            case "tridente_trueno" -> {
                if (chance(0.35)) lightning(a, t, 6.0);
            }
            case "maza_sismica" -> {
                effect(t, PotionEffectType.SLOWNESS, 60, 1);
                if (a.getFallDistance() > 1.5f) shockwave(a, t);
            }
            case "espada_gelida" -> {
                if (chance(0.25)) {
                    effect(t, PotionEffectType.SLOWNESS, 60, 1);
                    t.getWorld().spawnParticle(Particle.SNOWFLAKE, tl, 20, 0.3, 0.4, 0.3, 0.02);
                }
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- defensa

    private void defend(EntityDamageByEntityEvent e, Player victim) {
        PlayerInventory inv = victim.getInventory();

        // Paso Sombrio: esquivar
        if ("grebas_sombra".equals(idOf(inv.getLeggings())) && chance(0.18)) {
            e.setCancelled(true);
            victim.getWorld().spawnParticle(Particle.CLOUD, victim.getLocation().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.05);
            victim.sendActionBar(Component.text("Esquivaste!", NamedTextColor.AQUA));
            return;
        }

        Entity d = e.getDamager();
        if (!(d instanceof LivingEntity att) || att.equals(victim)) return;

        // Escudo Reflejo
        if (victim.isBlocking()
                && ("escudo_reflejo".equals(idOf(inv.getItemInMainHand()))
                || "escudo_reflejo".equals(idOf(inv.getItemInOffHand())))) {
            Vector v = att.getLocation().toVector().subtract(victim.getLocation().toVector());
            v.setY(0);
            if (v.lengthSquared() > 0.01) v.normalize().multiply(1.0);
            v.setY(0.35);
            att.setVelocity(v);
            victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6f, 1.6f);
            hurtLater(victim, att, 4.0);
        }

        // Coraza de Espinas
        if ("peto_espinas".equals(idOf(inv.getChestplate()))) {
            hurtLater(victim, att, Math.max(1.0, e.getFinalDamage() * 0.30));
            if (chance(0.20)) effect(att, PotionEffectType.WEAKNESS, 100, 0);
            victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1, 0), 8, 0.4, 0.5, 0.4, 0.1);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!enabled || !(e.getEntity() instanceof Player p)) return;
        PlayerInventory inv = p.getInventory();
        switch (e.getCause()) {
            case FALL -> {
                String b = idOf(inv.getBoots());
                if ("botas_viento".equals(b) || "botas_ligeras".equals(b)) e.setCancelled(true);
            }
            case FLY_INTO_WALL -> {
                if ("alas_fantasma".equals(idOf(inv.getChestplate()))) e.setCancelled(true);
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- proyectiles y cana

    @EventHandler
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!enabled) return;
        if (!(e.getEntity() instanceof AbstractArrow arrow) || !(arrow.getShooter() instanceof Player p)) return;
        for (ItemStack it : new ItemStack[]{p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand()}) {
            Material m = it.getType();
            if (m == Material.BOW || m == Material.CROSSBOW || m == Material.TRIDENT) {
                String id = idOf(it);
                if (id != null) {
                    arrow.getPersistentDataContainer().set(key, PersistentDataType.STRING, id);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onFish(PlayerFishEvent e) {
        if (!enabled || e.getState() != PlayerFishEvent.State.CAUGHT_ENTITY) return;
        Player p = e.getPlayer();
        if (!"cana_gancho".equals(idOf(p.getInventory().getItemInMainHand()))
                && !"cana_gancho".equals(idOf(p.getInventory().getItemInOffHand()))) return;
        if (!(e.getCaught() instanceof LivingEntity le)) return;
        if (le instanceof Player && !pvp) return;
        Vector v = p.getLocation().toVector().subtract(le.getLocation().toVector());
        if (v.lengthSquared() > 0.01) v.normalize().multiply(1.1);
        v.setY(0.45);
        le.setVelocity(v);
        effect(le, PotionEffectType.SLOWNESS, 40, 1);
    }

    // ---------------------------------------------------------------- pasivas (cada segundo)

    private void passive() {
        if (!enabled) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead()) continue;
            PlayerInventory inv = p.getInventory();
            String h = idOf(inv.getHelmet());
            String c = idOf(inv.getChestplate());
            String l = idOf(inv.getLeggings());
            String b = idOf(inv.getBoots());
            String m = idOf(inv.getItemInMainHand());
            if (h == null && c == null && l == null && b == null && m == null) continue;

            if ("casco_ojo".equals(h)) {
                give(p, PotionEffectType.NIGHT_VISION, 300, 0);
                for (Entity en : p.getNearbyEntities(10, 5, 10)) {
                    if (en instanceof Monster mo) {
                        mo.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 40, 0, false, false, false));
                    }
                }
            }
            if ("peto_espinas".equals(c) || "peto_firme".equals(c)) give(p, PotionEffectType.FIRE_RESISTANCE, 60, 0);
            if ("grebas_sombra".equals(l)) give(p, PotionEffectType.SPEED, 60, 0);
            if ("botas_viento".equals(b)) {
                give(p, PotionEffectType.SPEED, 60, 0);
                give(p, PotionEffectType.JUMP_BOOST, 60, 1);
            }
            if ("botas_ligeras".equals(b)) give(p, PotionEffectType.SPEED, 60, 0);

            if (setComplete && "casco_ojo".equals(h) && "peto_espinas".equals(c)
                    && "grebas_sombra".equals(l) && "botas_viento".equals(b)) {
                give(p, PotionEffectType.RESISTANCE, 60, 0);
                give(p, PotionEffectType.REGENERATION, 60, 0);
            }

            if ("alas_fantasma".equals(c) && p.isGliding()) {
                p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 12, 0.3, 0.2, 0.3, 0.02);
            }

            if (m != null) {
                switch (m) {
                    case "pico_minero" -> {
                        give(p, PotionEffectType.HASTE, 60, 1);
                        give(p, PotionEffectType.NIGHT_VISION, 300, 0);
                    }
                    case "pico_veloz" -> give(p, PotionEffectType.HASTE, 60, 0);
                    case "pala_arena" -> give(p, PotionEffectType.SPEED, 60, 0);
                    case "tridente_trueno" -> give(p, PotionEffectType.WATER_BREATHING, 60, 0);
                    default -> {
                    }
                }
            }
        }
    }
}
