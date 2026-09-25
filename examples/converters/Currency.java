package converters;

/**
 * F36 CONVERTER-USE CONTROL, POLYMORPHIC spelling (thesis Decision 4): each member
 * overrides {@code Currency exchange()} and returns another member. That is the
 * shape {@code examples/traffic} is accepted on. Its only caller examines the
 * result and drops it, so it is an established conversion: rejected, with no
 * candidate.
 *
 * <p>This is the control that the rule holds at EVERY locus. A store-back
 * requirement enforced only at a switch would split one converter's verdict by its
 * spelling.
 */
public sealed interface Currency permits Currency.Usd, Currency.Eur {
    Currency exchange();

    record Usd(double amount) implements Currency {
        @Override
        public Currency exchange() {
            return new Eur(amount * 0.9);
        }
    }

    record Eur(double amount) implements Currency {
        @Override
        public Currency exchange() {
            return new Usd(amount / 0.9);
        }
    }
}

final class Ledger {
    static boolean quotedInEuro(Currency c) {
        return c.exchange() instanceof Currency.Eur;
    }
}
