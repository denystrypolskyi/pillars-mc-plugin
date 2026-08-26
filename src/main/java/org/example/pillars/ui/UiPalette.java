package org.example.pillars.ui;

public final class UiPalette {
    public static final String BRAND = "§6";
    public static final String PRIMARY = "§e";
    public static final String TEXT = "§f";
    public static final String MUTED = "§7";
    public static final String SEPARATOR = "§8";
    public static final String SUCCESS = "§a";
    public static final String DANGER = "§c";
    public static final String INFO = "§b";
    public static final String BOLD = "§l";

    // Semantic styles: change these to restyle every interface consistently.
    public static final String TITLE = BRAND + BOLD;
    public static final String SECTION = BRAND + BOLD;
    public static final String LABEL = MUTED;
    public static final String VALUE = TEXT;
    public static final String ACCENT = BRAND;

    private UiPalette() {
    }
}
