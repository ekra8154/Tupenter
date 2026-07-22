package net.tupenter.script;

/**
 * Casts a collision ray through the world for the {@code raycast(...)} and
 * {@code raycast_block(...)} expression functions — the same crosshair-style
 * hit test the vanilla client uses (hits collidable block outlines, passes
 * through grass/flowers/fluids). The client backs this with {@code Level.clip};
 * {@link #NONE} means no world available. A miss (or no world) is null — the
 * expression layer turns that into the "miss" sentinel string.
 */
public interface Raycaster {

    /** No world to cast in (tests without a stub, menus): every cast misses. */
    Raycaster NONE = new Raycaster() {
        @Override
        public String fromPlayer(double maxDist) {
            return null;
        }

        @Override
        public String cast(double ox, double oy, double oz, double dx, double dy, double dz, double maxDist) {
            return null;
        }
    };

    /**
     * Cast from the player's eyes along their look direction, up to
     * {@code maxDist} blocks.
     *
     * @return the hit block's position as "x y z" (integer block coords), or
     *         null on a miss / no player / no world
     */
    String fromPlayer(double maxDist);

    /**
     * Cast a general ray from {@code (ox, oy, oz)} in direction
     * {@code (dx, dy, dz)} (normalized by the implementation), up to
     * {@code maxDist} blocks.
     *
     * @return the hit block's position as "x y z", or null on a miss / no world
     */
    String cast(double ox, double oy, double oz, double dx, double dy, double dz, double maxDist);
}
