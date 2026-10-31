package org.example.com.sRExpansion;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.ArrayList;
import java.util.List;

/**
 * 文本格式化：把配置里的颜色写法解析成 Adventure 组件。
 *
 * 支持：
 * <ul>
 *   <li>{@code &a}、{@code &l} 等传统颜色/格式代码（{@code &x} 十六进制写法也支持）</li>
 *   <li>{@code {#RRGGBB}} —— 单色</li>
 *   <li>{@code {#RRGGBB>}文字{#RRGGBB<}} —— 渐变，从 {@code >} 的颜色渐变到 {@code <} 的颜色</li>
 *   <li>{@code {#RRGGBB<>}} —— 渐变中间的色标，可以出现多次，用于多段渐变</li>
 * </ul>
 *
 * 例：{@code {#FF0000>}Mi{#FFA500<>}ne{#0000FF<}} 会让 "Mi" 从红渐到橙，"ne" 从橙渐到蓝。
 */
public final class TextFormatter {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('\u00a7')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /** 传统颜色代码 -> 颜色。 */
    private static final char[] LEGACY_CODES = "0123456789abcdef".toCharArray();
    private static final NamedTextColor[] LEGACY_COLORS = {
            NamedTextColor.BLACK, NamedTextColor.DARK_BLUE, NamedTextColor.DARK_GREEN, NamedTextColor.DARK_AQUA,
            NamedTextColor.DARK_RED, NamedTextColor.DARK_PURPLE, NamedTextColor.GOLD, NamedTextColor.GRAY,
            NamedTextColor.DARK_GRAY, NamedTextColor.BLUE, NamedTextColor.GREEN, NamedTextColor.AQUA,
            NamedTextColor.RED, NamedTextColor.LIGHT_PURPLE, NamedTextColor.YELLOW, NamedTextColor.WHITE
    };

    private TextFormatter() {}

    /** 解析成组件；无法识别的标记按普通文本处理。 */
    public static Component parse(String input) {
        if (input == null || input.isEmpty()) return Component.empty();
        Painter painter = new Painter();
        int i = 0;
        final int length = input.length();
        while (i < length) {
            char c = input.charAt(i);

            // & 传统代码
            if (c == '&' && i + 1 < length) {
                if (applyLegacy(painter, input, i)) {
                    i += 2;
                    continue;
                }
            }

            // {#RRGGBB...} 颜色标记
            if (c == '{' && i + 2 < length && input.charAt(i + 1) == '#') {
                int close = input.indexOf('}', i);
                if (close > i) {
                    String body = input.substring(i + 2, close);
                    boolean intermediate = body.endsWith("<>");
                    boolean start = !intermediate && body.endsWith(">");
                    boolean end = !intermediate && body.endsWith("<");
                    TextColor color = parseHex(body.replace(">", "").replace("<", ""));
                    if (color != null) {
                        if (start) {
                            i = emitGradient(input, close + 1, color, painter);
                            continue;
                        }
                        if (intermediate || end) {
                            // 孤立的色标：忽略
                            i = close + 1;
                            continue;
                        }
                        painter.setColor(color);
                        i = close + 1;
                        continue;
                    }
                }
            }

            painter.append(c);
            i++;
        }
        painter.flush();
        return Component.empty().children(painter.parts);
    }

    /** 解析后序列化成 {@code §} 传统字符串（含 {@code §x§R§R§G§G§B§B} 十六进制）。 */
    public static String toLegacy(String input) {
        return LEGACY.serialize(parse(input));
    }

    /** 去掉所有颜色/格式，只保留文字。 */
    public static String strip(String input) {
        return PLAIN.serialize(parse(input));
    }

    /** 把 {@code #RRGGBB} 解析成颜色。 */
    public static TextColor parseHex(String hex) {
        if (hex == null) return null;
        String value = hex.startsWith("#") ? hex.substring(1) : hex;
        if (value.length() != 6) return null;
        try {
            return TextColor.color(Integer.parseInt(value, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean applyLegacy(Painter painter, String input, int index) {
        char code = Character.toLowerCase(input.charAt(index + 1));
        for (int i = 0; i < LEGACY_CODES.length; i++) {
            if (LEGACY_CODES[i] == code) {
                painter.setColor(LEGACY_COLORS[i]);
                return true;
            }
        }
        switch (code) {
            case 'l': painter.setBold(true); return true;
            case 'o': painter.setItalic(true); return true;
            case 'n': painter.setUnderlined(true); return true;
            case 'm': painter.setStrikethrough(true); return true;
            case 'k': painter.setObfuscated(true); return true;
            case 'r': painter.reset(); return true;
            default: return false;
        }
    }

    /**
     * 从渐变起点开始一直读到 {@code {#RRGGBB<}}，把中间的字符按色标插值上色。
     *
     * @return 结束标记之后的下标
     */
    private static int emitGradient(String input, int from, TextColor startColor, Painter painter) {
        List<int[]> stops = new ArrayList<>();          // {可见字符位置, rgb}
        stops.add(new int[]{0, startColor.value()});
        List<Object> tokens = new ArrayList<>();        // Character 或 String（& 代码）
        int visible = 0;
        int i = from;
        boolean closed = false;
        final int length = input.length();

        while (i < length) {
            char c = input.charAt(i);
            if (c == '{' && i + 2 < length && input.charAt(i + 1) == '#') {
                int close = input.indexOf('}', i);
                if (close > i) {
                    String body = input.substring(i + 2, close);
                    boolean intermediate = body.endsWith("<>");
                    boolean end = !intermediate && body.endsWith("<");
                    boolean start = !intermediate && body.endsWith(">");
                    TextColor color = parseHex(body.replace(">", "").replace("<", ""));
                    if (color != null && (intermediate || end || start)) {
                        stops.add(new int[]{visible, color.value()});
                        i = close + 1;
                        if (end) { closed = true; break; }
                        continue;
                    }
                }
            }
            if (c == '&' && i + 1 < length) {
                tokens.add("&" + input.charAt(i + 1));
                i += 2;
                continue;
            }
            tokens.add(c);
            visible++;
            i++;
        }

        if (!closed) {
            // 没有结束标记：整段用起始色，避免把后面的内容一起吃掉
            stops.clear();
            stops.add(new int[]{0, startColor.value()});
            stops.add(new int[]{Math.max(visible - 1, 0), startColor.value()});
        }

        int position = 0;
        for (Object token : tokens) {
            if (token instanceof Character ch) {
                // 渐变里逐字上色，但不清除 &l / &o 等格式（否则 "&lM&la&lx" 这种写法会失效）
                painter.setGradientColor(interpolate(stops, position));
                painter.append(ch);
                position++;
            } else {
                String code = (String) token;
                // 渐变内部的格式代码照常生效，不占可见位置
                applyLegacy(painter, code, 0);
            }
        }
        return i;
    }

    /** 在色标之间按可见字符位置线性插值。 */
    private static TextColor interpolate(List<int[]> stops, int position) {
        if (stops.size() == 1) return TextColor.color(stops.get(0)[1]);
        int last = stops.size() - 1;
        if (position <= stops.get(0)[0]) return TextColor.color(stops.get(0)[1]);
        if (position >= stops.get(last)[0]) return TextColor.color(stops.get(last)[1]);
        for (int i = 0; i < last; i++) {
            int[] a = stops.get(i);
            int[] b = stops.get(i + 1);
            if (position >= a[0] && position <= b[0]) {
                int span = b[0] - a[0];
                double ratio = span <= 0 ? 0 : (double) (position - a[0]) / span;
                return TextColor.color(lerp(a[1], b[1], ratio));
            }
        }
        return TextColor.color(stops.get(last)[1]);
    }

    private static int lerp(int from, int to, double ratio) {
        int r1 = (from >> 16) & 0xFF, g1 = (from >> 8) & 0xFF, b1 = from & 0xFF;
        int r2 = (to >> 16) & 0xFF, g2 = (to >> 8) & 0xFF, b2 = to & 0xFF;
        int r = (int) Math.round(r1 + (r2 - r1) * ratio);
        int g = (int) Math.round(g1 + (g2 - g1) * ratio);
        int b = (int) Math.round(b1 + (b2 - b1) * ratio);
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }

    /** 累积同一样式的字符，样式变化时切成一个组件。 */
    private static final class Painter {
        final List<Component> parts = new ArrayList<>();
        final StringBuilder buffer = new StringBuilder();
        TextColor color;
        boolean bold, italic, underlined, strikethrough, obfuscated;

        void append(char c) {
            buffer.append(c);
        }

        void setColor(TextColor value) {
            flush();
            color = value;
            // 传统语义：颜色代码会清掉此前的格式
            bold = italic = underlined = strikethrough = obfuscated = false;
        }

        /** 渐变逐字上色时使用：只改颜色，保留已有的粗体/斜体等格式。 */
        void setGradientColor(TextColor value) {
            flush();
            color = value;
        }

        void setBold(boolean value) { flush(); bold = value; }
        void setItalic(boolean value) { flush(); italic = value; }
        void setUnderlined(boolean value) { flush(); underlined = value; }
        void setStrikethrough(boolean value) { flush(); strikethrough = value; }
        void setObfuscated(boolean value) { flush(); obfuscated = value; }

        void reset() {
            flush();
            color = null;
            bold = italic = underlined = strikethrough = obfuscated = false;
        }

        void flush() {
            if (buffer.length() == 0) return;
            Component part = Component.text(buffer.toString());
            if (color != null) part = part.color(color);
            if (bold) part = part.decorate(TextDecoration.BOLD);
            if (italic) part = part.decorate(TextDecoration.ITALIC);
            if (underlined) part = part.decorate(TextDecoration.UNDERLINED);
            if (strikethrough) part = part.decorate(TextDecoration.STRIKETHROUGH);
            if (obfuscated) part = part.decorate(TextDecoration.OBFUSCATED);
            parts.add(part);
            buffer.setLength(0);
        }
    }
}
