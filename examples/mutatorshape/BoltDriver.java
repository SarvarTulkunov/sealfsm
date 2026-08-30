package mutatorshape;

/**
 * Dispatches on the state and commits through {@link BoltRig}'s mutators. The
 * {@code Idle} arm deliberately calls the audit hook <em>beside</em> the real
 * commit: the two sit in one arm, so no difference of file, arm or context can
 * stand in for the difference the analysis actually has to make, which is what
 * each callee's body does with the value handed to it.
 */
public final class BoltDriver {

    public void drive(Bolt current, BoltRig rig) {
        switch (current) {
            case Idle i -> {
                rig.become(i);              // bookkeeping: commits nothing
                rig.assume(new Live());     // Idle -> Live
            }
            case Live l -> rig.assume(new Spent());   // Live -> Spent
            case Spent s -> rig.engage(new Idle());   // Spent -> Idle (laundered commit)
        }
    }
}
