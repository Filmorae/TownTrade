package dev.geduu.towntrade;

import com.google.gson.JsonParseException;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.Collection;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TownTrade implements ModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("Town Trade");
    private TradeService service;

    @Override
    public void onInitialize() {
        var board = TradeBoard.INSTANCE;
        board.register();
        PayloadTypeRegistry.clientboundPlay().register(TradeWire.OpenPacket.TYPE, TradeWire.OpenPacket.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(TradeWire.UpdatePacket.TYPE, TradeWire.UpdatePacket.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(TradeWire.ActionPacket.TYPE, TradeWire.ActionPacket.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(TradeWire.ActionPacket.TYPE, (packet, context) -> {
            if (service == null) return;
            try {
                var request = TradeConfig.JSON.fromJson(packet.data(), TradeWire.Request.class);
                service.handle(context.player(), request);
            } catch (JsonParseException ignored) {
                // Invalid client input has no effect.
            }
        });

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (isReplayServer(server)) return;
            try {
                var directory = FabricLoader.getInstance().getConfigDir().resolve("towntrade");
                var config = TradeConfig.load(directory);
                service = new TradeService(server, config, ServiceConfig.load(directory));
            } catch (Exception failure) {
                LOGGER.error("Town Trade could not load its configuration", failure);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            board.clear();
            if (service != null) {
                service.close();
                service = null;
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (service != null) service.tick(false);
            board.tick(server, service);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (service != null) {
                service.joined(handler.player);
                var prefix = Component.literal("[TownTrade]").withStyle(style -> style.withColor(0xa5d4ad));
                var hint = Component.translatableWithFallback("towntrade.info.join",
                        "Run %s to view your balance.", commandLink("/towntrade info"));
                handler.player.sendSystemMessage(Component.empty().append(prefix).append(" ").append(hint));
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (service != null) service.disconnected(handler.player);
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                service == null || !(entity instanceof ServerPlayer player) || !source.is(DamageTypes.FALL)
                        || !service.protectFall(player));
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
            var open = Commands.literal("open")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .then(target("bank", TradeWire.View.BANK, ""))
                    .then(target("buy", TradeWire.View.BUY, ""))
                    .then(target("permit", TradeWire.View.PERMIT, ""))
                    .then(target("enchant", TradeWire.View.ENCHANT, ""));
            var sell = Commands.literal("sell");
            for (var pool : TradeRules.Pool.values()) {
                sell.then(target(pool.key(), TradeWire.View.SELL, pool.key()));
            }
            var info = Commands.literal("info").executes(context -> open(context.getSource(),
                    List.of(context.getSource().getPlayerOrException()), TradeWire.View.INFO, ""));
            var active = Commands.literal("active").executes(context -> {
                var player = context.getSource().getPlayerOrException();
                if (service == null) {
                    context.getSource().sendFailure(TradeService.message("offline"));
                    return 0;
                }
                return service.activate(player) ? 1 : 0;
            });
            dispatcher.register(Commands.literal("towntrade").then(info).then(active).then(open.then(sell)));
        });
    }

    static boolean isReplayServer(MinecraftServer server) {
        return server != null && server.getClass().getName().equals("com.moulberry.flashback.playback.ReplayServer");
    }

    static Component commandLink(String command) {
        return Component.literal(command).withStyle(style -> style.withUnderlined(true)
                .withClickEvent(new ClickEvent.RunCommand(command)));
    }

    private LiteralArgumentBuilder<CommandSourceStack> target(String label, TradeWire.View view, String pool) {
        return Commands.literal(label)
                .then(Commands.argument("players", EntityArgument.players())
                    .executes(context -> open(context.getSource(), EntityArgument.getPlayers(context, "players"), view, pool)));
    }

    private int open(CommandSourceStack source, Collection<ServerPlayer> players, TradeWire.View view, String pool) {
        if (service == null) {
            source.sendFailure(TradeService.message("offline"));
            return 0;
        }
        int opened = 0;
        for (var player : players) {
            if (service.open(player, view, pool)) opened++;
        }
        return opened;
    }
}
