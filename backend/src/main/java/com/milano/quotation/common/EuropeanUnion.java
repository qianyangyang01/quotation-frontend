package com.milano.quotation.common;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** Same member list as the finance UI and quotation calculator. */
public final class EuropeanUnion {
    public static final String TAX_GROUP = "欧盟";
    private static final Map<String, String> CODES = load();
    private EuropeanUnion() {}

    private static Map<String, String> load() {
        try (var source = EuropeanUnion.class.getResourceAsStream("/eu-member-states.json")) {
            if (source == null) throw new IllegalStateException("Missing EU member list");
            var result = new HashMap<String, String>();
            for (var country : new JsonMapper().readTree(source)) {
                var code = country.get(0).asText();
                for (var alias : country) result.put(alias.asText().toUpperCase(Locale.ROOT), code);
            }
            return Map.copyOf(result);
        } catch (IOException error) { throw new IllegalStateException("Cannot load EU member list", error); }
    }

    public static boolean contains(String country) { return CODES.containsKey(country.trim().toUpperCase(Locale.ROOT)); }
    public static boolean sameCountry(String left, String right) {
        return left.trim().equalsIgnoreCase(right.trim()) || (contains(left)
                && CODES.get(left.trim().toUpperCase(Locale.ROOT)).equals(CODES.get(right.trim().toUpperCase(Locale.ROOT))));
    }
}
