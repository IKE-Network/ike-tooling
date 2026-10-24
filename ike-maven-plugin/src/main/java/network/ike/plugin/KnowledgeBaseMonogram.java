/*
 * Copyright © 2026 IKE Network (support@ike.network)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package network.ike.plugin;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;

/**
 * The icon of a knowledge base whose project keeps none: the initials of its display name on a
 * rounded square, in a colour from its artifactId, the same on every build and every machine.
 */
final class KnowledgeBaseMonogram {

    /** The icon's size in pixels; the {@code @2x} file is twice it. */
    static final int SIZE = 64;

    /** Words a knowledge base's name carries that say nothing about which one it is. */
    private static final List<String> GENERIC = List.of("kb", "knowledge", "base");

    private KnowledgeBaseMonogram() {
    }

    /**
     * Writes {@code icon.png} and {@code icon@2x.png} into a directory.
     *
     * @param directory   where to write them
     * @param displayName the name the initials come from
     * @param artifactId  the name the colour comes from
     * @return the {@code icon.png} written
     * @throws IOException if a file cannot be written
     */
    static Path write(Path directory, String displayName, String artifactId) throws IOException {
        // A build has no screen; drawing must not start a window system.
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
        Files.createDirectories(directory);
        Path icon = directory.resolve("icon.png");
        ImageIO.write(draw(displayName, artifactId, SIZE), "png", icon.toFile());
        ImageIO.write(draw(displayName, artifactId, SIZE * 2), "png", KnowledgeBaseDescriptor.doubleResolution(icon).toFile());
        return icon;
    }

    /**
     * Draws the monogram.
     *
     * @param displayName the name the initials come from
     * @param artifactId  the name the colour comes from
     * @param size        the width and height in pixels
     * @return the image
     */
    static BufferedImage draw(String displayName, String artifactId, int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setColor(color(artifactId));
            double inset = size / 32.0;
            graphics.fill(new RoundRectangle2D.Double(inset, inset, size - 2 * inset, size - 2 * inset,
                    size * 0.45, size * 0.45));
            String initials = initials(displayName);
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont((float) (size * (initials.length() > 2 ? 0.34 : 0.44)));
            GlyphVector glyphs = font.createGlyphVector(graphics.getFontRenderContext(), initials);
            Rectangle2D bounds = glyphs.getVisualBounds();
            graphics.setColor(Color.WHITE);
            graphics.drawGlyphVector(glyphs, (float) ((size - bounds.getWidth()) / 2 - bounds.getX()),
                    (float) ((size - bounds.getHeight()) / 2 - bounds.getY()));
        } finally {
            graphics.dispose();
        }
        return image;
    }

    /**
     * The initials of a name: the first letters of up to three of its words, or the first two letters
     * of a name of one word. Words are split at punctuation, spaces, and where a lower-case letter
     * meets an upper-case one; words such as {@code kb} or {@code Knowledge Base}, which every
     * knowledge base has, are left out.
     *
     * @param name the display name, such as {@code SOLOR GUDID}
     * @return the initials, such as {@code SG}; {@code ?} when the name has no letters or digits
     */
    static String initials(String name) {
        List<String> words = Arrays.stream(name.split("[^\\p{L}\\p{N}]+|(?<=\\p{Ll})(?=\\p{Lu})"))
                .filter(word -> !word.isEmpty()).toList();
        List<String> telling = words.stream().filter(word -> !GENERIC.contains(word.toLowerCase(Locale.ROOT))).toList();
        if (!telling.isEmpty()) {
            words = telling;
        }
        if (words.isEmpty()) {
            return "?";
        }
        if (words.size() == 1) {
            int[] letters = words.getFirst().codePoints().limit(2).toArray();
            return new String(letters, 0, 1).toUpperCase(Locale.ROOT)
                    + (letters.length > 1 ? new String(letters, 1, 1).toLowerCase(Locale.ROOT) : "");
        }
        StringBuilder initials = new StringBuilder();
        words.stream().limit(3).forEach(word -> initials.appendCodePoint(Character.toUpperCase(word.codePointAt(0))));
        return initials.toString();
    }

    /**
     * The monogram's colour: a hue from the CRC-32 of the lower-case artifactId, modulo 360, at a
     * saturation and brightness white initials read on.
     *
     * @param artifactId the knowledge base's artifactId
     * @return the colour
     */
    static Color color(String artifactId) {
        CRC32 checksum = new CRC32();
        checksum.update(artifactId.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        return Color.getHSBColor((checksum.getValue() % 360) / 360f, 0.6f, 0.62f);
    }
}
