package io.sealfsm.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Optional opt-in marker. Placing {@code @Fsm} on a sealed type forces the
 * classifier to treat that hierarchy as a state machine, bypassing the
 * structural heuristics. Useful (a) as an escape hatch for FSMs whose shape the
 * heuristics do not recognise, and (b) to pin ground truth when building the
 * evaluation corpus.
 *
 * <p>The classifier matches on the annotation's <em>simple name</em>, so an
 * analysed project may declare its own equivalently named marker without
 * depending on this artifact. Accepted simple names: {@code Fsm}, {@code FSM},
 * {@code StateMachine}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Fsm {
    /** Optional explicit initial-state simple type name; "" means auto-detect. */
    String initial() default "";
}
