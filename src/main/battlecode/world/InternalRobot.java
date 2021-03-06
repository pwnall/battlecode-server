package battlecode.world;

import static battlecode.common.GameConstants.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

import battlecode.common.Chassis;
import battlecode.common.ComponentClass;
import battlecode.common.ComponentType;
import battlecode.common.Direction;
import battlecode.common.GameConstants;
import battlecode.common.MapLocation;
import battlecode.common.Message;
import battlecode.common.Robot;
import battlecode.common.Team;
import battlecode.engine.GenericRobot;
import battlecode.engine.signal.Signal;
import battlecode.server.Config;
import battlecode.world.signal.DeathSignal;
import battlecode.world.signal.TurnOffSignal;

public class InternalRobot extends InternalObject implements Robot, GenericRobot {

    /**
     * Robots that are inside a transport are considered to be at this
     * location, so that no one but the dropship will be able to sense them.
     */
    public static final MapLocation VERY_FAR_AWAY = new MapLocation(-1000, -1000);
    private volatile double myEnergonLevel;
    protected volatile Direction myDirection;
    private volatile boolean energonChanged = true;
    protected volatile long controlBits;
    // is this used ever?
    protected volatile boolean hasBeenAttacked = false;
    private static boolean upkeepEnabled = Config.getGlobalConfig().getBoolean("bc.engine.upkeep-enabled");
    /** first index is robot type, second is direction, third is x or y */
    private static final Map<ComponentType, int[][][]> offsets = GameMap.computeVisibleOffsets();
    /** number of bytecodes used in the most recent round */
    private volatile int bytecodesUsed = 0;
    private List<Message> incomingMessageQueue;
    protected GameMap.MapMemory mapMemory;
    private InternalRobotBuffs buffs = new InternalRobotBuffs(this);
    public final Chassis chassis;
    private List<BaseComponent> newComponents;
    private volatile boolean on;
    private volatile boolean hasBeenOff;
    private volatile int cores;
    private volatile int platings;
    private volatile int regens;
    private volatile int weight;
    private volatile int invulnerableRounds;
    private volatile InternalRobot transporter;
    private Set<InternalRobot> passengers;
    private RobotControllerImpl rc;
    private volatile Bug buggedBy;
    private volatile int dummyRounds = GameConstants.DUMMY_LIFETIME;

    static class ComponentList {

        ArrayList<BaseComponent> all;
        EnumMap<ComponentClass, ArrayList<BaseComponent>> byClass;

        public ComponentList() {
            all = new ArrayList<BaseComponent>();
            byClass = new EnumMap<ComponentClass, ArrayList<BaseComponent>>(ComponentClass.class);
            for (ComponentClass c : ComponentClass.values())
                byClass.put(c, new ArrayList<BaseComponent>());
        }

        public void add(BaseComponent t) {
            all.add(t);
            byClass.get(t.type().componentClass).add(t);
        }

        public ArrayList<BaseComponent> get(ComponentClass cls) {
            return byClass.get(cls);
        }

        public ArrayList<BaseComponent> values() {
            return all;
        }

        public boolean hasComponent(ComponentType type) {
            for (BaseComponent bc : all) {
                if (bc.type == type) {
                    return true;
                }
            }
            return false;
        }
    }
    private ComponentList components = new ComponentList();

    public InternalRobotBuffs getBuffs() {
        return buffs;
    }

    public RobotControllerImpl getRC() {
        return rc;
    }

    public void setRC(RobotControllerImpl rc) {
        this.rc = rc;
    }

    @SuppressWarnings("unchecked")
    public InternalRobot(GameWorld gw, Chassis chassis, MapLocation loc, Team t,
            boolean spawnedRobot) {
        super(gw, loc, chassis.level, t);
        myDirection = Direction.values()[gw.getRandGen().nextInt(8)];
        this.chassis = chassis;

        myEnergonLevel = getMaxEnergon();

        incomingMessageQueue = new LinkedList<Message>();

        mapMemory = new GameMap.MapMemory(gw.getGameMap());
        saveMapMemory(null, loc, false);
        controlBits = 0;

        newComponents = new ArrayList<BaseComponent>();

        switch (chassis) {
            case DUMMY:
            case DEBRIS:
                on = false;
            default:
                on = true;
        }
    }

    public boolean inTransport() {
        return transporter != null;
    }

    @Override
    public InternalRobot container() {
        return transporter;
    }

    public boolean isOn() {
        return on;
    }

    public void setPower(boolean b) {
        if (b && !on) {
            hasBeenOff = true;
            for (BaseComponent c : components.values()) {
                c.activate(POWER_WAKE_DELAY);
            }
        }
        on = b;
    }

    public void setBugged(Bug b) {
        if (buggedBy != null) {
            buggedBy.removeBug();
        }
        buggedBy = b;
    }

    public boolean queryHasBeenOff() {
        boolean tmp = hasBeenOff;
        hasBeenOff = false;
        return tmp;
    }

    public boolean hasRoomFor(ComponentType c) {
        if (c == ComponentType.IRON) {
            for (BaseComponent bc : components.get(ComponentClass.ARMOR)) {
                if (bc.type == ComponentType.IRON) {
                    return false;
                }
            }
        }
        return c.weight + weight <= chassis.weight;
    }

    public void equip(ComponentType type) {
        BaseComponent controller;
        switch (type) {
            case SHIELD:
            case HARDENED:
            case REGEN:
            case PLASMA:
            case PLATING:
            case PROCESSOR:
                controller = new BaseComponent(type, this);
                break;
            case SMG:
            case BLASTER:
            case RAILGUN:
            case HAMMER:
            case MEDIC:
            case BEAM:
                controller = new Weapon(type, this);
                break;
            case SATELLITE:
            case TELESCOPE:
            case SIGHT:
            case RADAR:
            case BUILDING_SENSOR:
                controller = new Sensor(type, this);
                break;
            case BUG:
                controller = new Bug(type, this);
				break;
            case ANTENNA:
            case DISH:
            case NETWORK:
                controller = new Radio(type, this);
                break;
            case RECYCLER:
                controller = new Miner(this);
                break;
            case CONSTRUCTOR:
            case FACTORY:
            case ARMORY:
            case DUMMY:
                controller = new Builder(type, this);
                break;
            case SMALL_MOTOR:
            case MEDIUM_MOTOR:
            case LARGE_MOTOR:
            case FLYING_MOTOR:
            case BUILDING_MOTOR:
                controller = new Motor(type, this);
                break;
            case JUMP:
                controller = new JumpCore(type, this);
                break;
            case DROPSHIP:
                controller = new Dropship(this);
                break;
            case IRON:
                controller = new IronComponent(type, this);
                break;
            default:
                throw new RuntimeException("component " + type + " is not supported yet");
        }
        components.add(controller);
        newComponents.add(controller);
        if (myGameWorld.getCurrentRound() >= 0) {
            controller.activate(EQUIP_WAKE_DELAY);
        }
        weight += type.weight;
        switch (type) {
            case PLATING:
                platings++;
                changeEnergonLevel(GameConstants.PLATING_HP_BONUS);
                break;
            case PROCESSOR:
                cores++;
                break;
            case REGEN:
                regens++;
                break;
            case DROPSHIP:
                passengers = new HashSet<InternalRobot>();
                break;
        }
    }

    /*
    public void unequip(BaseComponent c) {
    c.getComponent().setController(null);
    components.remove(c);
    ComponentType type = c.type();
    weight-=type.weight;
    if(type==ComponentType.PLATING)
    platings--;
    else if(type==ComponentType.PROCESSOR)
    cores--;
    }
     */
    public void addAction(Signal s) {
        myGameWorld.visitSignal(s);
    }

    public Chassis getChassis() {
        return chassis;
    }

    /*
    public InternalComponent [] getComponents() {
    return Iterables.toArray(Iterables.transform(components.values(),Util.controllerToComponent),InternalComponent.class);
    }
     */
    public ComponentType[] getComponentTypes() {
        return Iterables.toArray(Iterables.transform(components.values(), Util.typeOfComponent), ComponentType.class);
    }

    public BaseComponent[] getComponentControllers() {
        return components.values().toArray(new BaseComponent[0]);
    }

    public BaseComponent[] getNewComponentControllers() {
        BaseComponent[] controllers = newComponents.toArray(new BaseComponent[0]);
        newComponents.clear();
        return controllers;
    }

    public BaseComponent[] getComponentControllers(ComponentClass cl) {
        return components.get(cl).toArray(new BaseComponent[0]);
    }

    public BaseComponent[] getComponentControllers(final ComponentType t) {
        Predicate<BaseComponent> p = new Predicate<BaseComponent>() {

            public boolean apply(BaseComponent c) {
                return c.type() == t;
            }
        };
        Iterable<BaseComponent> filtered = Iterables.filter(components.get(t.componentClass), p);
        return Iterables.toArray(filtered, BaseComponent.class);
    }

    @Override
    public void processBeginningOfRound() {
        super.processBeginningOfRound();
        buffs.processBeginningOfRound();
    }

    public void processBeginningOfTurn() {
        rc.processBeginningOfTurn();
        for (BaseComponent c : components.values()) {
            c.processBeginningOfTurn();
        }
        if (invulnerableRounds > 0) {
            invulnerableRounds--;
        }
        if (on && !myGameWorld.spendResources(getTeam(), chassis.upkeep)) {
            myGameWorld.visitSignal(new TurnOffSignal(this, false));
        }
        if (isOn())
            changeEnergonLevel(regens * REGEN_AMOUNT);
    }

    @Override
    public void processEndOfTurn() {
        super.processEndOfTurn();

        for (BaseComponent c : components.values()) {
            c.processEndOfTurn();
        }
    }

    @Override
    public void processEndOfRound() {
        super.processEndOfRound();
        buffs.processEndOfRound();

        if (chassis == Chassis.DUMMY) {
            dummyRounds--;
            if (dummyRounds <= 0)
                suicide();
        }

        if (myEnergonLevel <= 0) {
            suicide();
        }
    }

    public double getEnergonLevel() {
        return myEnergonLevel;
    }

    public Direction getDirection() {
        return myDirection;
    }

    public void activateShield() {
        invulnerableRounds = IRON_EFFECT_ROUNDS;
    }

    public void takeDamage(double baseAmount) {
        if (baseAmount < 0) {
            changeEnergonLevel(-baseAmount);
            return;
        }
        if (!isOn()) {
            changeEnergonLevelFromAttack(-baseAmount);
            return;
        }
        if (invulnerableRounds > 0) {
            return;
        }
        boolean haveHardened = false;
        double minDamage = Math.min(SHIELD_MIN_DAMAGE, baseAmount);
        for (BaseComponent c : components.get(ComponentClass.ARMOR)) {
            switch (c.type()) {
                case SHIELD:
                    baseAmount -= SHIELD_DAMAGE_REDUCTION;
                    break;
                case HARDENED:
                    haveHardened = true;
                    break;
                case PLASMA:
                    if (!c.isActive()) {
                        c.activate();
                        return;
                    }
                    break;
            }
        }
        if (haveHardened && baseAmount > HARDENED_MAX_DAMAGE) {
            changeEnergonLevelFromAttack(-HARDENED_MAX_DAMAGE);
        } else {
            changeEnergonLevelFromAttack(-Math.max(minDamage, baseAmount));
        }
    }

    public void changeEnergonLevelFromAttack(double amount) {
        hasBeenAttacked = true;
        changeEnergonLevel(amount * (buffs.getDamageReceivedMultiplier() + 1));
    }

    public void changeEnergonLevel(double amount) {
        myEnergonLevel += amount;
        if (myEnergonLevel > getMaxEnergon()) {
            myEnergonLevel = getMaxEnergon();
        }
        energonChanged = true;

        if (myEnergonLevel <= 0) {
            suicide();
        }
    }

    public boolean clearEnergonChanged() {
        if (energonChanged) {
            energonChanged = false;
            return true;
        } else {
            return false;
        }
    }

    public InternalRobot[] robotsOnBoard() {
        return passengers.toArray(new InternalRobot[0]);
    }

    public int spaceAvailable() {
        int space = TRANSPORT_CAPACITY;
        for (InternalRobot r : passengers) {
            space -= r.getChassis().weight;
        }
        return space;
    }

    public double getMaxEnergon() {
        return chassis.maxHp + platings * PLATING_HP_BONUS;
    }

    public void setDirection(Direction dir) {
        myDirection = dir;
    }

    public void suicide() {
        (new DeathSignal(this)).accept(myGameWorld);
    }

    public void enqueueIncomingMessage(Message msg) {
        incomingMessageQueue.add(msg);
    }

    public Message dequeueIncomingMessage() {
        if (incomingMessageQueue.size() > 0) {
            return incomingMessageQueue.remove(0);
        } else {
            return null;
        }
        // ~ return incomingMessageQueue.poll();
    }

    public Message[] dequeueIncomingMessages() {
        Message[] result = incomingMessageQueue.toArray(new Message[incomingMessageQueue.size()]);
        incomingMessageQueue.clear();
        return result;
    }

    public GameMap.MapMemory getMapMemory() {
        return mapMemory;
    }

    public void saveMapMemory(MapLocation oldLoc, MapLocation newLoc,
            boolean fringeOnly) {
        for (BaseComponent c : components.get(ComponentClass.SENSOR)) {
            saveMapMemory(newLoc, c.type());
        }
    }

    public void saveMapMemory(MapLocation newLoc, ComponentType t) {
        if (t != ComponentType.BUG) {
            int[][] myOffsets = offsets.get(t)[myDirection.ordinal()];
            mapMemory.rememberLocations(newLoc, myOffsets[0], myOffsets[1]);
        }
    }

    public void addPassenger(InternalRobot passenger) {
        passengers.add(passenger);
    }

    public boolean hasPassenger(InternalRobot passenger) {
        return passengers.contains(passenger);
    }

    public void removePassenger(InternalRobot passenger) {
        passengers.remove(passenger);
    }

    public void loadOnto(InternalRobot transporter) {
        this.transporter = transporter;
        myGameWorld.notifyMovingObject(this, myLocation, null);
        myLocation = VERY_FAR_AWAY;
    }

    public void unloadTo(MapLocation loc) {
        myLocation = loc;
        transporter = null;
        myGameWorld.notifyMovingObject(this, null, myLocation);
    }

    public void setControlBits(long l) {
        controlBits = l;
    }

    public long getControlBits() {
        return controlBits;
    }

    public void setBytecodesUsed(int numBytecodes) {
        bytecodesUsed = numBytecodes;
    }

    public int getBytecodesUsed() {
        return bytecodesUsed;
    }

    public int getBytecodeLimit() {
        if ((!on) || inTransport()) {
            return 0;
        }
        return BYTECODE_LIMIT_BASE + BYTECODE_LIMIT_ADDON * cores;
    }

    public boolean hasBeenAttacked() {
        return hasBeenAttacked;
    }

    public boolean hasComponentType(ComponentType type) {
        return components.hasComponent(type);
    }

    @Override
    public String toString() {
        return String.format("%s:%s#%d", getTeam(), chassis, getID());
    }

    public void freeMemory() {
        incomingMessageQueue = null;
        mapMemory = null;
        buffs = null;
        components = null;
        newComponents = null;
        passengers = null;
    }
}
