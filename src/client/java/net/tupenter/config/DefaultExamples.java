package net.tupenter.config;

import java.util.ArrayList;
import java.util.List;

/**
 * The examples a fresh install ships with, so the mod does something the moment
 * it's on rather than presenting an empty Scripting tab. Five custom commands
 * and five tick scripts — the scripts are DISARMED everywhere (no world enables
 * them), so nothing runs until the player flips one on in Mod Menu. Bodies are
 * stored multi-line for readability; the tick runner collapses the whitespace to
 * a single line before parsing, so the layout is cosmetic. DefaultExamplesTest
 * pins that every one parses and that no name collides with a vanilla or
 * built-in.
 */
public final class DefaultExamples {

    private DefaultExamples() {
    }

    static final String RAINBOW_TUNNEL =
            "rainbowTunnel =\n"
            + "#setdefault rc = blockset(\"red_wool\",\"orange_wool\",\"yellow_wool\",\"lime_wool\",\"light_blue_wool\") &&\n"
            + "#setdefault rt = 0 &&\n"
            + "#setdefault ringon = false &&\n"
            + "#setdefault ringrad = 5 &&\n"
            + "#setdefault ringoff = -2 &&\n"
            + "#setdefault litgap = 10 &&\n"
            + "#setdefault litfrom = client.pos &&\n"
            + "#if (client.keypress.right_bracket) (\n"
            + "  #set ringon = !ringon &&\n"
            + "  /echohud &drainbow ring: $ringon ? \"&aON\" : \"&cOFF\"$) &&\n"
            + "#set rt = rt + 1 &&\n"
            + "#if (ringon && client.speed > 2) (\n"
            + "  #local m = normalize(client.motion) &&\n"
            + "  #local u = normalize(cross(m, abs(component(m, \"y\")) > 0.95 ? vec(1, 0, 0) : vec(0, 1, 0))) &&\n"
            + "  #local v = cross(m, u) &&\n"
            + "  #local c = vadd(client.pos, scale(m, ringoff)) &&\n"
            + "  #local pts = round(ringrad * 6.5) &&\n"
            + "  #local half = round(pts / 2) &&\n"
            + "  #local lantern = dist(client.pos, litfrom) > litgap &&\n"
            + "  #for $i$ in 0..(pts - 1) (\n"
            + "    #local p = vadd(vadd(c, scale(u, ringrad*cos(360*i/pts))), scale(v, ringrad*sin(360*i/pts))) &&\n"
            + "    #silent (/setblock $round(component(p, \"x\"))$ $round(component(p, \"y\"))$ $round(component(p, \"z\"))$ $lantern && (i == 0 || i == half) ? \"minecraft:glowstone\" : nth(rc, floor(((i + rt) % (len(rc)*3)) / 3))$ keep)) &&\n"
            + "  #if (lantern) (#set litfrom = client.pos))";

    static final String ITEM_DESPAWN_TIMER =
            "itemDespawnTimer =\n"
            + "#setdefault grave = \"\" &&\n"
            + "#setdefault graveleft = -1 &&\n"
            + "#setdefault gravestamp = 0 &&\n"
            + "#setdefault gravesaid = 999 &&\n"
            + "#setdefault gravepaused = false &&\n"
            + "#setdefault gravereported = true &&\n"
            + "#if (client.just_died) (\n"
            + "  #set grave = client.blockpos &&\n"
            + "  #local isvoid = block(grave) == \"minecraft:void_air\" &&\n"
            + "  #if (isvoid) (\n"
            + "    /echo &cYou died in the void - nothing to recover &&\n"
            + "    #set graveleft = -1)\n"
            + "  #else (\n"
            + "    #set graveleft = 300 &&\n"
            + "    #set gravestamp = real.timestamp &&\n"
            + "    #set gravesaid = 300 &&\n"
            + "    #set gravepaused = false &&\n"
            + "    #set gravereported = false)) &&\n"
            + "#if (graveleft > 0 && !gravereported && client.health > 0) (\n"
            + "  /echo &7Died at &f$grave$&7 - items despawn in about &e$floor(graveleft / 60)$:$graveleft % 60 < 10 ? \"0\" : \"\"$$graveleft % 60$&7 (paused while that chunk is unloaded) &&\n"
            + "  #set gravereported = true) &&\n"
            + "#if (graveleft > 0 && simulated(grave)) (\n"
            + "  #if (gravepaused) (\n"
            + "    /echo &aGrave chunk loaded again - about &e$floor(graveleft / 60)$:$graveleft % 60 < 10 ? \"0\" : \"\"$$graveleft % 60$&a left &&\n"
            + "    #set gravepaused = false) &&\n"
            + "  #set graveleft = graveleft - (real.timestamp - gravestamp) &&\n"
            + "  #set gravestamp = real.timestamp &&\n"
            + "  #local now = floor(graveleft) &&\n"
            + "  #if (now < gravesaid) (\n"
            + "    #set gravesaid = now &&\n"
            + "    #if (client.health > 0 && (now == 120 || now == 60 || now == 30 || (now <= 10 && now >= 1))) (/echo &7about &e$floor(now / 60)$:$now % 60 < 10 ? \"0\" : \"\"$$now % 60$&7 until items may despawn) &&\n"
            + "    #if (now <= 0) (\n"
            + "      /echo &cItems have likely despawned by now, if not picked up &&\n"
            + "      #set graveleft = -1))) &&\n"
            + "#if (graveleft > 0 && !simulated(grave)) (\n"
            + "  #set gravestamp = real.timestamp &&\n"
            + "  #if (!gravepaused) (\n"
            + "    /echo &8Timer paused - items will not despawn as long as nothing loads the chunk &&\n"
            + "    #set gravepaused = true))";

    static final String CREEPER_ALERT =
            "creeperAlert =\n"
            + "#setdefault creeperNear = false &&\n"
            + "#if (client.gamemode == \"survival\") (\n"
            + "  #local c = nearest_entity(8, \"minecraft:creeper\") &&\n"
            + "  #if (c != \"miss\" && !creeperNear) (\n"
            + "    #local d = round(dist(client.pos, entity(c, \"pos\", client.pos))) &&\n"
            + "    /echo &cCreeper $d$ blocks away! &&\n"
            + "    #set creeperNear = true) &&\n"
            + "  #if (c == \"miss\" && creeperNear) (#set creeperNear = false))\n"
            + "#else (#set creeperNear = false)";

    static final String RESTOCK_REMINDER =
            "restockReminder =\n"
            + "#if (world.time == 2000) (/echo &aVillager morning restock!)";

    static final String ELYTRA_WARNING =
            "elytraWarning =\n"
            + "#if (client.slot.armor.chest.id == \"minecraft:elytra\" && client.slot.armor.chest.durability < 70) (/echohud &cElytra: &f$client.slot.armor.chest.durability$&c / 432 left)";

    static final String BLINK =
            "blink <maxdistance:int=100> \"teleport to where you're looking, stopping at walls\" =\n"
            + "#silent #local hit = raycast(maxdistance) &&\n"
            + "#if (hit == \"miss\") (/tp @s ^ ^ ^$maxdistance$)\n"
            + "#else (\n"
            + "  /tp @s $hit$ &&\n"
            + "  /tp @s ^ ^ ^-2)";

    static final String IRONKIT =
            "ironkit \"a full set of iron gear\" =\n"
            + "/give @s minecraft:iron_sword &&\n"
            + "/give @s minecraft:iron_pickaxe &&\n"
            + "/give @s minecraft:iron_axe &&\n"
            + "/give @s minecraft:shield &&\n"
            + "/give @s minecraft:iron_shovel &&\n"
            + "/give @s minecraft:iron_hoe &&\n"
            + "/give @s minecraft:iron_boots &&\n"
            + "/give @s minecraft:iron_leggings &&\n"
            + "/give @s minecraft:iron_chestplate &&\n"
            + "/give @s minecraft:iron_helmet";

    static final String PORTALCALC =
            "portalcalc <p:blockpos=~ ~ ~> <dim:to_overworld,to_nether=$client.dimension == \"minecraft:the_nether\" ? \"to_overworld\" : \"to_nether\"$> =\n"
            + "/echo $dim$: $floor(dim == \"to_nether\" ? p.x / 8 : p.x * 8)$ $p.y$ $floor(dim == \"to_nether\" ? p.z / 8 : p.z * 8)$";

    static final String TICKFREEZE =
            "tickfreeze \"toggle time freeze\" =\n"
            + "#if (world.frozen) (/tick unfreeze)\n"
            + "#else (/tick freeze)";

    static final String LAUNCH =
            "launch <entity:entity> <speed:float=5.0> <no_gravity:bool=false> \"Launch an entity where you're looking, at <speed> blocks/sec\" =\n"
            + "#local e = entity &&\n"
            + "#local mv = scale(client.look, speed) &&\n"
            + "#local pos = vadd(client.eye_pos, scale(client.look, 1.5)) &&\n"
            + "#local ms = component(mv, \"x\") + \"d,\" + component(mv, \"y\") + \"d,\" + component(mv, \"z\") + \"d\" &&\n"
            + "#local n = no_gravity ? \",NoGravity:1b\" : \"\" &&\n"
            + "#if (e == \"minecraft:fireball\") (/summon $e$ $pos$ {Motion:[$ms$],ExplosionPower:50b$n$})\n"
            + "#elseif (e == \"minecraft:ender_pearl\") (/summon $e$ $pos$ {Motion:[$ms$],Owner:$client.uuid_nbt$$n$})\n"
            + "#else (/summon $e$ $pos$ {Motion:[$ms$]$n$})";

    /** Custom-command definitions, in stored "name <decls> \"desc\" = body" form. */
    public static List<String> aliases() {
        return List.of(BLINK, IRONKIT, PORTALCALC, TICKFREEZE, LAUNCH);
    }

    /** Tick-script definitions, each "name = body". Fixed ids so a reseed is stable. */
    public static List<TupenterConfig.GlobalScript> globalScripts() {
        return List.of(
                new TupenterConfig.GlobalScript("ex-tunnel", RAINBOW_TUNNEL),
                new TupenterConfig.GlobalScript("ex-despawn", ITEM_DESPAWN_TIMER),
                new TupenterConfig.GlobalScript("ex-creeper", CREEPER_ALERT),
                new TupenterConfig.GlobalScript("ex-restock", RESTOCK_REMINDER),
                new TupenterConfig.GlobalScript("ex-elytra", ELYTRA_WARNING));
    }

    /**
     * Populate a config that has no examples yet — called once, when there is no
     * config file on disk. Only fills a list that is still empty, so it never
     * fights anything the user has already made. worldScripts is left untouched,
     * which is what disarms every seeded script.
     */
    public static void seed(TupenterConfig config) {
        if (config.aliases == null || config.aliases.isEmpty()) {
            config.aliases = new ArrayList<>(aliases());
        }
        if (config.globalScripts == null || config.globalScripts.isEmpty()) {
            config.globalScripts = new ArrayList<>(globalScripts());
        }
    }
}
