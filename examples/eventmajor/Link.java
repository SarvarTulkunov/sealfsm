package eventmajor;

/**
 * F27 FIXTURE — the transition table written <b>transposed</b>.
 *
 * <p>Every recognizer in the tool asks where the <em>state</em> is discriminated.
 * Here it is not discriminated anywhere: {@link LinkMultiplexer} switches over the
 * <b>frame type</b> — the input — and each arm installs the next state. The arm
 * labels are Σ, not Q. So no recognizer matched, the hierarchy was rejected as
 * "no transition producer found", and because the candidate channel keyed on the
 * same state-discrimination it reported <b>no states either</b>, though this
 * {@code permits} clause names them exactly.
 *
 * <p>This is not a synthetic shape. It was found by census over JDK 21 (14,723
 * files) and 145 library source jars (26,432 files), recorded in {@code CENSUS.md}:
 * Apache HttpClient 5's HTTP/2 multiplexer commits {@code connState} from inside
 * {@code switch (frameType)}, and its HTTP/1.1 duplexer commits the same field
 * from inside {@code switch (closeMode)}. Of the 909 closed-type state fields in
 * the two corpora, 17 are committed by a Σ-major dispatch directly and 8 one call
 * deep, against 9 committed by a state-major one.
 *
 * <p>The correct answer is a Tier 3 <b>candidate</b> and never a machine: a
 * Σ-major arm establishes no source state, so a relation built from one would be
 * sourced entirely at {@code <unknown>}, and the tiers exist so that a gap in
 * attribution is not dressed as a result. 4 states, no relation claimed, 2 sites
 * named.
 */
public sealed interface Link permits Ready, Active, Draining, Closed { }
