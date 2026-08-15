package examples.turnstile;

/** Input alphabet as a sealed type. */
public sealed interface Event permits Coin, Push {}
