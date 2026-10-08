package com.laberinto;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Tag;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
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

    static {
        // ---- nuevas armas / herramientas
        reg("azada_segadora", "Guadana Segadora",
                "Cada golpe corta tambien a los enemigos a 3 bloques (50% del dano).");
        reg("daga_asesina", "Punalada Trapera",
                "Golpear por la espalda: +75% de dano.", "Al sostenerla: Velocidad I.");
        reg("hacha_verdugo", "Hacha del Verdugo",
                "+50% de dano a enemigos con menos del 30% de vida.");
        reg("arco_toxico", "Flechas Toxicas",
                "Veneno II (5s), Lentitud y Debilidad al enemigo.");
        reg("espada_vacio", "Salto del Vacio",
                "Clic derecho: te teletransportas hasta 10 bloques hacia donde miras.", "Enfriamiento: 6s.");
        reg("pico_fundidor", "Pico Fundidor",
                "Los minerales se funden solos al romperlos.");
        reg("hacha_lenador", "Hacha Lenadora",
                "Talas el arbol completo de un golpe (agachate para talar normal).");
        reg("brujula_tesoro", "Brujula del Tesoro",
                "Clic derecho: indica distancia y direccion del botin mas cercano.");
        // ---- nuevas defensas
        reg("escudo_titan", "Escudo del Titan",
                "Mientras bloqueas: Resistencia II.");
        reg("corona_rey", "Corona del Rey",
                "Cada 20s ganas Absorcion II (4 corazones extra).");
        reg("peto_fenix", "Renacer del Fenix",
                "Si recibes un golpe mortal, sobrevives con 5 corazones y te regeneras.", "Enfriamiento: 5 min.");
        reg("grebas_magma", "Aura de Magma",
                "Los monstruos a 2 bloques de ti se prenden fuego.", "Inmunidad al fuego.");
        reg("botas_sismicas", "Pisada Sismica",
                "Sin dano por caida: aterrizar crea una onda que danha a los enemigos cercanos.");
    }

    static NamespacedKey key;

    private final JavaPlugin plugin;
    private final Random rnd = new Random();
    private boolean enabled = true, pvp = true, setComplete = true;
    private boolean busy; // evita que un efecto dispare otros efectos en cadena
    private LootManager loot;
    private final Map<String, Long> cooldowns = new HashMap<>();

    private static final Map<Material, Material> SMELT = Map.of(
            Material.RAW_IRON, Material.IRON_INGOT,
            Material.RAW_GOLD, Material.GOLD_INGOT,
            Material.RAW_COPPER, Material.COPPER_INGOT,
            Material.ANCIENT_DEBRIS, Material.NETHERITE_SCRAP,
            Material.COBBLESTONE, Material.STONE,
            Material.COBBLED_DEEPSLATE, Material.DEEPSLATE,
            Material.SAND, Material.GLASS,
            Material.RED_SAND, Material.GLASS,
            Material.NETHERRACK, Material.NETHER_BRICK,
            Material.CLAY_BALL, Material.BRICK);

    Habilidades(JavaPlugin plugin) {
        this.plugin = plugin;
        key = new NamespacedKey(plugin, "habilidad");
    }

    void setLoot(LootManager l) {
        this.loot = l;
    }

    /** true (y arranca el enfriamiento) si ya paso el tiempo desde el ultimo uso. */
    private boolean ready(Player p, String ability, long ms) {
        String k = p.getUniqueId() + ability;
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(k);
        if (last != null && now - last < ms) return false;
        cooldowns.put(k, now);
        return true;
    }

    private double maxHealth(LivingEntity e) {
        AttributeInstance ai = e.getAttribute(Attribute.MAX_HEALTH);
        return ai == null ? 20.0 : ai.getValue();
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

    private void shockwave(Player attacker, LivingEntity center, double damage) {
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
            hurtLater(attacker, le, damage);
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
                if (a.getFallDistance() > 1.5f) shockwave(a, t, 4.0);
            }
            case "azada_segadora" -> {
                double dmg = e.getFinalDamage() * 0.5;
                t.getWorld().spawnParticle(Particle.SWEEP_ATTACK, t.getLocation().add(0, 1, 0), 3, 1.0, 0.2, 1.0, 0);
                for (Entity en : t.getNearbyEntities(3, 1.5, 3)) {
                    if (!(en instanceof LivingEntity le) || le.equals(a) || le instanceof ArmorStand) continue;
                    if (le instanceof Player && !pvp) continue;
                    hurtLater(a, le, dmg);
                }
            }
            case "daga_asesina" -> {
                Vector facing = t.getLocation().getDirection().setY(0);
                Vector toT = t.getLocation().toVector().subtract(a.getLocation().toVector()).setY(0);
                if (facing.lengthSquared() > 0.01 && toT.lengthSquared() > 0.01
                        && facing.normalize().dot(toT.normalize()) > 0.5) {
                    hurtLater(a, t, e.getFinalDamage() * 0.75);
                    t.getWorld().spawnParticle(Particle.CRIT, tl, 25, 0.3, 0.4, 0.3, 0.3);
                    a.sendActionBar(Component.text("Punalada trapera!", NamedTextColor.DARK_RED));
                }
            }
            case "hacha_verdugo" -> {
                if (hpFraction(t) < 0.30) {
                    hurtLater(a, t, e.getFinalDamage() * 0.5);
                    t.getWorld().spawnParticle(Particle.CRIT, tl, 20, 0.3, 0.4, 0.3, 0.3);
                }
            }
            case "arco_toxico" -> {
                if (!ranged) return;
                effect(t, PotionEffectType.POISON, 100, 1);
                effect(t, PotionEffectType.SLOWNESS, 60, 0);
                effect(t, PotionEffectType.WEAKNESS, 100, 0);
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
        EntityDamageEvent.DamageCause cause = e.getCause();

        // Renacer del Fenix: sobrevive a un golpe mortal (cada 5 minutos)
        if (cause != EntityDamageEvent.DamageCause.VOID && cause != EntityDamageEvent.DamageCause.KILL
                && cause != EntityDamageEvent.DamageCause.SUICIDE
                && "peto_fenix".equals(idOf(inv.getChestplate()))
                && p.getHealth() - e.getFinalDamage() <= 0
                && ready(p, "fenix", 300_000L)) {
            e.setCancelled(true);
            p.setHealth(Math.min(10.0, maxHealth(p)));
            give(p, PotionEffectType.REGENERATION, 100, 1);
            give(p, PotionEffectType.RESISTANCE, 100, 1);
            give(p, PotionEffectType.FIRE_RESISTANCE, 200, 0);
            Location l = p.getLocation().add(0, 1, 0);
            p.getWorld().spawnParticle(Particle.FLAME, l, 60, 0.5, 0.8, 0.5, 0.1);
            p.getWorld().spawnParticle(Particle.END_ROD, l, 30, 0.5, 0.8, 0.5, 0.1);
            p.getWorld().playSound(l, Sound.ITEM_TOTEM_USE, 1f, 1f);
            p.sendMessage(Component.text("El Peto del Fenix te salvo la vida!", NamedTextColor.GOLD));
            return;
        }

        switch (cause) {
            case FALL -> {
                String b = idOf(inv.getBoots());
                if ("botas_sismicas".equals(b)) {
                    double d = e.getDamage();
                    e.setCancelled(true);
                    if (d >= 2.0) shockwave(p, p, Math.min(10.0, d * 0.8));
                } else if ("botas_viento".equals(b) || "botas_ligeras".equals(b)) {
                    e.setCancelled(true);
                }
            }
            case FLY_INTO_WALL -> {
                if ("alas_fantasma".equals(idOf(inv.getChestplate()))) e.setCancelled(true);
            }
            default -> {
            }
        }
    }

    // ---------------------------------------------------------------- clic derecho (habilidades activas)

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (!enabled || e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        String id = idOf(p.getInventory().getItemInMainHand());
        if (id == null) return;
        switch (id) {
            case "espada_vacio" -> blink(p);
            case "brujula_tesoro" -> compass(p);
            default -> {
            }
        }
    }

    private void blink(Player p) {
        if (!ready(p, "vacio", 6000L)) {
            p.sendActionBar(Component.text("Salto del Vacio en enfriamiento...", NamedTextColor.GRAY));
            return;
        }
        Vector dir = p.getLocation().getDirection().normalize();
        Location best = null;
        for (int i = 1; i <= 10; i++) {
            Location f = p.getLocation().add(dir.clone().multiply(i));
            Block feet = f.getBlock();
            Block head = f.clone().add(0, 1, 0).getBlock();
            if (!feet.isPassable() || !head.isPassable() || feet.isLiquid()) break;
            best = f;
        }
        if (best == null) {
            cooldowns.remove(p.getUniqueId() + "vacio");
            p.sendActionBar(Component.text("No hay espacio para saltar.", NamedTextColor.RED));
            return;
        }
        Location from = p.getLocation().add(0, 1, 0);
        p.getWorld().spawnParticle(Particle.PORTAL, from, 40, 0.3, 0.6, 0.3, 0.5);
        p.teleport(best);
        p.getWorld().spawnParticle(Particle.PORTAL, best.clone().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.5);
        p.getWorld().playSound(best, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
        p.setFallDistance(0f);
    }

    private void compass(Player p) {
        if (!ready(p, "brujula", 2000L)) return;
        Location t = loot == null ? null : loot.nearest(p.getLocation());
        if (t == null) {
            p.sendActionBar(Component.text("No hay botin cerca (o no estas en el laberinto).", NamedTextColor.GRAY));
            return;
        }
        p.setCompassTarget(t);
        Location pl = p.getLocation();
        double dx = t.getX() - pl.getX();
        double dz = t.getZ() - pl.getZ();
        double yawTo = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = (((yawTo - pl.getYaw()) % 360) + 540) % 360 - 180;
        int idx = (int) Math.round(rel / 45.0);
        if (idx < 0) idx += 8;
        String[] words = {"adelante", "adelante-derecha", "derecha", "atras-derecha",
                "atras", "atras-izquierda", "izquierda", "adelante-izquierda"};
        double dy = t.getY() - pl.getY();
        String alt = dy > 3 ? " (arriba)" : dy < -3 ? " (abajo)" : "";
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        p.sendActionBar(Component.text("Botin mas cercano: " + dist + " bloques, " + words[idx % 8] + alt,
                NamedTextColor.GOLD));
        p.playSound(pl, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
    }

    // ---------------------------------------------------------------- romper bloques

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        ItemStack tool = p.getInventory().getItemInMainHand();
        String id = idOf(tool);
        if (id == null) return;
        Block b = e.getBlock();

        if (id.equals("pico_fundidor")) {
            List<ItemStack> out = new ArrayList<>();
            boolean changed = false;
            Collection<ItemStack> drops = b.getDrops(tool, p);
            for (ItemStack d : drops) {
                Material sm = SMELT.get(d.getType());
                if (sm != null) {
                    out.add(new ItemStack(sm, d.getAmount()));
                    changed = true;
                } else {
                    out.add(d);
                }
            }
            if (!changed) return;
            e.setDropItems(false);
            Location l = b.getLocation().add(0.5, 0.5, 0.5);
            for (ItemStack o : out) b.getWorld().dropItemNaturally(l, o);
            b.getWorld().spawnParticle(Particle.FLAME, l, 10, 0.3, 0.3, 0.3, 0.02);
        } else if (id.equals("hacha_lenador") && !p.isSneaking() && Tag.LOGS.isTagged(b.getType())) {
            fell(b, tool);
        }
    }

    /** Tala todos los troncos conectados (maximo 64) y gasta durabilidad. */
    private void fell(Block start, ItemStack tool) {
        Deque<Block> queue = new ArrayDeque<>();
        Set<Block> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        ItemMeta meta = tool.getItemMeta();
        Damageable dm = meta instanceof Damageable d ? d : null;
        int maxDur = tool.getType().getMaxDurability();
        int broken = 0;
        while (!queue.isEmpty() && broken < 64) {
            Block cur = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block n = cur.getRelative(dx, dy, dz);
                        if (seen.size() < 200 && !seen.contains(n) && Tag.LOGS.isTagged(n.getType())) {
                            seen.add(n);
                            queue.add(n);
                        }
                    }
                }
            }
            if (cur.equals(start)) continue;
            if (dm != null && maxDur > 0 && dm.getDamage() + 1 >= maxDur) break;
            cur.breakNaturally(tool);
            if (dm != null) dm.setDamage(dm.getDamage() + 1);
            broken++;
        }
        if (dm != null) tool.setItemMeta(dm);
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
            if ("corona_rey".equals(h)) {
                String k = p.getUniqueId() + "corona";
                long now = System.currentTimeMillis();
                Long last = cooldowns.get(k);
                if (last == null || now - last >= 21_000L) {
                    cooldowns.put(k, now);
                    give(p, PotionEffectType.ABSORPTION, 400, 1);
                }
            }
            if ("grebas_magma".equals(l)) {
                give(p, PotionEffectType.FIRE_RESISTANCE, 60, 0);
                for (Entity en : p.getNearbyEntities(2.5, 2, 2.5)) {
                    if (en instanceof Monster) en.setFireTicks(60);
                }
                p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 0.2, 0), 4, 0.5, 0.1, 0.5, 0.01);
            }
            if (p.isBlocking() && ("escudo_titan".equals(m) || "escudo_titan".equals(idOf(inv.getItemInOffHand())))) {
                give(p, PotionEffectType.RESISTANCE, 40, 1);
            }
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
                    case "pala_arena", "daga_asesina" -> give(p, PotionEffectType.SPEED, 60, 0);
                    case "tridente_trueno" -> give(p, PotionEffectType.WATER_BREATHING, 60, 0);
                    default -> {
                    }
                }
            }
        }
    }
}
