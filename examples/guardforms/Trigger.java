package guardforms;

import java.util.List;

/**
 * One event member per guard shape, so each shape yields its own labelled edge
 * and a lost guard is attributable to exactly one spelling.
 */
public sealed interface Trigger {

    record Inv(boolean flag)                  implements Trigger {}  // bare invocation
    record Unary(boolean flag)                implements Trigger {}  // !x
    record Binary(int n)                      implements Trigger {}  // x > k
    record Conj(boolean a, int n)             implements Trigger {}  // &&
    record Disj(boolean a, boolean b)         implements Trigger {}  // ||
    record Boxed(Boolean flag)                implements Trigger {}  // java.lang.Boolean
    record Bound(boolean flag)                implements Trigger {}  // pattern-binding read
    record Inst(Object payload)               implements Trigger {}  // instanceof
    record Ternary(boolean a)                 implements Trigger {}  // (c ? x : y) == k
    record Arr(boolean[] flags)               implements Trigger {}  // array access
    record NegBin(int n)                      implements Trigger {}  // !(x > k)
    record Static(int n)                      implements Trigger {}  // static call
    record Chain(String s)                    implements Trigger {}  // call chain
    record Lib(String a, String b)            implements Trigger {}  // library static call
    record Sw(int n)                          implements Trigger {}  // switch-expression guard
    record Paren(boolean flag)                implements Trigger {}  // redundant parentheses
    record Deep(boolean a, boolean b, int n)  implements Trigger {}  // deep compound
    record Lambda(List<String> xs)            implements Trigger {}  // lambda inside the guard
    record Cast(Object o)                     implements Trigger {}  // (Boolean) cast
    record Nest(Inner in)                     implements Trigger {}  // nested record pattern

    /**
     * A payload, NOT an event: deliberately not a {@code Trigger}. Making it one
     * would put a permitted subtype in another permitted subtype's component list,
     * turning Σ into a recursive data type whose synthesised accessor reads like a
     * per-state transition method — and the classifier then reports the event
     * alphabet itself as a second state machine.
     */
    record Inner(boolean on) {}
}
