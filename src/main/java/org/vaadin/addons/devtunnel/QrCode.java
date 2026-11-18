package org.vaadin.addons.devtunnel;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

final class QrCode {

    private QrCode() {
    }

    /** The text as a QR code: an SVG image in a data URL. */
    static String svgDataUrl(String text) {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, Map.of(EncodeHintType.MARGIN, 2));
        } catch (WriterException e) {
            throw new IllegalArgumentException("Cannot encode " + text + " as a QR code", e);
        }
        StringBuilder path = new StringBuilder();
        for (int y = 0; y < matrix.getHeight(); y++) {
            for (int x = 0; x < matrix.getWidth(); x++) {
                if (matrix.get(x, y)) {
                    path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
        }
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 " + matrix.getWidth() + " "
                + matrix.getHeight() + "' shape-rendering='crispEdges'><rect width='100%' height='100%' fill='#fff'/>"
                + "<path fill='#000' d='" + path + "'/></svg>";
        return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
    }
}
