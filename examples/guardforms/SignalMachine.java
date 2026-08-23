package guardforms;

import java.util.Objects;

/**
 * Twenty {@code when} clauses, one per spelling, over a fixed two-state machine.
 *
 * <p>Spoon 10.4.2 populates {@code CtCase.getGuard()} <em>only</em> when the guard
 * is a {@code CtBinaryOperator}. For every other shape it leaves the slot null and
 * prepends the guard expression into the arm body as a statement, so recovery has
 * to read it back out of the body — see {@code TransitionExtractor.leakedGuard}.
 * That split is invisible from the source, which is why the shapes are enumerated
 * here rather than assumed: of these twenty, only {@code Binary}, {@code Conj},
 * {@code Disj}, {@code Inst}, {@code Ternary}, {@code NegBin} and {@code Deep}
 * take the supported path; the other thirteen are recovered.
 *
 * <p>Three of them were found losing their guard only by enumerating this table:
 * <ul>
 *   <li>{@link Trigger.Boxed} — a {@code Boolean}-typed guard failed a check
 *       written against the primitive {@code boolean};</li>
 *   <li>{@link Trigger.Cast} — a cast is not its own node in Spoon, so
 *       {@code (Boolean) v.o()} reported the invocation's own type
 *       ({@code Object}) and was rejected;</li>
 *   <li>{@link Trigger.Sw} — recovered, but its source spans five lines, and the
 *       guard text reached the DOT label with the newlines intact. Graphviz
 *       accepts that, so it failed silently rather than loudly.</li>
 * </ul>
 *
 * <p>Every arm goes to the same target, so the machine is 2 states / 21 edges and
 * the fixture asserts on the LABELS, not on the shape of the automaton.
 */
public final class SignalMachine {

    static boolean positive(int n) {
        return n > 0;
    }

    public Signal step(Signal current, Trigger trigger) {
        return switch (current) {
            case Idle i -> switch (trigger) {
                case Trigger.Inv t     when t.flag()                        -> new Busy();
                case Trigger.Unary t   when !t.flag()                       -> new Busy();
                case Trigger.Binary t  when t.n() > 10                      -> new Busy();
                case Trigger.Conj t    when t.a() && t.n() > 1              -> new Busy();
                case Trigger.Disj t    when t.a() || t.b()                  -> new Busy();
                case Trigger.Boxed t   when t.flag()                        -> new Busy();
                case Trigger.Bound(boolean flag) when flag                  -> new Busy();
                case Trigger.Inst t    when t.payload() instanceof String    -> new Busy();
                case Trigger.Ternary t when (t.a() ? 1 : 0) == 1            -> new Busy();
                case Trigger.Arr t     when t.flags()[0]                    -> new Busy();
                case Trigger.NegBin t  when !(t.n() > 3)                    -> new Busy();
                case Trigger.Static t  when SignalMachine.positive(t.n())   -> new Busy();
                case Trigger.Chain t   when t.s().trim().toUpperCase().isEmpty() -> new Busy();
                case Trigger.Lib t     when Objects.equals(t.a(), t.b())    -> new Busy();
                case Trigger.Sw t      when switch (t.n()) {
                                               case 1 -> true;
                                               default -> false;
                                           }                               -> new Busy();
                case Trigger.Paren t   when ((t.flag()))                    -> new Busy();
                case Trigger.Deep t    when (t.a() || t.b()) && !(t.n() > 2 && t.n() < 9) -> new Busy();
                case Trigger.Lambda t  when t.xs().stream().anyMatch(String::isEmpty) -> new Busy();
                case Trigger.Cast t    when (Boolean) t.o()                 -> new Busy();
                case Trigger.Nest(Trigger.Inner(boolean on)) when on        -> new Busy();
                default -> i;
            };
            case Busy b -> new Idle();
        };
    }
}
