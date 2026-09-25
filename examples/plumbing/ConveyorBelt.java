package plumbing;

/**
 * F36 driver (thesis Decision 4): stores {@link ConveyorMachine#next}'s successor
 * back as the current state. Without it the VALUE_RETURN half of this fixture is
 * a value no caller installs. It would then no longer be claimed as a transition,
 * and the fixture would stop pinning F10 at that commit form. The FIELD_MUTATION
 * half ({@code step}) installs in its own host and needs nothing. Unseeded on
 * purpose.
 */
final class ConveyorBelt {
    private final ConveyorMachine machine = new ConveyorMachine();
    private Conveyor conveyor;

    ConveyorBelt(Conveyor start) {
        this.conveyor = start;
    }

    void command(Command command) {
        conveyor = machine.next(conveyor, command);
    }
}
