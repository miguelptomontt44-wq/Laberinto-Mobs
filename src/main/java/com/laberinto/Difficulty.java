package com.laberinto;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.format.NamedTextColor;

import java.text.Normalizer;

enum Difficulty {
    FACIL("Facil", NamedTextColor.GREEN, BossBar.Color.GREEN),
    MEDIO("Medio", NamedTextColor.YELLOW, BossBar.Color.YELLOW),
    DIFICIL("Dificil", NamedTextColor.RED, BossBar.Color.RED);

    final String label;
    final NamedTextColor color;
    final BossBar.Color barColor;

    Difficulty(String label, NamedTextColor color, BossBar.Color barColor) {
        this.label = label;
        this.color = color;
        this.barColor = barColor;
    }

    Difficulty next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /** Acepta facil/easy, medio/normal, dificil/hard (con o sin tildes). */
    static Difficulty parse(String s) {
        if (s == null) return null;
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").trim().toUpperCase();
        return switch (n) {
            case "FACIL", "EASY" -> FACIL;
            case "MEDIO", "MEDIA", "NORMAL" -> MEDIO;
            case "DIFICIL", "HARD" -> DIFICIL;
            default -> null;
        };
    }
}
