package com.laberinto;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Botin aleatorio dentro del laberinto.
 * - Se genera en espacios de aire sobre el suelo, dentro de la zona.
 * - Cada X minutos TODO el botin desaparece y se genera en otras posiciones.
 * - Click (izquierdo o derecho) sobre el cofre/cabeza y recibes el premio.
 * - La probabilidad de cada premio sale de su "peso" en config.yml.
 */
final class LootManager implements Listener {

    enum Rarity {
        COMUN("Comun", NamedTextColor.GRAY, 0xCCCCCC, Material.CHEST, Material.ZOMBIE_HEAD),
        RARO("Raro", NamedTextColor.AQUA, 0x55FFFF, Material.TRAPPED_CHEST, Material.SKELETON_SKULL),
        EPICO("Epico", NamedTextColor.LIGHT_PURPLE, 0xFF55FF, Material.ENDER_CHEST, Material.WITHER_SKELETON_SKULL),
        LEGENDARIO("Legendario", NamedTextColor.GOLD, 0xFFAA00, Material.SHULKER_BOX, Material.DRAGON_HEAD);

        final String label;
        final NamedTextColor color;
        final int rgb;
        final Material chest;
        final Material head;

        Rarity(String label, NamedTextColor color, int rgb, Material chest, Material head) {
            this.label = label;
            this.color = color;
            this.rgb = rgb;
            this.chest = chest;
            this.head = head;
        }
    }

    private record Entry(Material material, int min, int max, double weight, Rarity rarity,
                         String name, Map<Enchantment, Integer> enchants) {
    }

    private static final class Spot {
        final Location loc;
        final List<ItemStack> items;
        final Rarity rarity;
        final float yaw;
        ItemDisplay display;
        Interaction hit;

        Spot(Location loc, List<ItemStack> items, Rarity rarity, float yaw) {
            this.loc = loc;
            this.items = items;
            this.rarity = rarity;
            this.yaw = yaw;
        }

        boolean visible() {
            return hit != null && hit.isValid();
        }
    }

    private final JavaPlugin plugin;
    private final Random rnd = new Random();
    private final List<Entry> entries = new ArrayList<>();
    private double totalWeight;

    private final List<Spot> spots = new ArrayList<>();
    private final Map<UUID, Spot> byEntity = new HashMap<>();

    private LaberintoMobs.Zone zone;
    private int generation;

    private boolean enabled;
    private int amount;
    private double regenMinutes;
    private boolean headMode;
    private int rollsMin, rollsMax;
    private double minSeparation;
    private int minWalls;
    private boolean announceRegen, announceLegendary;
    private double showDistance;

    LootManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- config

    void load(FileConfiguration c, LaberintoMobs.Zone z) {
        this.zone = z;
        enabled = c.getBoolean("loot.activo", true);
        amount = Math.max(1, c.getInt("loot.cantidad", 10));
        regenMinutes = Math.max(0.1, c.getDouble("loot.regenerar-minutos", 10));
        headMode = "CABEZA".equalsIgnoreCase(c.getString("loot.apariencia", "COFRE"));
        rollsMin = Math.max(1, c.getInt("loot.tiradas-min", 1));
        rollsMax = Math.max(rollsMin, c.getInt("loot.tiradas-max", 2));
        minSeparation = Math.max(0, c.getDouble("loot.separacion-minima", 6));
        minWalls = Math.max(0, c.getInt("loot.paredes-minimas", 1));
        announceRegen = c.getBoolean("loot.avisar-regeneracion", true);
        announceLegendary = c.getBoolean("loot.anunciar-legendarios", true);
        showDistance = Math.max(16, c.getDouble("loot.distancia-visible", 48));

        entries.clear();
        totalWeight = 0;
        for (Map<?, ?> m : c.getMapList("loot.items")) {
            try {
                Material mat = Material.valueOf(str(m.get("material")).toUpperCase());
                int min = (int) num(m.get("min"), 1);
                int max = (int) num(m.get("max"), min);
                double w = num(m.get("peso"), 1);
                if (w <= 0) continue;
                Rarity r = m.get("rareza") == null ? Rarity.COMUN : Rarity.valueOf(str(m.get("rareza")).toUpperCase());
                String name = m.get("nombre") == null ? null : str(m.get("nombre"));
                Map<Enchantment, Integer> ench = new LinkedHashMap<>();
                if (m.get("encantamientos") instanceof Map<?, ?> em) {
                    for (Map.Entry<?, ?> en : em.entrySet()) {
                        String key = str(en.getKey()).toLowerCase();
                        Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key));
                        if (e == null) {
                            plugin.getLogger().warning("Encantamiento desconocido en loot: " + key);
                            continue;
                        }
                        ench.put(e, (int) num(en.getValue(), 1));
                    }
                }
                entries.add(new Entry(mat, Math.max(1, min), Math.max(Math.max(1, min), max), w, r, name, ench));
                totalWeight += w;
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Entrada de loot invalida (" + m + "): " + ex.getMessage());
            }
        }
    }

    private static String str(Object o) {
        return String.valueOf(o);
    }

    private static double num(Object o, double def) {
        if (o instanceof Number n) return n.doubleValue();
        if (o == null) return def;
        try {
            return Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ---------------------------------------------------------------- tareas

    void startTasks() {
        if (!enabled) {
            removeAll();
            return;
        }
        long period = Math.max(20L, (long) (regenMinutes * 60 * 20));
        Bukkit.getScheduler().runTaskTimer(plugin, this::regenerate, 100L, period);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshVisuals, 60L, 40L);
    }

    /** Borra todo el botin y genera uno nuevo en otras posiciones. */
    void regenerate() {
        removeAll();
        generation++;
        if (!enabled || zone == null || entries.isEmpty()) return;
        World w = Bukkit.getWorld(zone.world());
        if (w == null) return;
        plan(w, generation, new int[]{0, 0});
    }

    private void plan(World w, int gen, int[] st) {
        if (gen != generation) return;
        if (st[0] >= amount || st[1] >= amount * 40) {
            plugin.getLogger().info("Botin del laberinto generado: " + st[0] + "/" + amount);
            if (announceRegen && st[0] > 0) announceRegeneration(w);
            return;
        }
        st[1]++;
        int x = zone.x1() + rnd.nextInt(zone.x2() - zone.x1() + 1);
        int z = zone.z1() + rnd.nextInt(zone.z2() - zone.z1() + 1);
        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(ch ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (gen != generation) return;
                    if (tryAdd(w, x, z)) st[0]++;
                    plan(w, gen, st);
                }));
    }

    private void announceRegeneration(World w) {
        Component msg = Component.text("El botin del laberinto cambio de lugar!", NamedTextColor.GOLD);
        for (Player p : w.getPlayers()) {
            if (zone.contains(p.getLocation())) p.sendMessage(msg);
        }
    }

    // ---------------------------------------------------------------- posiciones

    private boolean tryAdd(World w, int x, int z) {
        int minY = Math.max(zone.y1(), w.getMinHeight() + 1);
        int maxY = Math.min(zone.y2(), w.getMaxHeight() - 3);
        List<Integer> ys = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            if (isFree(w, x, y, z)) ys.add(y);
        }
        if (ys.isEmpty()) return false;
        int y = ys.get(rnd.nextInt(ys.size()));

        Location loc = new Location(w, x + 0.5, y, z + 0.5);
        double sep2 = minSeparation * minSeparation;
        for (Spot s : spots) {
            if (s.loc.distanceSquared(loc) < sep2) return false;
        }

        List<ItemStack> items = new ArrayList<>();
        Rarity best = Rarity.COMUN;
        int rolls = rollsMin + rnd.nextInt(rollsMax - rollsMin + 1);
        for (int i = 0; i < rolls; i++) {
            Entry e = pick();
            items.add(build(e));
            if (e.rarity().ordinal() > best.ordinal()) best = e.rarity();
        }
        spots.add(new Spot(loc, items, best, rnd.nextFloat() * 360f));
        return true;
    }

    /** Suelo solido, 2 bloques de aire encima y (opcional) paredes al lado. */
    private boolean isFree(World w, int x, int y, int z) {
        if (!w.getBlockAt(x, y - 1, z).isSolid()) return false;
        for (int dy = 0; dy <= 1; dy++) {
            Block b = w.getBlockAt(x, y + dy, z);
            if (!b.isPassable() || b.isLiquid()) return false;
        }
        if (minWalls > 0) {
            int walls = 0;
            if (w.getBlockAt(x + 1, y, z).isSolid()) walls++;
            if (w.getBlockAt(x - 1, y, z).isSolid()) walls++;
            if (w.getBlockAt(x, y, z + 1).isSolid()) walls++;
            if (w.getBlockAt(x, y, z - 1).isSolid()) walls++;
            if (walls < minWalls) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- items

    private Entry pick() {
        double r = rnd.nextDouble() * totalWeight;
        for (Entry e : entries) {
            r -= e.weight();
            if (r < 0) return e;
        }
        return entries.get(entries.size() - 1);
    }

    private ItemStack build(Entry e) {
        int qty = e.min() + rnd.nextInt(e.max() - e.min() + 1);
        ItemStack is = new ItemStack(e.material(), Math.min(qty, e.material().getMaxStackSize()));
        ItemMeta meta = is.getItemMeta();
        if (meta == null) return is;

        boolean special = !e.enchants().isEmpty() || e.name() != null;
        if (e.name() != null) {
            meta.displayName(Component.text(e.name(), e.rarity().color)
                    .decoration(TextDecoration.ITALIC, false).decorate(TextDecoration.BOLD));
        }
        if (meta instanceof EnchantmentStorageMeta esm) {
            e.enchants().forEach((en, lvl) -> esm.addStoredEnchant(en, lvl, true));
        } else {
            e.enchants().forEach((en, lvl) -> meta.addEnchant(en, lvl, true));
        }
        if (special) {
            meta.lore(List.of(
                    Component.text("Reliquia del Laberinto", e.rarity().color)
                            .decoration(TextDecoration.ITALIC, false),
                    Component.text("Rareza: " + e.rarity().label, NamedTextColor.DARK_GRAY)
                            .decoration(TextDecoration.ITALIC, false)));
        }
        is.setItemMeta(meta);
        return is;
    }

    // ---------------------------------------------------------------- visual

    /** Crea las entidades visuales de los botines que tengan un jugador cerca. */
    private void refreshVisuals() {
        if (zone == null || spots.isEmpty()) return;
        double d2 = showDistance * showDistance;
        for (Spot s : new ArrayList<>(spots)) {
            World w = s.loc.getWorld();
            if (w == null) continue;
            if (s.visible()) {
                if (s.rarity.ordinal() >= Rarity.RARO.ordinal()) {
                    w.spawnParticle(Particle.END_ROD, s.loc.clone().add(0, 1.2, 0), 2, 0.25, 0.3, 0.25, 0.01);
                }
                continue;
            }
            if (!w.isChunkLoaded(s.loc.getBlockX() >> 4, s.loc.getBlockZ() >> 4)) continue;
            boolean near = false;
            for (Player p : w.getPlayers()) {
                if (p.getLocation().distanceSquared(s.loc) <= d2) {
                    near = true;
                    break;
                }
            }
            if (near) show(s);
        }
    }

    private void show(Spot s) {
        unregister(s);
        World w = s.loc.getWorld();
        Material mat = headMode ? s.rarity.head : s.rarity.chest;

        s.display = w.spawn(s.loc.clone().add(0, 0.5, 0), ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(mat));
            d.setPersistent(false);
            d.setGlowing(true);
            d.setGlowColorOverride(Color.fromRGB(s.rarity.rgb));
            d.setRotation(s.yaw, 0f);
        });
        s.hit = w.spawn(s.loc, Interaction.class, h -> {
            h.setPersistent(false);
            h.setInteractionWidth(1.0f);
            h.setInteractionHeight(1.0f);
            h.setResponsive(true);
        });
        byEntity.put(s.hit.getUniqueId(), s);
    }

    private void unregister(Spot s) {
        if (s.hit != null) {
            byEntity.remove(s.hit.getUniqueId());
            if (s.hit.isValid()) s.hit.remove();
        }
        if (s.display != null && s.display.isValid()) s.display.remove();
        s.hit = null;
        s.display = null;
    }

    void removeAll() {
        for (Spot s : spots) unregister(s);
        spots.clear();
        byEntity.clear();
    }

    int count() {
        return spots.size();
    }

    // ---------------------------------------------------------------- eventos

    @EventHandler(priority = EventPriority.HIGH)
    public void onRightClick(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Interaction i)) return;
        Spot s = byEntity.get(i.getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        if (e.getHand() == EquipmentSlot.HAND) claim(e.getPlayer(), s);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLeftClick(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Interaction i)) return;
        Spot s = byEntity.get(i.getUniqueId());
        if (s == null) return;
        e.setCancelled(true);
        if (e.getDamager() instanceof Player p) claim(p, s);
    }

    private void claim(Player p, Spot s) {
        if (!spots.remove(s)) return; // ya reclamado
        unregister(s);

        Location l = s.loc.clone().add(0, 0.8, 0);
        World w = l.getWorld();
        w.playSound(l, s.rarity == Rarity.EPICO || s.rarity == Rarity.LEGENDARIO
                ? Sound.BLOCK_ENDER_CHEST_OPEN : Sound.BLOCK_CHEST_OPEN, 1f, 1f);
        w.spawnParticle(Particle.END_ROD, l, 40, 0.4, 0.4, 0.4, 0.08);
        w.spawnParticle(Particle.CRIT, l, 25, 0.4, 0.4, 0.4, 0.2);
        if (s.rarity == Rarity.LEGENDARIO) {
            w.playSound(l, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        } else {
            w.playSound(l, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        }

        p.sendMessage(Component.text("Botin " + s.rarity.label + "!", s.rarity.color)
                .decorate(TextDecoration.BOLD));
        for (ItemStack it : s.items) {
            ItemMeta m = it.getItemMeta();
            Component name = (m != null && m.hasDisplayName())
                    ? m.displayName()
                    : Component.translatable(it.getType().translationKey());
            p.sendMessage(Component.text("  + " + it.getAmount() + "x ", NamedTextColor.YELLOW).append(name));
            for (ItemStack left : p.getInventory().addItem(it.clone()).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }

        if (announceLegendary && s.rarity == Rarity.LEGENDARIO) {
            Bukkit.broadcast(Component.text(p.getName() + " encontro un tesoro LEGENDARIO en el laberinto!",
                    NamedTextColor.GOLD).decorate(TextDecoration.BOLD));
        }
    }

    // ---------------------------------------------------------------- comandos

    void command(CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase() : "";
        switch (sub) {
            case "regenerar" -> {
                regenerate();
                sender.sendMessage(Component.text("Regenerando botin del laberinto...", NamedTextColor.GREEN));
            }
            case "limpiar" -> {
                generation++;
                removeAll();
                sender.sendMessage(Component.text("Botin eliminado (volvera en el proximo ciclo).",
                        NamedTextColor.GREEN));
            }
            case "chances" -> chances(sender);
            case "ubicaciones" -> {
                if (spots.isEmpty()) {
                    sender.sendMessage(Component.text("No hay botin activo.", NamedTextColor.RED));
                    return;
                }
                for (Spot s : spots) {
                    sender.sendMessage(Component.text(s.rarity.label + " en " + s.loc.getBlockX() + ", "
                            + s.loc.getBlockY() + ", " + s.loc.getBlockZ(), s.rarity.color));
                }
            }
            default -> sender.sendMessage(Component.text(
                    "Uso: /laberinto loot <regenerar|limpiar|chances|ubicaciones>", NamedTextColor.YELLOW));
        }
    }

    private void chances(CommandSender sender) {
        if (entries.isEmpty()) {
            sender.sendMessage(Component.text("No hay items de loot en config.yml", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("--- Probabilidad por tirada (tiradas por cofre: "
                + rollsMin + "-" + rollsMax + ") ---", NamedTextColor.AQUA));
        List<Entry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> Double.compare(b.weight(), a.weight()));
        for (Entry e : sorted) {
            String n = e.name() != null ? e.name() : e.material().name();
            sender.sendMessage(Component.text(String.format("%6.2f%%  %s (%s)",
                    e.weight() / totalWeight * 100.0, n, e.rarity().label), e.rarity().color));
        }
    }
}
