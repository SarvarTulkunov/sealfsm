package bindingframes;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import bindingframes.Latch.Open;
import bindingframes.Latch.Shut;
import bindingframes.Latch.Stuck;

/**
 * NEGATIVE D — a parameter read that may execute AFTER the call returns. The
 * binding describes the parameter during the call; a body stored for later is not
 * guaranteed to run inside it, so the binding is not carried across.
 *
 * <ul>
 *   <li>{@code Open}: the read is inside a lambda stored in a list and invoked
 *       through a library interface — UNRESOLVED.</li>
 *   <li>{@code Shut}: the read is inside a LOCAL CLASS whose instance is kept.
 *       Its method IS in the model and is folded, so this is the case the
 *       boundary rule has to catch explicitly: the read belongs to
 *       {@code Holder.read}, the parameter to {@code held} — UNRESOLVED.</li>
 *   <li>{@code Stuck}: the CONTROL. The same parameter read, written directly in
 *       the callee's own body, resolves.</li>
 * </ul>
 */
public final class LatchDriver {

    static final List<Supplier<Latch>> PENDING = new ArrayList<>();
    static final List<Object> KEPT = new ArrayList<>();

    public static Latch next(Latch current, Cmd c) {
        return switch (current) {
            case Open o -> later(new Shut());
            case Shut s -> held(new Stuck());
            case Stuck s -> now(new Open());
        };
    }

    private static Latch later(Latch p) {
        Supplier<Latch> deferred = () -> p;
        PENDING.add(deferred);
        return deferred.get();
    }

    private static Latch held(Latch p) {
        final class Holder {
            Latch read() {
                return p;
            }
        }
        Holder h = new Holder();
        KEPT.add(h);
        return h.read();
    }

    private static Latch now(Latch p) {
        return p;
    }
}
