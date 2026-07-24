package net.tupenter.config;

import java.util.ArrayList;
import java.util.List;

/**
 * The examples a fresh install ships with, so the mod does something the
 * moment it's on rather than presenting an empty Scripting tab. Two custom
 * commands and four tick scripts — the scripts are DISARMED everywhere (no
 * world enables them), so nothing runs until the player flips one on in
 * Mod Menu. Bodies are stored multi-line for readability; the tick runner
 * collapses the whitespace to a single line before parsing, so the layout is
 * cosmetic. {@link DefaultExamplesTest} pins that every one parses and that no
 * name collides with a vanilla or built-in.
 */
public final class DefaultExamples {

    private DefaultExamples() {
    }

    static final String RAINBOW_TUNNEL =
            "rainbowTunnel =\n"
            + "#setdefault rc = blockset(\"red_wool\",\"orange_wool\",\"yellow_wool\",\"lime_wool\",\"light_blue_wool\")\n"
            + "&& #setdefault rt = 0\n"
            + "&& #setdefault ringon = false\n"
            + "&& #setdefault ringrad = 5\n"
            + "&& #setdefault ringoff = -2\n"
            + "&& #setdefault litgap = 10\n"
            + "&& #setdefault litfrom = client.pos\n"
            + "&& #if (client.keypress.right_bracket) (#set ringon = !ringon\n"
            + "    && /echohud &drainbow ring: $ringon ? \"&aON\" : \"&cOFF\"$)\n"
            + "&& #set rt = rt + 1\n"
            + "&& #if (ringon\n"
            + "    && client.speed > 2) (#local m = normalize(client.motion)\n"
            + "    && #local u = normalize(cross(m, abs(component(m, \"y\")) > 0.95 ? vec(1, 0, 0) : vec(0, 1, 0)))\n"
            + "    && #local v = cross(m, u)\n"
            + "    && #local c = vadd(client.pos, scale(m, ringoff))\n"
            + "    && #local pts = round(ringrad * 6.5)\n"
            + "    && #local half = round(pts / 2)\n"
            + "    && #local lantern = dist(client.pos, litfrom) > litgap\n"
            + "    && #for $i$ in 0..(pts - 1) (#local p = vadd(vadd(c, scale(u, ringrad*cos(360*i/pts))), scale(v, ringrad*sin(360*i/pts))) && #silent (/setblock $round(component(p, \"x\"))$ $round(component(p, \"y\"))$ $round(component(p, \"z\"))$ $lantern && (i == 0 || i == half) ? \"minecraft:glowstone\" : nth(rc, floor(((i + rt) % (len(rc)*3)) / 3))$ keep))\n"
            + "    && #if (lantern) (#set litfrom = client.pos))";

    static final String ITEM_DESPAWN_TIMER =
            "itemDespawnTimer =\n"
            + "#setdefault grave = \"\"\n"
            + "&& #setdefault graveleft = -1\n"
            + "&& #setdefault gravestamp = 0\n"
            + "&& #setdefault gravesaid = 999\n"
            + "&& #setdefault gravepaused = false\n"
            + "&& #setdefault gravereported = true\n"
            + "&& #if (client.just_died) (#set grave = client.blockpos\n"
            + "    && #local isvoid = block(grave) == \"minecraft:void_air\"\n"
            + "    && #if (isvoid) (/echo &cYou died in the void - nothing to recover && #set graveleft = -1) #else (#set graveleft = 300 && #set gravestamp = real.timestamp && #set gravesaid = 300 && #set gravepaused = false && #set gravereported = false))\n"
            + "&& #if (graveleft > 0\n"
            + "    && !gravereported\n"
            + "    && client.health > 0) (/echo &7Died at &f$grave$&7 - items despawn in about &e$floor(graveleft / 60)$:$graveleft % 60 < 10 ? \"0\" : \"\"$$graveleft % 60$&7 (paused while that chunk is unloaded)\n"
            + "    && #set gravereported = true)\n"
            + "&& #if (graveleft > 0\n"
            + "    && simulated(grave)) (#if (gravepaused) (/echo &aGrave chunk loaded again - about &e$floor(graveleft / 60)$:$graveleft % 60 < 10 ? \"0\" : \"\"$$graveleft % 60$&a left && #set gravepaused = false)\n"
            + "    && #set graveleft = graveleft - (real.timestamp - gravestamp)\n"
            + "    && #set gravestamp = real.timestamp\n"
            + "    && #local now = floor(graveleft)\n"
            + "    && #if (now < gravesaid) (#set gravesaid = now && #if (client.health > 0 && (now == 120 || now == 60 || now == 30 || (now <= 10 && now >= 1))) (/echo &7about &e$floor(now / 60)$:$now % 60 < 10 ? \"0\" : \"\"$$now % 60$&7 until items may despawn) && #if (now <= 0) (/echo &cItems have likely despawned by now, if not picked up && #set graveleft = -1)))\n"
            + "&& #if (graveleft > 0\n"
            + "    && !simulated(grave)) (#set gravestamp = real.timestamp\n"
            + "    && #if (!gravepaused) (/echo &8Timer paused - items will not despawn as long as nothing loads the chunk && #set gravepaused = true))";

    static final String CREEPER_ALERT =
            "creeperAlert =\n"
            + "#setdefault creeperNear = false\n"
            + "&& #local n = len(entities(16, \"minecraft:creeper\"))\n"
            + "&& #if (n > 0\n"
            + "    && !creeperNear) (/echo &cCreeper within 16 blocks! ($n$)\n"
            + "    && #set creeperNear = true)\n"
            + "&& #if (n == 0\n"
            + "    && creeperNear) (#set creeperNear = false)";

    static final String RESTOCK_REMINDER =
            "restockReminder =\n"
            + "#if (world.time == 2000) (/echo &aVillager morning restock!)";

    static final String BLINK =
            "blink <maxdistance:int=100> \"teleport to where you're looking, stopping at walls\" = #silent #local hit = raycast(maxdistance) && #if (hit == \"miss\") (/tp @s ^ ^ ^$maxdistance$) #else (/tp @s $hit$ && /tp @s ^ ^ ^-2)";

    static final String IRONKIT =
            "ironkit \"a full set of iron gear\" = /give @s minecraft:iron_sword && /give @s minecraft:iron_pickaxe && /give @s minecraft:iron_axe && /give @s minecraft:shield && /give @s minecraft:iron_shovel && /give @s minecraft:iron_hoe && /give @s minecraft:iron_boots && /give @s minecraft:iron_leggings && /give @s minecraft:iron_chestplate && /give @s minecraft:iron_helmet";

    /** Custom-command definitions, in stored "name <decls> \"desc\" = body" form. */
    public static List<String> aliases() {
        return List.of(BLINK, IRONKIT);
    }

    /** Tick-script definitions, each "name = body". Fixed ids so a reseed is stable. */
    public static List<TupenterConfig.GlobalScript> globalScripts() {
        return List.of(
                new TupenterConfig.GlobalScript("ex-tunnel", RAINBOW_TUNNEL),
                new TupenterConfig.GlobalScript("ex-despawn", ITEM_DESPAWN_TIMER),
                new TupenterConfig.GlobalScript("ex-creeper", CREEPER_ALERT),
                new TupenterConfig.GlobalScript("ex-restock", RESTOCK_REMINDER));
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
