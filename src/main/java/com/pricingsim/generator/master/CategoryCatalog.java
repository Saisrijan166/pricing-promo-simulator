package com.pricingsim.generator.master;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.pricingsim.domain.enums.Category;

/**
 * Per-category generation parameters (PLAN.md §7.2 steps 1–2). Brand names are invented for this project and are not
 * meant to refer to any real company (PLAN.md §2 assumption 12).
 *
 * <p>Each margin band and markup band is chosen so that {@code markupLo × (1 − marginHi) > 1.07}: the initial floor
 * {@code landed / (1 − min_margin)} then sits at least 7% below MRP, which leaves room for the initial price. Supplier
 * cost shocks (+10–20%) can still push the floor above the current price or above MRP, which is the natural
 * guardrail conflict the plan asks for.
 */
public final class CategoryCatalog {

    /**
     * @param weight        share of SKUs (PLAN.md §7.2 step 1)
     * @param mrpMedian     median MRP in INR (lognormal)
     * @param mrpSigma      lognormal sigma of MRP
     * @param marginLo      lower bound of {@code min_margin_pct}
     * @param marginHi      upper bound of {@code min_margin_pct}
     * @param markupLo      lower bound of MRP / initial landed cost (PLAN.md: 1.4–2.6 overall)
     * @param markupHi      upper bound of MRP / initial landed cost
     * @param packShare     share of SKUs sold as multi-unit packs
     * @param demandFactor  relative daily demand (latent; never exported)
     * @param brands        fictional brands
     * @param subcategories subcategories, each with the name suffixes of its single-unit variants
     */
    public record Profile(
            double weight,
            double mrpMedian,
            double mrpSigma,
            double marginLo,
            double marginHi,
            double markupLo,
            double markupHi,
            double packShare,
            double demandFactor,
            List<String> brands,
            List<Subcategory> subcategories) {
    }

    /** A subcategory and the name suffixes of its single-unit variants (size, colour, edition). */
    public record Subcategory(String name, List<String> variants) {
    }

    private static final Map<Category, Profile> PROFILES = new EnumMap<>(Category.class);

    static {
        PROFILES.put(Category.GROCERY, new Profile(0.20, 180, 0.55, 0.10, 0.18, 1.40, 1.65, 0.25, 1.8,
                List.of("Harvestry", "Grainwell", "Pantrio", "Meadowmill"),
                List.of(
                        new Subcategory("Rice", List.of("1 kg", "5 kg", "10 kg")),
                        new Subcategory("Atta", List.of("1 kg", "5 kg", "10 kg")),
                        new Subcategory("Pulses", List.of("500 g", "1 kg")),
                        new Subcategory("Edible Oil", List.of("1 L", "2 L", "5 L")),
                        new Subcategory("Spices", List.of("50 g", "100 g", "200 g")),
                        new Subcategory("Snacks", List.of("150 g", "400 g")),
                        new Subcategory("Beverages", List.of("250 g", "500 g", "1 L")))));
        PROFILES.put(Category.PERSONAL_CARE, new Profile(0.12, 250, 0.50, 0.18, 0.30, 1.60, 2.00, 0.25, 1.4,
                List.of("Purelume", "Dewcraft", "Softlane", "Aquaveil"),
                List.of(
                        new Subcategory("Shampoo", List.of("180 ml", "340 ml", "650 ml")),
                        new Subcategory("Soap", List.of("75 g", "125 g")),
                        new Subcategory("Toothpaste", List.of("100 g", "150 g")),
                        new Subcategory("Deodorant", List.of("150 ml", "200 ml")),
                        new Subcategory("Body Lotion", List.of("200 ml", "400 ml")))));
        PROFILES.put(Category.BEAUTY, new Profile(0.08, 450, 0.60, 0.28, 0.45, 2.00, 2.50, 0.05, 0.9,
                List.of("Velvessa", "Rosique", "Glowmint", "Satinara"),
                List.of(
                        new Subcategory("Lipstick", List.of("Matte Red", "Nude", "Berry")),
                        new Subcategory("Face Wash", List.of("100 ml", "150 ml")),
                        new Subcategory("Serum", List.of("30 ml", "50 ml")),
                        new Subcategory("Kajal", List.of("Black", "Smudge Proof")),
                        new Subcategory("Sunscreen", List.of("SPF 30", "SPF 50")))));
        PROFILES.put(Category.HEALTH, new Profile(0.06, 350, 0.55, 0.15, 0.28, 1.55, 1.95, 0.10, 0.9,
                List.of("Vitaloom", "Carewell Labs", "Nutrivia", "Healix"),
                List.of(
                        new Subcategory("Multivitamin", List.of("30 Tablets", "60 Tablets")),
                        new Subcategory("Protein Powder", List.of("500 g", "1 kg")),
                        new Subcategory("Pain Relief Balm", List.of("25 g", "50 g")),
                        new Subcategory("Antiseptic", List.of("100 ml", "500 ml")),
                        new Subcategory("Thermometer", List.of("Digital", "Infrared")))));
        PROFILES.put(Category.HOME_KITCHEN, new Profile(0.14, 800, 0.65, 0.20, 0.35, 1.70, 2.20, 0.05, 0.7,
                List.of("Hearthly", "Cookora", "Nestwise", "Brightware"),
                List.of(
                        new Subcategory("Cookware", List.of("Kadai 24 cm", "Tawa 28 cm", "Set of 3")),
                        new Subcategory("Storage Jar", List.of("500 ml", "1 L", "Set of 6")),
                        new Subcategory("Bedsheet", List.of("Single", "Double", "King")),
                        new Subcategory("Cleaning Liquid", List.of("500 ml", "1 L", "2 L")),
                        new Subcategory("Water Bottle", List.of("750 ml", "1 L")))));
        PROFILES.put(Category.ELECTRONICS, new Profile(0.08, 2500, 0.75, 0.12, 0.22, 1.40, 1.70, 0.0, 0.4,
                List.of("Voltiq", "Sonaro", "Pixelan", "Circuitra"),
                List.of(
                        new Subcategory("Earbuds", List.of("Black", "White", "Pro")),
                        new Subcategory("Power Bank", List.of("10000 mAh", "20000 mAh")),
                        new Subcategory("Charger", List.of("20 W", "33 W", "65 W")),
                        new Subcategory("Smartwatch", List.of("Black", "Silver", "Lite")),
                        new Subcategory("Bluetooth Speaker", List.of("10 W", "20 W")))));
        PROFILES.put(Category.APPAREL, new Profile(0.12, 900, 0.55, 0.35, 0.55, 2.40, 2.60, 0.05, 0.9,
                List.of("Threadory", "Loomline", "Urban Weft", "Cottonfold"),
                List.of(
                        new Subcategory("T-Shirt", List.of("S", "M", "L", "XL")),
                        new Subcategory("Kurta", List.of("M", "L", "XL")),
                        new Subcategory("Jeans", List.of("30", "32", "34")),
                        new Subcategory("Socks", List.of("Free Size")),
                        new Subcategory("Jacket", List.of("M", "L", "XL")))));
        PROFILES.put(Category.TOYS, new Profile(0.06, 700, 0.60, 0.22, 0.38, 1.80, 2.30, 0.0, 0.7,
                List.of("Playnest", "Tinkerloo", "Bricklet", "Funvoy"),
                List.of(
                        new Subcategory("Building Blocks", List.of("100 Pieces", "250 Pieces")),
                        new Subcategory("Puzzle", List.of("500 Pieces", "1000 Pieces")),
                        new Subcategory("Soft Toy", List.of("Small", "Large")),
                        new Subcategory("RC Car", List.of("Junior", "Pro")),
                        new Subcategory("Board Game", List.of("Classic", "Family Edition")))));
        PROFILES.put(Category.SPORTS, new Profile(0.06, 1200, 0.65, 0.18, 0.32, 1.65, 2.10, 0.0, 0.6,
                List.of("Sprintora", "Peakfield", "Courtlane", "Trailhive"),
                List.of(
                        new Subcategory("Yoga Mat", List.of("4 mm", "6 mm")),
                        new Subcategory("Cricket Bat", List.of("Size 5", "Full Size")),
                        new Subcategory("Football", List.of("Size 4", "Size 5")),
                        new Subcategory("Dumbbell", List.of("2 kg", "5 kg", "10 kg")),
                        new Subcategory("Badminton Racket", List.of("Standard", "Pro")))));
        PROFILES.put(Category.STATIONERY, new Profile(0.08, 120, 0.50, 0.22, 0.38, 1.80, 2.30, 0.25, 1.3,
                List.of("Inkwise", "Paperlark", "Scribblo", "Notara"),
                List.of(
                        new Subcategory("Notebook", List.of("A4", "A5")),
                        new Subcategory("Gel Pen", List.of("Blue", "Black")),
                        new Subcategory("Marker Set", List.of("Set of 6", "Set of 12")),
                        new Subcategory("Geometry Box", List.of("Standard")),
                        new Subcategory("Sticky Notes", List.of("3 x 3 in", "Assorted")))));
        if (PROFILES.size() != Category.values().length) {
            throw new IllegalStateException("Every category needs a profile");
        }
    }

    /** Pack sizes for multi-unit packs. */
    public static final List<Integer> PACK_SIZES = List.of(2, 3, 4, 6, 12);

    private CategoryCatalog() {
    }

    public static Profile of(Category category) {
        return PROFILES.get(category);
    }

    /** Category weights in {@link Category} declaration order. */
    public static double[] weights() {
        Category[] categories = Category.values();
        double[] weights = new double[categories.length];
        for (int i = 0; i < categories.length; i++) {
            weights[i] = PROFILES.get(categories[i]).weight();
        }
        return weights;
    }
}
