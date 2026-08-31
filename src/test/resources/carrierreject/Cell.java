package carrierreject;

public sealed interface Cell permits Ready, Busy, Spent {
}
