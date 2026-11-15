package com.shay.waypoint.data

/** Bundled food database: name (serving), kcal, protein g. Approximate values per serving. */
object Foods {
    data class F(val name: String, val kcal: Double, val protein: Double)

    private fun f(n: String, k: Int, p: Double) = F(n, k.toDouble(), p)

    val all: List<F> = listOf(
        // Breakfast
        f("Poha (1 plate)", 250, 5.0), f("Upma (1 bowl)", 250, 6.0), f("Idli (1 pc)", 58, 2.0),
        f("Plain dosa (1)", 168, 4.0), f("Masala dosa (1)", 387, 7.0), f("Uttapam (1)", 200, 5.0),
        f("Medu vada (1 pc)", 97, 3.0), f("Sambar (1 bowl)", 130, 6.0), f("Coconut chutney (2 tbsp)", 70, 1.0),
        f("Aloo paratha (1)", 290, 6.0), f("Plain paratha (1)", 180, 4.0), f("Thepla (1)", 120, 3.0),
        f("Puri (1 pc)", 100, 1.5), f("Puri bhaji (2 puri + bhaji)", 400, 7.0), f("Misal pav (1 plate)", 450, 15.0),
        f("Sabudana khichdi (1 bowl)", 300, 2.0), f("Dhokla (4 pcs)", 160, 6.0), f("Oats with milk (1 bowl)", 230, 9.0),
        f("Cornflakes with milk (1 bowl)", 260, 9.0), f("Bread slice (1)", 70, 2.5), f("Bread butter (2 slices)", 210, 5.0),
        f("Peanut butter (1 tbsp)", 95, 4.0), f("Boiled egg (1)", 78, 6.0), f("Omelette (2 eggs)", 180, 12.0),
        f("Egg bhurji (2 eggs)", 200, 12.0), f("Sandwich, veg (1)", 250, 7.0),
        // Mains
        f("Roti / chapati (1)", 104, 3.0), f("Naan (1)", 260, 9.0), f("Steamed rice (1 cup)", 205, 4.0),
        f("Jeera rice (1 cup)", 250, 4.0), f("Pulao (1 cup)", 220, 5.0), f("Veg biryani (1 plate)", 350, 8.0),
        f("Chicken biryani (1 plate)", 480, 22.0), f("Dal (1 bowl)", 150, 9.0), f("Dal tadka (1 bowl)", 180, 9.0),
        f("Rajma (1 bowl)", 210, 11.0), f("Chole (1 bowl)", 230, 10.0), f("Kadhi (1 bowl)", 160, 6.0),
        f("Khichdi (1 bowl)", 250, 9.0), f("Mixed veg sabzi (1 bowl)", 120, 3.0), f("Aloo sabzi (1 bowl)", 180, 3.0),
        f("Bhindi fry (1 bowl)", 140, 3.0), f("Paneer butter masala (1 bowl)", 350, 14.0), f("Palak paneer (1 bowl)", 280, 14.0),
        f("Chicken curry (1 bowl)", 260, 24.0), f("Chicken xacuti (1 bowl)", 300, 24.0), f("Egg curry (2 eggs)", 240, 13.0),
        f("Fish curry (1 bowl)", 230, 22.0), f("Goan fish curry rice (1 plate)", 520, 25.0), f("Prawn curry (1 bowl)", 220, 20.0),
        f("Fish fry (1 pc)", 200, 18.0), f("Chicken breast, cooked (100 g)", 165, 31.0), f("Paneer (100 g)", 265, 18.0),
        f("Soya chunks, dry (50 g)", 173, 26.0), f("Sprouts (1 bowl)", 100, 7.0), f("Curd (1 bowl)", 92, 5.0),
        f("Raita (1 bowl)", 90, 4.0), f("Pav (1)", 140, 4.0), f("Pav bhaji (1 plate)", 400, 9.0),
        f("Fried rice (1 plate)", 400, 8.0), f("Hakka noodles (1 plate)", 420, 9.0), f("Instant noodles (1 pack)", 350, 8.0),
        f("Veg momos (6)", 250, 8.0), f("Chicken momos (6)", 290, 16.0), f("Pizza (1 slice)", 285, 12.0),
        f("Veg burger (1)", 350, 9.0),
        // Snacks
        f("Samosa (1)", 260, 4.0), f("Vada pav (1)", 290, 6.0), f("Pakora (4 pcs)", 200, 4.0),
        f("Glucose biscuits (4)", 110, 1.5), f("Chips (small pack)", 160, 2.0), f("Peanuts (30 g)", 170, 7.0),
        f("Almonds (10)", 70, 2.5), f("Roasted chana (30 g)", 110, 6.0), f("Makhana (1 cup)", 100, 3.0),
        f("Bhel puri (1 plate)", 250, 5.0), f("Pani puri (6)", 200, 3.0),
        // Fruit
        f("Banana (1)", 105, 1.3), f("Apple (1)", 95, 0.5), f("Mango (1)", 200, 2.8), f("Orange (1)", 62, 1.2),
        f("Papaya (1 cup)", 62, 0.7), f("Grapes (1 cup)", 104, 1.1), f("Watermelon (1 cup)", 46, 0.9),
        // Drinks
        f("Tea with milk + sugar (1 cup)", 80, 2.0), f("Coffee with milk (1 cup)", 90, 3.0), f("Milk (1 glass)", 150, 8.0),
        f("Sweet lassi (1 glass)", 220, 7.0), f("Buttermilk (1 glass)", 40, 2.0), f("Sol kadhi (1 glass)", 90, 1.0),
        f("Fresh lime soda (1 glass)", 80, 0.0), f("Cola (330 ml)", 140, 0.0), f("Whey protein (1 scoop)", 120, 24.0),
        // Sweets
        f("Gulab jamun (1)", 150, 2.0), f("Jalebi (2)", 150, 1.0), f("Rasgulla (1)", 125, 2.0), f("Ladoo (1)", 180, 3.0),
        f("Bebinca (1 slice)", 250, 4.0), f("Dark chocolate (20 g)", 110, 1.5), f("Ice cream (1 scoop)", 140, 2.5),
    )
}
