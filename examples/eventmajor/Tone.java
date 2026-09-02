package eventmajor;

/**
 * NEGATIVE CONTROL for the closed-alphabet requirement. {@link ToneBoard} is the
 * positive fixture in every respect — it holds a {@code Tone} field, and two arms
 * install a {@code Tone} — except that it discriminates an {@code int} code rather
 * than an enum. Must yield <b>no machine and no candidate</b>.
 *
 * <p>The cost of this requirement is real and is stated rather than hidden: an
 * int-coded or String-keyed event alphabet is not reached. It buys the far larger
 * population the census measured — {@code switch (int opcode)} is the shape of
 * every parser, bytecode reader and option decoder in Java, and admitting those
 * would leave the candidate channel distinguishing nothing.
 */
public sealed interface Tone permits Soft, Loud { }
