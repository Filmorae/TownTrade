package dev.geduu.towntrade;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class TradeBoardRenderer implements BlockEntityRenderer<TradeBoardEntity, TradeBoardRenderer.State> {
    private static final int HEIGHT = 216;
    private static final int ORDERS_PER_PAGE = 3;
    private static final int PANEL_LIGHT = 0x00f00070;
    private static final int FRAME = 0xff384047;
    private static final int PAPER = 0xfff8fbfc;
    private static final int INK = 0xff29333c;
    private static final int SUBTLE = 0xff546270;
    private static final int BORDER = 0xffc1cbd1;
    private static final int CARD = 0xffe9eff1;
    private static final int PALE_GREEN = 0xffbddbcd;
    private static final int GREEN = 0xff214c39;
    private static final int AMBER = 0xff795321;
    private static final int BLUE = 0xff304a70;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DATE_AND_CLOCK = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT);
    private static final String[] SERVICES = {"SuperBuy", "SuperSell", "SuperPermit", "SuperEnchant"};
    private static final int[][][] ICONS = {
            {{0, 0, 2, 2}, {2, 2, 12, 4}, {3, 4, 5, 8}, {10, 4, 12, 8}, {4, 8, 12, 10}, {4, 11, 6, 13}, {10, 11, 12, 13}},
            {{2, 1, 11, 3}, {1, 3, 3, 9}, {10, 3, 12, 10}, {3, 9, 9, 11}, {4, 11, 8, 13}, {7, 4, 9, 6}},
            {{2, 0, 12, 2}, {2, 2, 4, 12}, {10, 2, 12, 12}, {2, 12, 12, 14}, {5, 4, 9, 6}, {5, 8, 9, 10}},
            {{0, 6, 2, 12}, {2, 5, 6, 6}, {2, 11, 6, 12}, {6, 6, 8, 14},
                    {8, 5, 12, 6}, {8, 11, 12, 12}, {12, 6, 14, 12}, {10, 0, 12, 5}, {8, 2, 14, 3}}
    };
    private static final RenderType SURFACE = RenderTypes.entityCutoutCull(TradeBoard.id("textures/entity/board_pixels.png"));
    private final Font font;
    private final Map<TradeBoardEntity, Frame> frames = new WeakHashMap<>();

    private record Quad(float x, float y, float right, float bottom, float depth, int color) {}
    private record Line(FormattedCharSequence text, float x, float y, float scale, int color) {}
    private record Drawing(int width, List<Quad> quads, List<Line> lines) {}
    private record Frame(BoardSnapshot snapshot, int width, long second, boolean replay, int page,
                         String language, Drawing drawing) {}

    static final class State extends BlockEntityRenderState {
        AABB bounds;
        Drawing drawing;
        int blocksHigh;
        float facing;
    }

    TradeBoardRenderer(BlockEntityRendererProvider.Context context) {
        font = context.font();
    }

    @Override
    public State createRenderState() { return new State(); }

    @Override
    public boolean shouldRenderOffScreen() { return true; }

    @Override
    public boolean shouldRender(TradeBoardEntity board, Vec3 camera) {
        if (!board.formed() || board.getLevel() == null || !board.getLevel().hasChunkAt(board.end())) return false;
        var center = board.bounds().getCenter();
        var facing = board.facing();
        double front = (camera.x - center.x) * facing.getStepX() + (camera.z - center.z) * facing.getStepZ();
        return front > BoardPanel.DEPTH - 0.5 && camera.distanceToSqr(center) <= TradeBoard.RANGE * TradeBoard.RANGE;
    }

    @Override
    public void extractRenderState(TradeBoardEntity board, State state, float partial, Vec3 camera,
                                   ModelFeatureRenderer.CrumblingOverlay crumbling) {
        BlockEntityRenderer.super.extractRenderState(board, state, partial, camera, crumbling);
        state.bounds = board.bounds();
        state.blocksHigh = board.height();
        state.facing = 180F - board.facing().toYRot();
        int width = HEIGHT * board.width() / board.height();
        var minecraft = Minecraft.getInstance();
        boolean replay = TradeClient.isReplay(minecraft);
        var snapshot = board.snapshot();
        long wallSecond = replay ? 0 : System.currentTimeMillis() / 1000;
        long phase = replay ? board.getLevel().getGameTime() / 20 : wallSecond;
        int pageCount = Math.ceilDiv(snapshot.orders().size(), ORDERS_PER_PAGE);
        int page = pageCount > 0 && Math.floorMod(phase, 25) >= 15
                ? Math.floorMod(Math.floorDiv(phase, 25), pageCount) : -1;
        long second = page < 0 && !snapshot.restocks().isEmpty() ? wallSecond : 0;
        String language = minecraft.getLanguageManager().getSelected();
        var frame = frames.get(board);
        if (frame == null || frame.snapshot() != snapshot || frame.width() != width || frame.second() != second
                || frame.replay() != replay || frame.page() != page || !frame.language().equals(language)) {
            var drawing = draw(width, snapshot, second * 1000, replay, page, pageCount);
            frame = new Frame(snapshot, width, second, replay, page, language, drawing);
            frames.put(board, frame);
        }
        state.drawing = frame.drawing();
    }

    @Override
    public void submit(State state, PoseStack poses, SubmitNodeCollector queue, CameraRenderState camera) {
        if (camera.cullFrustum != null && !camera.cullFrustum.isVisible(state.bounds)) return;
        float unit = (float) state.blocksHigh / HEIGHT;
        var drawing = state.drawing;
        poses.pushPose();
        poses.translate(0.5F, 0F, 0.5F);
        poses.mulPose(Axis.YP.rotationDegrees(state.facing));
        poses.translate(0.5, state.blocksHigh, 0.5 - BoardPanel.DEPTH - 0.002);
        poses.scale(-unit, -unit, unit);
        queue.submitCustomGeometry(poses, SURFACE, (pose, output) -> {
            for (var rect : drawing.quads()) {
                vertex(pose, output, rect.x(), rect.y(), rect.depth(), rect.color(), drawing.width());
                vertex(pose, output, rect.x(), rect.bottom(), rect.depth(), rect.color(), drawing.width());
                vertex(pose, output, rect.right(), rect.bottom(), rect.depth(), rect.color(), drawing.width());
                vertex(pose, output, rect.right(), rect.y(), rect.depth(), rect.color(), drawing.width());
            }
        });
        for (var line : drawing.lines()) {
            poses.pushPose();
            poses.translate(line.x(), line.y(), -0.86F);
            poses.scale(line.scale(), line.scale(), 1);
            queue.submitText(poses, 0, 0, line.text(), false, Font.DisplayMode.NORMAL, PANEL_LIGHT, line.color(), 0, 0);
            poses.popPose();
        }
        poses.popPose();
    }

    private Drawing draw(int width, BoardSnapshot snapshot, long now, boolean replay, int page, int pageCount) {
        var quads = new ArrayList<Quad>();
        var lines = new ArrayList<Line>();
        box(quads, 0, 0, width, HEIGHT, 0, FRAME);
        box(quads, 2, 2, width - 2, HEIGHT - 2, 1, PAPER);
        logo(quads);
        text(lines, Component.literal("TOWNTRADE").withStyle(net.minecraft.ChatFormatting.BOLD),
                51, 13, 2.75F, INK, width - 67);
        text(lines, label(page >= 0 ? "orders" : "title"), 53, 40, 1.22F, SUBTLE, width - 69);
        box(quads, 12, 56, width - 12, 58, 2, PALE_GREEN);

        if (page >= 0) {
            drawOrders(width, snapshot, page, quads, lines);
            box(quads, 2, 192, width - 2, HEIGHT - 2, 2, CARD);
            text(lines, label("orders_hint"), 15, 199, 0.95F, SUBTLE, width - 83);
            if (pageCount > 1) {
                var counter = Component.literal((page + 1) + " / " + pageCount);
                text(lines, counter, width - 14 - font.width(counter), 199, 1, SUBTLE, 60);
            }
        } else {
            drawOverview(width, snapshot, now, replay, quads, lines);
        }
        return new Drawing(width, List.copyOf(quads), List.copyOf(lines));
    }

    private void drawOverview(int width, BoardSnapshot snapshot, long now, boolean replay, List<Quad> quads,
                              List<Line> lines) {
        int split = width * 57 / 100;
        int cardWidth = (split - 19) / 2;
        for (int i = 0; i < SERVICES.length; i++) {
            int x = 12 + (i % 2) * (cardWidth + 6);
            int y = 65 + (i / 2) * 70;
            card(quads, x, y, x + cardWidth, y + 62, CARD);
            for (var icon : ICONS[i]) {
                box(quads, x + 7 + icon[0], y + 6 + icon[1], x + 7 + icon[2], y + 6 + icon[3], 4, SUBTLE);
            }
            int labelWidth = cardWidth - 14;
            float scale = Math.min(1.05F, (float) labelWidth / font.width(SERVICES[i]));
            text(lines, Component.literal(SERVICES[i]), x + 7, y + 27, scale, INK, labelWidth);
            var badge = serviceLabel(snapshot.status());
            boolean ready = snapshot.status() == BoardSnapshot.Status.READY;
            int badgeWidth = Math.min(font.width(badge) + 10, cardWidth - 12);
            box(quads, x + 6, y + 41, x + 6 + badgeWidth, y + 54, 4, ready ? PALE_GREEN : PAPER);
            text(lines, badge, x + 11, y + 43, 1, ready ? GREEN : AMBER, badgeWidth - 8);
        }

        int left = split + 7;
        int right = width - 12;
        card(quads, left, 65, right, 197, CARD);
        box(quads, left + 1, 66, right - 1, 88, 4, BORDER);
        text(lines, label("restocks"), left + 7, 72, 1.25F, INK, right - left - 14);
        if (snapshot.restocks().isEmpty()) {
            String key = snapshot.status() == BoardSnapshot.Status.READY ? "full_stock" : "no_data";
            text(lines, label(key), left + 8, 111, 1.15F, SUBTLE, right - left - 16);
            return;
        }
        for (int i = 0; i < snapshot.restocks().size(); i++) {
            var restock = snapshot.restocks().get(i);
            int y = 94 + i * 31;
            text(lines, restock.item(), left + 7, y + 2, 1.0F, INK, right - left - 14);
            text(lines, timeLabel(restock.at(), now, replay, snapshot.timezone(), CLOCK), left + 7, y + 13,
                    1.05F, GREEN, right - left - 14);
            if (i < snapshot.restocks().size() - 1) box(quads, left + 7, y + 25, right - 7, y + 26, 4, BORDER);
        }
    }

    private void drawOrders(int width, BoardSnapshot snapshot, int page, List<Quad> quads, List<Line> lines) {
        int left = 18;
        int rowWidth = width - 2 * left;
        int ownerX = left + rowWidth * 39 / 100;
        int priceX = left + rowWidth * 63 / 100;
        int stageX = left + rowWidth * 80 / 100;
        int dateWidth = rowWidth / 3;
        int start = page * ORDERS_PER_PAGE;
        int end = Math.min(start + ORDERS_PER_PAGE, snapshot.orders().size());
        var zone = ZoneId.of(snapshot.timezone());
        for (int i = start; i < end; i++) {
            var order = snapshot.orders().get(i);
            int y = 64 + (i - start) * 42;
            card(quads, 12, y, width - 12, y + 38, CARD);
            fitted(lines, Component.literal(order.quantity() + " × ").append(order.item()), left, y + 6,
                    INK, ownerX - left - 6);
            fitted(lines, Component.literal(order.requester().isEmpty() ? "—" : "@" + order.requester()),
                    ownerX, y + 6, SUBTLE, priceX - ownerX - 5);
            fitted(lines, label("copper", order.price()), priceX, y + 6, INK, stageX - priceX - 4);
            var phase = order.phase();
            int statusColor = switch (phase) {
                case WAITING -> AMBER;
                case PROTECTED -> BLUE;
                case PUBLIC -> GREEN;
            };
            fitted(lines, label("phase." + phase.name().toLowerCase(Locale.ROOT)), stageX, y + 6,
                    statusColor, width - left - stageX);
            box(quads, left, y + 19, width - left, y + 20, 4, BORDER);
            var arrival = label("arrives_at", date(order.arrives(), zone));
            var protection = order.arrives() > 0 && order.arrives() == order.protectsUntil()
                    ? label("no_protection") : label("protected_until", date(order.protectsUntil(), zone));
            var expiration = label("public_until", date(order.expires(), zone));
            fitted(lines, arrival, left, y + 25, phase == BoardSnapshot.Phase.WAITING ? statusColor : SUBTLE, dateWidth - 5);
            fitted(lines, protection, left + dateWidth, y + 25,
                    phase == BoardSnapshot.Phase.PROTECTED ? statusColor : SUBTLE, dateWidth - 5);
            fitted(lines, expiration, left + 2 * dateWidth, y + 25,
                    phase == BoardSnapshot.Phase.PUBLIC ? statusColor : SUBTLE, dateWidth);
        }
    }

    private static String date(long millis, ZoneId zone) {
        return millis == 0 ? "—" : DATE_AND_CLOCK.format(Instant.ofEpochMilli(millis).atZone(zone));
    }

    private void fitted(List<Line> lines, Component value, int x, int y, int color, int width) {
        float scale = Math.clamp((float) width / Math.max(1, font.width(value)), 0.72F, 1F);
        text(lines, value, x, y, scale, color, width);
    }

    private static Component serviceLabel(BoardSnapshot.Status status) {
        return label(switch (status) {
            case STARTING -> "starting";
            case READY -> "ready";
            case OFFLINE -> "offline";
        });
    }

    private static Component timeLabel(long at, long now, boolean replay, String timezone, DateTimeFormatter format) {
        if (replay) return Component.literal(format.format(Instant.ofEpochMilli(at).atZone(ZoneId.of(timezone))));
        return at <= now ? label("syncing") : Component.literal(TradeRules.permitDuration(at - now));
    }

    private static Component label(String key, Object... arguments) {
        return Component.translatable("towntrade.board." + key, arguments);
    }

    private void text(List<Line> lines, Component title, float x, float y, float scale, int color, int maximum) {
        int pixels = Math.max(1, (int) (maximum / scale));
        if (font.width(title) > pixels) {
            title = Component.literal(font.substrByWidth(title, Math.max(1, pixels - font.width("…"))).getString())
                    .append("…");
        }
        lines.add(new Line(title.getVisualOrderText(), x, y, scale, color));
    }

    private static void logo(List<Quad> quads) {
        box(quads, 15, 46, 42, 49, 3, SUBTLE);
        box(quads, 17, 42, 40, 44, 3, SUBTLE);
        for (int x = 18; x < 40; x += 8) box(quads, x, 26, x + 4, 41, 3, SUBTLE);
        for (int i = 0; i < 6; i++) box(quads, 15 + i * 2, 23 - i * 2, 42 - i * 2, 25 - i * 2, 3, SUBTLE);
    }

    private static void card(List<Quad> quads, int x, int y, int right, int bottom, int color) {
        box(quads, x, y, right, bottom, 2, BORDER);
        box(quads, x + 1, y + 1, right - 1, bottom - 1, 3, color);
    }

    private static void box(List<Quad> quads, int x, int y, int right, int bottom, int layer, int color) {
        quads.add(new Quad(x, y, right, bottom, -layer * 0.16F, color));
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer output, float x, float y, float z, int color, int width) {
        output.addVertex(pose, x, y, z).setColor(color).setUv(x / width, y / HEIGHT)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(PANEL_LIGHT).setNormal(pose, 0, 0, -1);
    }
}
