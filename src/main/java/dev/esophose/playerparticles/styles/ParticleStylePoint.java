package dev.esophose.playerparticles.styles;

import dev.rosewood.rosegarden.config.CommentedFileConfiguration;
import dev.esophose.playerparticles.particles.PParticle;
import dev.esophose.playerparticles.particles.ParticlePair;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import org.bukkit.Location;

public class ParticleStylePoint extends ConfiguredParticleStyle {

    private double offset;
    private boolean followNametag;
    private Map<String, Integer> periods = Collections.emptyMap();

    protected ParticleStylePoint() {
        super("point", true, false, -0.5);
    }

    @Override
    public List<PParticle> getParticles(ParticlePair particle, Location location) {
        return Collections.singletonList(PParticle.point(location.clone().add(0.0, this.offset, 0.0)));
    }

    @Override
    public List<PParticle> getParticles(ParticlePair particle, Location location, Player player) {
        if (player == null)
            return null;
        if (!NametagReminder.emitNow(particle, this.periods))
            return Collections.emptyList();
        return Collections.singletonList(PParticle.point(NametagReminder.anchor(player, location, this.offset, this.followNametag)));
    }

    @Override
    public void updateTimers() {

    }

    @Override
    protected List<String> getGuiIconMaterialNames() {
        return Collections.singletonList("STONE_BUTTON");
    }

    @Override
    protected void setDefaultSettings(CommentedFileConfiguration config) {
        this.setIfNotExists("player-offset", 1.5, "How far to offset the player location vertically");
        NametagReminder.setDefaults(this);
    }

    @Override
    protected void loadSettings(CommentedFileConfiguration config) {
        this.offset = config.getDouble("player-offset");
        this.followNametag = config.getBoolean("follow-nametag");
        this.periods = NametagReminder.readPeriods(config);
    }

}
