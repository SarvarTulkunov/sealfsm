package examples.factory;

/**
 * Σ = {Turn, Kick}. {@code Kick} exists so that {@code shutOnTurn}'s
 * {@code : current} branch is taken on a real input: with Σ = {Turn} alone,
 * {@code event instanceof Turn} holds for every input and the self-loop the
 * fixture pins would be reachable only by a null event (F38).
 */
public sealed interface Event permits Turn, Kick {}
