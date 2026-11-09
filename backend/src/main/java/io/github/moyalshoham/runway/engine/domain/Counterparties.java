package io.github.moyalshoham.runway.engine.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Normalises counterparty names so that "Bezeq Ltd. #88123" and "BEZEQ LTD" group together. */
public final class Counterparties {

    private static final Pattern QUOTES = Pattern.compile("[\"'`׳״‘’“”]");
    private static final Pattern LONG_NUMBERS = Pattern.compile("\\d{3,}");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern LEGAL_SUFFIX = Pattern.compile("(?U)\\b(ltd|inc|llc|בעמ)\\b");

    private Counterparties() {}

    public static String key(String name) {
        String s = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        s = QUOTES.matcher(s).replaceAll("");
        s = LONG_NUMBERS.matcher(s).replaceAll(" ");
        s = NON_WORD.matcher(s).replaceAll(" ");
        s = LEGAL_SUFFIX.matcher(s).replaceAll(" ");
        return s.strip().replaceAll("\\s+", " ");
    }
}
