// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com

package com.mewcode.tui;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Spinner verb phrases displayed while the agent is working.
 * Ported from Go: internal/tui/verbs.go
 */
public final class SpinnerVerbs {

    private SpinnerVerbs() {}

    private static final List<String> VERBS = List.of(
            "Accomplishing",
            "Architecting",
            "Baking",
            "Beboppin'",
            "Befuddling",
            "Bloviating",
            "Boogieing",
            "Boondoggling",
            "Bootstrapping",
            "Brewing",
            "Calculating",
            "Canoodling",
            "Caramelizing",
            "Cascading",
            "Cerebrating",
            "Choreographing",
            "Churning",
            "Coalescing",
            "Cogitating",
            "Combobulating",
            "Composing",
            "Computing",
            "Concocting",
            "Considering",
            "Contemplating",
            "Cooking",
            "Crafting",
            "Creating",
            "Crunching",
            "Crystallizing",
            "Cultivating",
            "Deciphering",
            "Deliberating",
            "Dilly-dallying",
            "Discombobulating",
            "Doodling",
            "Elucidating",
            "Enchanting",
            "Envisioning",
            "Fermenting",
            "Finagling",
            "Flambéing",
            "Flibbertigibbeting",
            "Flummoxing",
            "Forging",
            "Frolicking",
            "Gallivanting",
            "Garnishing",
            "Generating",
            "Germinating",
            "Grooving",
            "Harmonizing",
            "Hatching",
            "Honking",
            "Hullaballooing",
            "Ideating",
            "Imagining",
            "Improvising",
            "Incubating",
            "Inferring",
            "Infusing",
            "Kneading",
            "Lollygagging",
            "Manifesting",
            "Marinating",
            "Meandering",
            "Metamorphosing",
            "Mewing",
            "Moonwalking",
            "Moseying",
            "Mulling",
            "Musing",
            "Noodling",
            "Orbiting",
            "Orchestrating",
            "Percolating",
            "Philosophising",
            "Pondering",
            "Pontificating",
            "Pouncing",
            "Purring",
            "Puzzling",
            "Razzle-dazzling",
            "Ruminating",
            "Scampering",
            "Simmering",
            "Sketching",
            "Spelunking",
            "Spinning",
            "Sprouting",
            "Synthesizing",
            "Thinking",
            "Tinkering",
            "Transfiguring",
            "Transmuting",
            "Undulating",
            "Unfurling",
            "Unravelling",
            "Vibing",
            "Wandering",
            "Whisking",
            "Working",
            "Wrangling",
            "Zigzagging"
    );

    /** Returns a random spinner verb (present-participle form). */
    public static String random() {
        return VERBS.get(ThreadLocalRandom.current().nextInt(VERBS.size()));
    }

    /** Returns an unmodifiable view of all spinner verbs. */
    public static List<String> all() {
        return VERBS;
    }

    /**
     * Converts a present-participle verb ("Thinking") to a simple past-tense
     * form ("Thought").  Handles a handful of irregular cases and falls back
     * to a mechanical strip-"ing" + "ed" rule for regular verbs.
     */
    public static String pastTense(String verb) {
        if (verb == null || verb.isEmpty()) {
            return verb;
        }

        // Irregular / special-case mappings
        return switch (verb) {
            case "Thinking"  -> "Thought";
            case "Brewing"   -> "Brewed";
            case "Mewing"    -> "Mewed";
            case "Beboppin'" -> "Bebopped";
            case "Dilly-dallying"  -> "Dilly-dallied";
            case "Razzle-dazzling" -> "Razzle-dazzled";
            case "Flibbertigibbeting" -> "Flibbertigibbeted";

            default -> convertRegular(verb);
        };
    }

    private static String convertRegular(String verb) {
        if (!verb.endsWith("ing")) {
            return verb + "ed";
        }

        String base = verb.substring(0, verb.length() - 3); // strip "ing"

        // Verbs whose base already ends with 'e' that was dropped before -ing:
        //   e.g. "Composing" -> base "Compos" -> restore 'e' -> "Composed"
        // Detect by checking if the stem looks like it had a silent-e
        // (consonant ending that needs an 'e' restored).
        // Simple heuristic: if base ends in a consonant cluster typical
        // of silent-e verbs, restore the 'e'.
        char last = base.charAt(base.length() - 1);

        if (isDoubledConsonant(base)) {
            // e.g. "Spinning" -> base "Spinn" -> "Spinned" (drop duplicate)
            return base.substring(0, base.length() - 1) + "ed";
        }

        if ("aeiouy".indexOf(last) >= 0) {
            // base ends in vowel: just add 'd'
            // e.g. "Cascading" -> base "Cascad" -- not vowel, skip
            // Actually "Ideating" -> base "Ideat" -- not vowel either
            // This handles: base ends in vowel like "Grooving" -> "Groov" -> no.
            // Let's just add "ed" in most cases.
            return base + "d";
        }

        // For stems that originally had a silent-e (e.g., "Composing" from "compose"):
        // common pattern is base ending in s, z, c, g, v, t, l, n, r + "ing"
        // We restore the 'e' and add 'd'.
        if ("szgvcln".indexOf(last) >= 0) {
            return base + "ed";
        }

        // Default: add "ed" to the base
        return base + "ed";
    }

    /** Checks whether the base ends with two identical consonants (doubled). */
    private static boolean isDoubledConsonant(String base) {
        if (base.length() < 2) return false;
        char c1 = base.charAt(base.length() - 1);
        char c2 = base.charAt(base.length() - 2);
        return c1 == c2 && "aeiouy".indexOf(c1) < 0;
    }
}
