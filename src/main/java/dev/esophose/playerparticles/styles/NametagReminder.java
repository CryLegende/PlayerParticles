package dev.esophose.playerparticles.styles;

import dev.esophose.playerparticles.config.Settings;
import dev.esophose.playerparticles.particles.ParticlePair;
import dev.rosewood.rosegarden.config.CommentedFileConfiguration;
import dev.rosewood.rosegarden.hook.PlaceholderAPIHook;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Shared logic of the styles drawn just above the nametag (point, overhead):
 * the height follows the nametag (no extra line such as a title, the player's scale attribute),
 * and each effect can have its own emission period so long-lived particles do not pile up.
 */
public final class NametagReminder {

    private static final Map<UUID, Boolean> EXTRA_LINE = new ConcurrentHashMap<>();
    private static volatile boolean scaleSupported = true;

    private NametagReminder() {

    }

    /**
     * Re-reads which online players have the extra nametag line. Main thread only (PlaceholderAPI).
     */
    public static void refresh() {
        String placeholder = Settings.NAMETAG_EXTRA_LINE_PLACEHOLDER.get();
        if (placeholder == null || placeholder.trim().isEmpty()) {
            EXTRA_LINE.clear();
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            String value = PlaceholderAPIHook.applyPlaceholders(player, placeholder);
            boolean extraLine = value != null && !value.trim().isEmpty() && !value.equals(placeholder);
            EXTRA_LINE.put(player.getUniqueId(), extraLine);
        }
        EXTRA_LINE.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    /**
     * @param player The owner of the particles, may be null
     * @param location The style location (eyes - 0.6)
     * @param offset The configured vertical offset, for a nametag that has the extra line
     * @param followNametag If the height should follow the nametag
     * @return Where to draw the particle
     */
    public static Location anchor(Player player, Location location, double offset, boolean followNametag) {
        if (player == null || !followNametag)
            return location.clone().add(0, offset, 0);

        double lower = Boolean.FALSE.equals(EXTRA_LINE.get(player.getUniqueId())) ? Settings.NAMETAG_EXTRA_LINE_HEIGHT.get() : 0;
        double scale = scale(player);
        // location is 0.6 below the eyes: with the scale attribute the whole distance above the eyes grows or shrinks
        return location.clone().add(0, 0.6 + (offset - 0.6 - lower) * scale, 0);
    }

    /**
     * @return true if the effect of this particle should be drawn during the current tick
     */
    public static boolean emitNow(ParticlePair particle, Map<String, Integer> periods) {
        if (periods.isEmpty())
            return true;

        Integer period = periods.get(particle.getEffect().getInternalName());
        if (period == null || period <= 1)
            return true;

        int window = (int) Math.max(1, Settings.TICKS_PER_PARTICLE.get());
        int phase = particle.getOwnerUniqueId() == null ? 0 : particle.getOwnerUniqueId().hashCode() & 0x7FFF;
        return Math.floorMod(Bukkit.getCurrentTick() + phase, period) < window;
    }

    static void setDefaults(ConfiguredParticleStyle style) {
        style.setIfNotExists("follow-nametag", false, "The offset is set for a nametag that has the extra line (config.yml, nametag-extra-line-placeholder):", "players without it get their particles nametag-extra-line-height lower, and the height scales with the player's size");
        style.setIfNotExists("effect-periods", Collections.emptyMap(), "Ticks between two particles for the listed effects (effect: ticks), others use ticks-per-particle", "Useful for long-lived particles (heart, angry_villager, end_rod...) that would otherwise pile up");
    }

    static Map<String, Integer> readPeriods(CommentedFileConfiguration config) {
        Map<String, Integer> periods = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("effect-periods");
        if (section != null)
            for (String key : section.getKeys(false))
                periods.put(key.toLowerCase(), section.getInt(key));
        return periods;
    }

    private static double scale(Player player) {
        if (!scaleSupported)
            return 1;

        try {
            AttributeInstance instance = player.getAttribute(Attribute.SCALE);
            return instance == null ? 1 : instance.getValue();
        } catch (Throwable t) {
            scaleSupported = false;
            return 1;
        }
    }

}
