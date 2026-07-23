package net.tupenter.command;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.tupenter.script.ExpressionException;
import net.tupenter.script.Value;
import net.tupenter.script.VarDoc;
import net.tupenter.script.VariableProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Live client state as read-only variables (docs/SCRIPTING_DESIGN.md §5.3).
 * Adding one is a single registration line — the parser picks it up for
 * resolution and error suggestions, and the help pages render it, because
 * registration REQUIRES a category and a blurb: an undocumented variable is
 * a compile error here, not a doc-drift bug.
 */
public final class ClientVariableProvider implements VariableProvider {

    private record Entry(String category, String blurb, Function<LocalPlayer, Value> reader) {
    }

    private static final String POSITION = "Position";
    private static final String ENVIRONMENT = "Environment";
    private static final String MOVEMENT = "Movement";
    private static final String VITALS = "Vitals & stats";
    private static final String HAZARDS = "Hazards";
    private static final String SESSION = "Session";

    private final Map<String, Entry> variables = new LinkedHashMap<>();

    public ClientVariableProvider() {
        // Two coordinate SPACES, one shape each — matching Mojang's
        // position() -> Vec3 (precise) and blockPosition() -> BlockPos (whole).
        // Components hang off the vec, so there's no parallel scalar vocabulary.
        register(POSITION, "client.pos", "where you are, precise — a vec3 \"x y z\" (+ .x/.y/.z)", player -> Value.of(
                round2(player.getX()) + " " + round2(player.getY()) + " " + round2(player.getZ())));
        register(POSITION, "client.pos.x", "the x of client.pos", player -> Value.ofNumber(player.getX()));
        register(POSITION, "client.pos.y", "the y of client.pos", player -> Value.ofNumber(player.getY()));
        register(POSITION, "client.pos.z", "the z of client.pos", player -> Value.ofNumber(player.getZ()));
        register(POSITION, "client.blockpos", "the block you're in — whole coords (+ .x/.y/.z)", player -> {
            BlockPos pos = player.blockPosition();
            return Value.of(pos.getX() + " " + pos.getY() + " " + pos.getZ());
        });
        register(POSITION, "client.blockpos.x", "the x of client.blockpos", player -> Value.ofNumber(player.blockPosition().getX()));
        register(POSITION, "client.blockpos.y", "the y of client.blockpos", player -> Value.ofNumber(player.blockPosition().getY()));
        register(POSITION, "client.blockpos.z", "the z of client.blockpos", player -> Value.ofNumber(player.blockPosition().getZ()));
        register(POSITION, "client.yaw", "horizontal facing, degrees", player -> Value.ofNumber(player.getYRot()));
        register(POSITION, "client.pitch", "vertical look angle, degrees — down is positive", player -> Value.ofNumber(player.getXRot()));
        register(POSITION, "client.facing", "the compass direction you face: north/south/east/west", player -> Value.of(player.getDirection().getName()));
        register(POSITION, "client.eye_pos", "your eyes' precise vec3 — a ray origin (+ .x/.y/.z)", player -> {
            net.minecraft.world.phys.Vec3 e = player.getEyePosition();
            return Value.of(round2(e.x) + " " + round2(e.y) + " " + round2(e.z));
        });
        register(POSITION, "client.look", "your unit look direction — a ray dir (+ .x/.y/.z)", player -> {
            net.minecraft.world.phys.Vec3 v = player.getLookAngle();
            return Value.of(round2(v.x) + " " + round2(v.y) + " " + round2(v.z));
        });
        // Full-precision components — vec strings can't be indexed in an expression, so
        // expose x/y/z directly for math (e.g. Motion:[look.x*speed, ...]). Not rounded:
        // rounding is a display convenience; math wants the exact unit vector.
        register(POSITION, "client.look.x", "the x of client.look (full precision)", player -> Value.ofNumber(player.getLookAngle().x));
        register(POSITION, "client.look.y", "the y of client.look (full precision)", player -> Value.ofNumber(player.getLookAngle().y));
        register(POSITION, "client.look.z", "the z of client.look (full precision)", player -> Value.ofNumber(player.getLookAngle().z));
        register(POSITION, "client.eye_pos.x", "the x of client.eye_pos (full precision)", player -> Value.ofNumber(player.getEyePosition().x));
        register(POSITION, "client.eye_pos.y", "the y of client.eye_pos (full precision)", player -> Value.ofNumber(player.getEyePosition().y));
        register(POSITION, "client.eye_pos.z", "the z of client.eye_pos (full precision)", player -> Value.ofNumber(player.getEyePosition().z));
        register(POSITION, "client.dimension", "the dimension you're in", player -> Value.of(player.level().dimension().location().toString()));

        // position context
        register(ENVIRONMENT, "client.biome", "the biome at your feet", player -> player.level().getBiome(player.blockPosition()).unwrapKey()
                .map(key -> Value.of(key.location().toString()))
                .orElseThrow(() -> new ExpressionException("client.biome: unregistered biome")));
        register(ENVIRONMENT, "client.light", "combined light level where you stand, 0-15", player -> Value.ofNumber(player.level().getMaxLocalRawBrightness(player.blockPosition())));
        register(ENVIRONMENT, "client.light_block", "block-light only (torches, lava, ...)", player -> Value.ofNumber(
                player.level().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, player.blockPosition())));
        register(ENVIRONMENT, "client.light_sky", "sky-light only (before darkness and weather)", player -> Value.ofNumber(
                player.level().getBrightness(net.minecraft.world.level.LightLayer.SKY, player.blockPosition())));
        register(ENVIRONMENT, "client.chunk_x", "your chunk's x", player -> Value.ofNumber(player.chunkPosition().x));
        register(ENVIRONMENT, "client.chunk_z", "your chunk's z", player -> Value.ofNumber(player.chunkPosition().z));

        // movement + orientation
        register(MOVEMENT, "client.speed", "how fast you're moving, blocks/sec (full 3D)", player -> {
            net.minecraft.world.phys.Vec3 v = player.getDeltaMovement();
            return Value.ofNumber(round2(v.length() * 20));
        });
        register(MOVEMENT, "client.speed_xz", "horizontal speed, blocks/sec", player -> {
            net.minecraft.world.phys.Vec3 v = player.getDeltaMovement();
            return Value.ofNumber(round2(Math.hypot(v.x, v.z) * 20));
        });
        // (no speed_y — that's motion.y)
        register(MOVEMENT, "client.motion", "your velocity vec3, blocks/sec (+ .x/.y/.z)", player -> {
            net.minecraft.world.phys.Vec3 v = player.getDeltaMovement();
            return Value.of(round2(v.x * 20) + " " + round2(v.y * 20) + " " + round2(v.z * 20));
        });
        register(MOVEMENT, "client.motion.x", "the x of client.motion", player -> Value.ofNumber(round2(player.getDeltaMovement().x * 20)));
        register(MOVEMENT, "client.motion.y", "the y of client.motion — signed vertical speed", player -> Value.ofNumber(round2(player.getDeltaMovement().y * 20)));
        register(MOVEMENT, "client.motion.z", "the z of client.motion", player -> Value.ofNumber(round2(player.getDeltaMovement().z * 20)));
        register(MOVEMENT, "client.on_ground", "standing on something?", player -> Value.of(player.onGround()));
        register(MOVEMENT, "client.sneaking", "sneaking?", player -> Value.of(player.isCrouching()));
        register(MOVEMENT, "client.sprinting", "sprinting?", player -> Value.of(player.isSprinting()));
        register(MOVEMENT, "client.swimming", "in the swimming pose?", player -> Value.of(player.isSwimming()));
        register(MOVEMENT, "client.flying", "creative flight active?", player -> Value.of(player.getAbilities().flying));
        register(MOVEMENT, "client.gliding", "elytra deployed?", player -> Value.of(player.isFallFlying()));
        register(MOVEMENT, "client.fall_distance", "blocks fallen so far", player -> Value.ofNumber(round2(player.fallDistance)));
        register(MOVEMENT, "client.riding", "in/on a vehicle? — the gate for client.vehicle.*", player -> Value.of(player.getVehicle() != null));

        // stats
        register(VITALS, "client.health", "your health — 20 is full hearts", player -> Value.ofNumber(player.getHealth()));
        register(VITALS, "client.max_health", "your health cap", player -> Value.ofNumber(player.getMaxHealth()));
        register(VITALS, "client.absorption", "yellow absorption hearts", player -> Value.ofNumber(player.getAbsorptionAmount()));
        register(VITALS, "client.food", "the hunger bar, 0-20", player -> Value.ofNumber(player.getFoodData().getFoodLevel()));
        register(VITALS, "client.saturation", "hidden saturation behind the hunger bar", player -> Value.ofNumber(player.getFoodData().getSaturationLevel()));
        register(VITALS, "client.air", "air supply while submerged — 300 is full", player -> Value.ofNumber(player.getAirSupply()));
        register(VITALS, "client.armor", "armor points, 0-20", player -> Value.ofNumber(player.getArmorValue()));
        register(VITALS, "client.xp_level", "your experience level", player -> Value.ofNumber(player.experienceLevel));
        register(VITALS, "client.xp_progress", "progress to the next level, 0..1", player -> Value.ofNumber(round2(player.experienceProgress)));
        // effects — a list, so len(), rand(), and #foreach all compose
        register(VITALS, "client.effects", "your active effect ids — a LIST for #foreach", player -> {
            java.util.List<Value> ids = new java.util.ArrayList<>();
            for (net.minecraft.world.effect.MobEffectInstance effect : player.getActiveEffects()) {
                effect.getEffect().unwrapKey().ifPresent(key -> ids.add(Value.of(key.location().toString())));
            }
            return new Value.ListValue(java.util.List.copyOf(ids));
        });

        // hazards + surroundings
        register(HAZARDS, "client.in_water", "touching water?", player -> Value.of(player.isInWater()));
        register(HAZARDS, "client.underwater", "eyes submerged?", player -> Value.of(player.isUnderWater()));
        register(HAZARDS, "client.in_lava", "touching lava?", player -> Value.of(player.isInLava()));
        register(HAZARDS, "client.on_fire", "burning?", player -> Value.of(player.isOnFire()));

        // identity + connection/session
        register(SESSION, "client.name", "your player name", player -> Value.of(player.getName().getString()));
        register(SESSION, "client.gamemode", "your game mode", player -> Value.of(playerInfo(player).getGameMode().getName()));
        register(SESSION, "client.ping", "your latency, ms", player -> Value.ofNumber(playerInfo(player).getLatency()));
        register(SESSION, "client.fps", "frames per second", player -> Value.ofNumber(Minecraft.getInstance().getFps()));
        register(SESSION, "client.uuid", "your UUID, dashed text", player -> Value.of(player.getStringUUID()));
        // NBT-flavored UUID for Owner-style fields: {Owner:[I;a,b,c,d]}
        register(SESSION, "client.uuid_nbt", "your UUID as an NBT int-array — for Owner-style fields", player -> {
            int[] parts = net.minecraft.core.UUIDUtil.uuidToIntArray(player.getUUID());
            return Value.of("[I;" + parts[0] + "," + parts[1] + "," + parts[2] + "," + parts[3] + "]");
        });
        // which hotbar slot is SELECTED (0-8). Named ...selected_slot because a bare
        // "client.slot" read like a namespace root — that's now slot CONTENTS,
        // client.slot.<slot>.<field> (see SlotVariableProvider).
        register(SESSION, "client.selected_slot", "which hotbar slot is selected, 0-8", player -> Value.ofNumber(player.getInventory().getSelectedSlot()));
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static net.minecraft.client.multiplayer.PlayerInfo playerInfo(LocalPlayer player) {
        net.minecraft.client.multiplayer.PlayerInfo info = player.connection.getPlayerInfo(player.getUUID());
        if (info == null) {
            throw new ExpressionException("player info isn't synced yet — try again in a moment");
        }
        return info;
    }

    private void register(String category, String name, String blurb, Function<LocalPlayer, Value> reader) {
        variables.put(name, new Entry(category, blurb, reader));
    }

    /** Every variable's doc, in registration order — the client subject page renders from this. */
    public List<VarDoc> docs() {
        List<VarDoc> docs = new ArrayList<>();
        variables.forEach((name, entry) -> docs.add(new VarDoc(name, entry.category(), entry.blurb())));
        return docs;
    }

    @Override
    public String describe(String name) {
        Entry entry = variables.get(name.toLowerCase(java.util.Locale.ROOT));
        return entry == null ? null : entry.blurb();
    }

    @Override
    public Set<String> names() {
        return variables.keySet();
    }

    @Override
    public Optional<Value> resolve(String name) {
        Entry entry = variables.get(name.toLowerCase(java.util.Locale.ROOT));
        if (entry == null) {
            return Optional.empty();
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            throw new ExpressionException(name + " is only available in-game");
        }
        return Optional.of(entry.reader().apply(player));
    }
}
