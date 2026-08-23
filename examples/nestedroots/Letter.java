package nestedroots;

public record Letter(String text) implements Contents {
    @Override public double postageGrams() {
        return 3.0;
    }
}
