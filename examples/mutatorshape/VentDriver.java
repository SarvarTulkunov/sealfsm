package mutatorshape;

/**
 * One arm commits through the mutator, one through the impostor. The first is a
 * fully attributed edge; the second must not become one.
 */
public final class VentDriver {

    public void drive(Vent current, VentRig rig) {
        switch (current) {
            case Sealed s -> rig.assume(new Venting());  // Sealed -> Venting
            case Venting v -> rig.restart(v);            // commits Sealed; the argument is not it
        }
    }
}
