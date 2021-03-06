package battlecode.world.signal;

import battlecode.common.ComponentType;
import battlecode.world.InternalRobot;
import battlecode.engine.signal.Signal;

public class EquipSignal extends Signal {

	public final int robotID;
	public final int builderID;
	public final ComponentType component;

	public EquipSignal(InternalRobot robot, InternalRobot builder, ComponentType component) {
		robotID = robot.getID();
		if(builder!=null)
			builderID = builder.getID();
		else
			builderID = 0;
		this.component = component;
	}
}
