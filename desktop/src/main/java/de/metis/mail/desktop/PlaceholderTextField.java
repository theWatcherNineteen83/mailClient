package de.metis.mail.desktop;

import javax.swing.*;
import java.awt.*;

/** JTextField mit grauem Placeholder-Text, wenn leer und ohne Fokus. */
public final class PlaceholderTextField extends JTextField {

    private final String placeholderKey;

    public PlaceholderTextField(String placeholderKey) {
        this.placeholderKey = placeholderKey;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (getText().isEmpty() && !hasFocus()) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Color dis = UIManager.getColor("TextField.disabledForeground");
            g2.setColor(dis != null ? dis : new Color(0x9AA7B4));
            FontMetrics fm = g2.getFontMetrics();
            Insets in = getInsets();
            g2.drawString(I18n.tr(placeholderKey), in.left + 4,
                    (getHeight() + fm.getAscent()) / 2 - 1);
            g2.dispose();
        }
    }
}
