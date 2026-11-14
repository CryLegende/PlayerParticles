package dev.esophose.playerparticles.styles;

import dev.esophose.playerparticles.PlayerParticles;
import dev.esophose.playerparticles.manager.DataManager;
import dev.esophose.playerparticles.manager.ParticleManager;
import dev.esophose.playerparticles.particles.PParticle;
import dev.esophose.playerparticles.particles.PPlayer;
import dev.esophose.playerparticles.particles.ParticlePair;
import dev.rosewood.rosegarden.config.CommentedFileConfiguration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public class ParticleStyleMove extends ConfiguredParticleStyle implements Listener {

    private ParticleManager particleManager;
    private DataManager dataManager;

    private int multiplier;
    private int minTicksBetween;
    private final Map<UUID, Integer> lastSpawnTicks = new ConcurrentHashMap<>();

    protected ParticleStyleMove() {
        super("move", false, false, 0);
    }

    @Override
    public List<PParticle> getParticles(ParticlePair particle, Location location) {
        List<PParticle> particles = new ArrayList<>();

        for (int i = 0; i < this.multiplier; i++)
            particles.addAll(DefaultStyles.NORMAL.getParticles(particle, location));

        return particles;
    }

    @Override
    public void updateTimers() {

    }

    @Override
    protected List<String> getGuiIconMaterialNames() {
        return Arrays.asList("PISTON", "PISTON_BASE");
    }

    @Override
    protected void setDefaultSettings(CommentedFileConfiguration config) {
        this.setIfNotExists("multiplier", 1, "The multiplier for the number of particles to spawn", "This style uses the same spawning as the 'normal' style");
        this.setIfNotExists("min-ticks-between", 1, "Minimum number of ticks between two spawns for the same player", "1 spawns on every movement (up to 20 times per second), 2 at most 10 times per second, etc.");
    }

    @Override
    protected void loadSettings(CommentedFileConfiguration config) {
        this.multiplier = config.getInt("multiplier");
        this.minTicksBetween = Math.max(1, config.getInt("min-ticks-between"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()))
            return;

        if (this.particleManager == null || this.dataManager == null) {
            this.particleManager = PlayerParticles.getInstance().getManager(ParticleManager.class);
            this.dataManager = PlayerParticles.getInstance().getManager(DataManager.class);
        }

        Player player = event.getPlayer();
        PPlayer pplayer = this.dataManager.getPPlayer(player.getUniqueId());
        if (pplayer == null)
            return;

        if (this.minTicksBetween > 1) {
            int tick = Bukkit.getCurrentTick();
            Integer last = this.lastSpawnTicks.get(player.getUniqueId());
            if (last != null && tick - last < this.minTicksBetween && tick >= last)
                return;
            if (pplayer.getActiveParticlesForStyle(DefaultStyles.MOVE).isEmpty())
                return;
            this.lastSpawnTicks.put(player.getUniqueId(), tick);
        }

        for (ParticlePair particle : pplayer.getActiveParticlesForStyle(DefaultStyles.MOVE)) {
            Location loc = player.getLocation();
            loc.setY(loc.getY() + 0.05);
            this.particleManager.displayParticles(pplayer, player.getWorld(), particle, DefaultStyles.MOVE.getParticles(particle, loc), false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        this.lastSpawnTicks.remove(event.getPlayer().getUniqueId());
    }

}
