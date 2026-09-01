package unreadablecallee;

/**
 * THE F11 CONTROL for the k = 1 commit-existence probe: <b>"I could not read it"
 * must never become "it commits".</b>
 *
 * <p>Same shape as {@code examples/voidcommit} — a stateful driver, an exhaustive
 * {@code switch} statement over the state, every arm a bare call whose value Java
 * discards — but the callee is a method whose <em>body the analysis cannot
 * read</em>: an interface method with no implementation in the source set,
 * reached through a field typed with that interface.
 *
 * <p>An unread body contains no field write for the same reason it contains
 * nothing at all: it was never parsed. Its emptiness is therefore evidence of
 * nothing, and the probe must answer <em>unknown</em> — which is not a proof of
 * commit. F11 is the finding that named this failure mode one level down, where
 * reading a reflective shadow's empty body as proof deleted every library call
 * returning the hierarchy type. Here the same mistake runs the other way and
 * would <em>fabricate</em> a machine rather than delete an edge, which is the
 * worse direction.
 *
 * <p>Must produce: <b>no machine</b>; one {@code Candidate} carrying the complete
 * three-state {@code permits} closure; and a diagnostic <em>naming the unreadable
 * callee</em>, so the report separates "I read it and it commits nothing"
 * ({@code examples/voidfold}) from "I could not read it" (here). Those are
 * different findings — the first is about the program, the second about the
 * invocation — and only the second is something a user can act on by widening
 * {@code --src}.
 *
 * <p>The callee is declared in the source set with no body, rather than being a
 * JDK call, on purpose. A reflective <em>shadow</em> is the ordinary out-of-model
 * case: every {@code Objects.equals}, every synthesised record accessor, and
 * every guard expression Spoon leaks into an arm body is one, and reporting them
 * all would make the diagnostic noise a reader learns to ignore. A declaration
 * the analysis <em>did</em> read, which simply has no body, is the case worth
 * naming — so the verdict is the same for both and the report is stratified.
 */
public sealed interface Shutter permits Open, Closing, Shut { }
