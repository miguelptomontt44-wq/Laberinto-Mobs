package com.laberinto;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

/** Todos los ajustes de un nivel de dificultad (mobs + botin + recompensas). */
final class LevelConfig {

    final Difficulty diff; // null = sistema de dificultad desactivado (valores base)

    String name = "Normal";
    String description = "";

    // mobs
    int maxTotal = 60, maxPerPlayer = 30, perCycle = 4;
    int strength = 1, speed = 1;
    double health = 1.0;
    final List<EntityType> types = new ArrayList<>();
    final List<Integer> weights = new ArrayList<>();
    int totalWeight;

    // botin
    int lootAmount = 10, rollsMin = 1, rollsMax = 2;
    final Map<LootManager.Rarity, Double> rarity = new EnumMap<>(LootManager.Rarity.class);

    // recompensa por matar mobs
    double xpMult = 1.0;
    double dropChance = 0.0;
    LootManager.Rarity dropMax = LootManager.Rarity.EPICO;

    LevelConfig(Difficulty diff) {
        this.diff = diff;
        for (LootManager.Rarity r : LootManager.Rarity.values()) rarity.put(r, 1.0);
    }

    double rarityMult(LootManager.Rarity r) {
        return rarity.getOrDefault(r, 1.0);
    }

    EntityType pickType(Random rnd) {
        if (totalWeight <= 0 || types.isEmpty()) return null;
        int r = rnd.nextInt(totalWeight);
        for (int i = 0; i < types.size(); i++) {
            r -= weights.get(i);
            if (r < 0) return types.get(i);
        }
        return types.get(0);
    }

    private void readMobs(ConfigurationSection s, Logger log) {
        types.clear();
        weights.clear();
        totalWeight = 0;
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            int w = s.getInt(key);
            if (w <= 0) continue;
            try {
                types.add(EntityType.valueOf(key.toUpperCase()));
                weights.add(w);
                totalWeight += w;
            } catch (IllegalArgumentException ex) {
                log.warning("Mob desconocido en config: " + key);
            }
        }
    }

    /** Valores "de siempre" (spawn.*, efectos.*, mobs.pesos, loot.*). */
    static LevelConfig base(FileConfiguration c, Logger log) {
        LevelConfig l = new LevelConfig(null);
        l.maxTotal = c.getInt("spawn.max-total", 60);
        l.maxPerPlayer = c.getInt("spawn.max-por-jugador", 30);
        l.perCycle = c.getInt("spawn.por-ciclo", 4);
        l.strength = c.getInt("efectos.fuerza", 1);
        l.speed = c.getInt("efectos.velocidad", 1);
        l.readMobs(c.getConfigurationSection("mobs.pesos"), log);
        l.lootAmount = Math.max(1, c.getInt("loot.cantidad", 10));
        l.rollsMin = Math.max(1, c.getInt("loot.tiradas-min", 1));
        l.rollsMax = Math.max(l.rollsMin, c.getInt("loot.tiradas-max", 2));
        return l;
    }

    /** Un nivel: lo que no este definido se hereda de los valores base. */
    static LevelConfig level(FileConfiguration c, Difficulty d, LevelConfig base, Logger log) {
        String p = "dificultad.niveles." + d.name() + ".";
        LevelConfig l = new LevelConfig(d);
        l.name = c.getString(p + "nombre", d.label);
        l.description = c.getString(p + "descripcion", "");

        l.maxTotal = c.getInt(p + "spawn.max-total", base.maxTotal);
        l.maxPerPlayer = c.getInt(p + "spawn.max-por-jugador", base.maxPerPlayer);
        l.perCycle = c.getInt(p + "spawn.por-ciclo", base.perCycle);
        l.strength = c.getInt(p + "efectos.fuerza", base.strength);
        l.speed = c.getInt(p + "efectos.velocidad", base.speed);
        l.health = Math.max(0.1, c.getDouble(p + "vida-multiplicador", 1.0));

        ConfigurationSection mobs = c.getConfigurationSection(p + "mobs");
        if (mobs != null && !mobs.getKeys(false).isEmpty()) {
            l.readMobs(mobs, log);
        } else {
            l.types.addAll(base.types);
            l.weights.addAll(base.weights);
            l.totalWeight = base.totalWeight;
        }

        l.lootAmount = Math.max(1, c.getInt(p + "loot.cantidad", base.lootAmount));
        l.rollsMin = Math.max(1, c.getInt(p + "loot.tiradas-min", base.rollsMin));
        l.rollsMax = Math.max(l.rollsMin, c.getInt(p + "loot.tiradas-max", base.rollsMax));
        for (LootManager.Rarity r : LootManager.Rarity.values()) {
            l.rarity.put(r, Math.max(0, c.getDouble(p + "loot.rareza." + r.name(), 1.0)));
        }

        l.xpMult = Math.max(0, c.getDouble(p + "recompensa-mobs.xp-multiplicador", 1.0));
        l.dropChance = Math.max(0, Math.min(1, c.getDouble(p + "recompensa-mobs.drop-chance", 0.0)));
        try {
            l.dropMax = LootManager.Rarity.valueOf(
                    c.getString(p + "recompensa-mobs.drop-rareza-maxima", "EPICO").toUpperCase());
        } catch (IllegalArgumentException ex) {
            l.dropMax = LootManager.Rarity.EPICO;
        }
        return l;
    }
}
