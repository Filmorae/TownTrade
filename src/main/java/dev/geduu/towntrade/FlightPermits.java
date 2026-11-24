package dev.geduu.towntrade;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

final class FlightPermits {
    private static final long WARNING = 30_000;
    private static final long LANDING_GRACE = 60_000;

    record Window(long start, long end) {}

    record Credit(long unusedMillis, long activeUntil) {
        static final Credit EMPTY = new Credit(0, 0);

        Credit add(long duration) {
            return new Credit(Math.addExact(unusedMillis, duration), activeUntil);
        }

        Credit activate(long now) {
            return new Credit(0, Math.addExact(Math.max(activeUntil, now), unusedMillis));
        }

        static Credit migrate(List<Window> windows, long now) {
            long end = 0;
            long unused = 0;
            for (var window : windows) {
                if (window.end() <= now) continue;
                if (window.start() <= now) end = Math.max(end, window.end());
                else unused = Math.addExact(unused, window.end() - window.start());
            }
            return new Credit(unused, end);
        }
    }

    private final Map<UUID, Long> warnedFor = new HashMap<>();

    void sync(ServerPlayer player, long end, long now) {
        var marker = (PlayerTransactionTag) player;
        var abilities = player.getAbilities();
        if (player.onGround() || player.isInWater() || player.onClimbable() || player.isPassenger()
                || player.isFallFlying() || player.isCreative() || player.isSpectator()
                || marker.towntrade$landing() <= now) marker.towntrade$setLanding(0);
        if (player.isCreative() || player.isSpectator()) {
            marker.towntrade$setPermitFlight(false);
            return;
        }
        boolean wasFlying = marker.towntrade$restoredFlying();
        marker.towntrade$setRestoredFlying(false);
        if (end > now) {
            marker.towntrade$setLanding(0);
            if (!abilities.mayfly) {
                abilities.mayfly = true;
                marker.towntrade$setPermitFlight(true);
                player.onUpdateAbilities();
            }
            if (end - now <= WARNING && warnedFor.getOrDefault(player.getUUID(), 0L) != end) {
                warnedFor.put(player.getUUID(), end);
                player.sendSystemMessage(Component.translatable("towntrade.permit.warning", (end - now + 999) / 1000));
            }
            return;
        }
        if (marker.towntrade$permitFlight()) {
            marker.towntrade$setPermitFlight(false);
            // A permit-controlled flyer may have logged out while still in the air.
            if ((abilities.flying || wasFlying) && !player.onGround() && !player.isInWater()
                    && !player.onClimbable() && !player.isPassenger()) {
                marker.towntrade$setLanding(now + LANDING_GRACE);
            }
            abilities.flying = false;
            abilities.mayfly = false;
            player.onUpdateAbilities();
        }
        warnedFor.remove(player.getUUID());
    }

    boolean protectFall(ServerPlayer player, long now) {
        var marker = (PlayerTransactionTag) player;
        if (marker.towntrade$landing() <= now) return false;
        marker.towntrade$setLanding(0);
        return true;
    }

    void disconnected(ServerPlayer player) {
        warnedFor.remove(player.getUUID());
    }
}
