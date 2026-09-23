package enumbodies;

/**
 * Transitions written INSIDE the enum constants' bodies, plus an enum-level
 * method that only some constants override. Each body is an anonymous class
 * ({@code Turn$1}) whose only instance is its constant, so its source state is
 * the constant. The enum-level {@code next()} runs only in {@code CENTER} — the
 * one constant that does not override it — so its self-loop belongs to
 * {@code CENTER} and to no other constant.
 */
public sealed interface Knob permits Rest, Turn {
    Knob next();
}
