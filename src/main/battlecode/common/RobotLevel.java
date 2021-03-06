package battlecode.common;

/**
 * This enumeration distinguishes objects that are on the ground, or in
 * the air at a given location.
 *
 * Since RobotLevel is a Java 1.5 enum, you can use it in <code>switch</code>
 * statements, it has all the standard enum methods (<code>valueOf</code>,
 * <code>values</code>, etc.), and you can safely use <code>==</code> for
 * equality tests.
 */
public enum RobotLevel {

    MINE(0),
    /** The vertical location of ground robots. */
    ON_GROUND(1),
    /** The vertical location of air robots. */
    IN_AIR(2);
    /** An integer representation of the height. */
    private int height;

    /**
     * Creates a new RobotLevel, based on the given height.
     *
     * @param height the integer representation of the MapHeight
     */
    private RobotLevel(int height) {
        this.height = height;
    }

    /**
     * Returns an integral height-wise ordering of the height type.
     */
    public int getHeight() {
        return height;
    }
}
