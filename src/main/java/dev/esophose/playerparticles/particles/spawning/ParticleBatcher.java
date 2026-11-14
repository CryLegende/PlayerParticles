package dev.esophose.playerparticles.particles.spawning;

import dev.esophose.playerparticles.PlayerParticles;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

/**
 * Queues particle packets per viewer and sends them once per tick as a single bundle packet,
 * instead of one packet (and one network flush) per particle and per viewer.
 * The client handles a bundle in one go, so the result on screen is the same.
 * Falls back to {@link Player#spawnParticle} when the server internals cannot be reached.
 */
public final class ParticleBatcher {

    private static final int MAX_BUNDLE_SIZE = 4000; // the client refuses bundles over 4096 packets

    private static volatile boolean enabled;
    private static MethodHandle particleParam;   // (Particle, Object) -> ParticleOptions
    private static MethodHandle particlePacket;  // (ParticleOptions, boolean, boolean, double x3, float x4, int) -> packet
    private static MethodHandle bundlePacket;    // (Iterable) -> bundle packet
    private static MethodHandle playerHandle;    // (CraftPlayer) -> ServerPlayer
    private static MethodHandle playerConnection;// (ServerPlayer) -> ServerGamePacketListenerImpl
    private static MethodHandle sendPacket;      // (listener, Packet) -> void

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private ParticleBatcher() {

    }

    private static final class Pending {
        final Player player;
        final Queue<Object> packets = new ConcurrentLinkedQueue<>();

        Pending(Player player) {
            this.player = player;
        }
    }

    /**
     * Looks up the server internals used to build and send the packets.
     *
     * @param batch false to always send each particle on its own
     */
    public static void setup(boolean batch) {
        enabled = false;
        PENDING.clear();
        if (!batch)
            return;

        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            String craft = Bukkit.getServer().getClass().getPackage().getName();

            Class<?> craftParticle = Class.forName(craft + ".CraftParticle");
            Method param = craftParticle.getMethod("createParticleParam", Particle.class, Object.class);
            particleParam = lookup.unreflect(param).asType(MethodType.methodType(Object.class, Particle.class, Object.class));

            Class<?> optionsClass = Class.forName("net.minecraft.core.particles.ParticleOptions");
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket");
            Constructor<?> packet = packetClass.getConstructor(optionsClass, boolean.class, boolean.class, double.class, double.class, double.class, float.class, float.class, float.class, float.class, int.class);
            particlePacket = lookup.unreflectConstructor(packet).asType(MethodType.methodType(Object.class, Object.class, boolean.class, boolean.class, double.class, double.class, double.class, float.class, float.class, float.class, float.class, int.class));

            Class<?> bundleClass = Class.forName("net.minecraft.network.protocol.game.ClientboundBundlePacket");
            bundlePacket = lookup.unreflectConstructor(bundleClass.getConstructor(Iterable.class)).asType(MethodType.methodType(Object.class, Iterable.class));

            Class<?> craftPlayer = Class.forName(craft + ".entity.CraftPlayer");
            Method handle = craftPlayer.getMethod("getHandle");
            playerHandle = lookup.unreflect(handle).asType(MethodType.methodType(Object.class, Player.class));

            Class<?> serverPlayer = handle.getReturnType();
            Field connection = serverPlayer.getField("connection");
            playerConnection = lookup.unreflectGetter(connection).asType(MethodType.methodType(Object.class, Object.class));

            Class<?> packetInterface = Class.forName("net.minecraft.network.protocol.Packet");
            Method send = connection.getType().getMethod("send", packetInterface);
            sendPacket = lookup.unreflect(send).asType(MethodType.methodType(void.class, Object.class, Object.class));

            enabled = true;
        } catch (Throwable t) {
            PlayerParticles.getInstance().getLogger().log(Level.WARNING, "Particle packet batching unavailable, sending particles one by one: " + t);
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void spawn(Player player, Particle particle, double x, double y, double z, int count, double offsetX, double offsetY, double offsetZ, double extra, Object data) {
        spawn(player, particle, x, y, z, count, offsetX, offsetY, offsetZ, extra, data, false);
    }

    public static void spawn(Player player, Particle particle, double x, double y, double z, int count, double offsetX, double offsetY, double offsetZ, double extra, Object data, boolean force) {
        if (!enabled) {
            player.spawnParticle(particle, x, y, z, count, offsetX, offsetY, offsetZ, extra, data, force);
            return;
        }

        if (data != null && !particle.getDataType().isInstance(data))
            throw new IllegalArgumentException("data (" + data.getClass() + ") should be " + particle.getDataType());

        Object packet;
        try {
            Object options = particleParam.invokeExact(particle, data);
            packet = particlePacket.invokeExact(options, force, false, x, y, z, (float) offsetX, (float) offsetY, (float) offsetZ, (float) extra, count);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }

        PENDING.computeIfAbsent(player.getUniqueId(), k -> new Pending(player)).packets.add(packet);
    }

    /**
     * Sends everything queued since the last call, one bundle per viewer.
     */
    public static void flush() {
        if (!enabled)
            return;

        Iterator<Map.Entry<UUID, Pending>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Pending pending = iterator.next().getValue();
            if (!pending.player.isOnline()) {
                iterator.remove();
                continue;
            }

            List<Object> packets = new ArrayList<>();
            Object packet;
            while ((packet = pending.packets.poll()) != null)
                packets.add(packet);
            if (packets.isEmpty())
                continue;

            try {
                Object connection = playerConnection.invokeExact(playerHandle.invokeExact(pending.player));
                if (connection == null)
                    continue;

                if (packets.size() == 1) {
                    sendPacket.invokeExact(connection, packets.get(0));
                } else {
                    for (int i = 0; i < packets.size(); i += MAX_BUNDLE_SIZE) {
                        Iterable<?> part = packets.size() <= MAX_BUNDLE_SIZE ? packets : new ArrayList<>(packets.subList(i, Math.min(packets.size(), i + MAX_BUNDLE_SIZE)));
                        sendPacket.invokeExact(connection, bundlePacket.invokeExact(part));
                    }
                }
            } catch (Throwable t) {
                PlayerParticles.getInstance().getLogger().log(Level.WARNING, "Could not send particles to " + pending.player.getName(), t);
            }
        }
    }

}
