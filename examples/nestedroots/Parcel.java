package nestedroots;

public record Parcel(double kilograms) implements Contents {
    @Override public double postageGrams() {
        return kilograms * 1000.0;
    }
}
