// kilocode_change - new file
package ai.kilocode.client.session.ui.empty

import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.Icon

/**
 * Brand logo rendered from a high-resolution bitmap instead of an SVG icon.
 *
 * The SVG pipeline re-rasterizes the logo through the icon cache, which can leave
 * visible stair-stepping when the cached raster is repainted at another scale. This
 * icon draws the 128px master PNG once per paint with bilinear interpolation at the
 * current scale, so the result stays smooth on every DPI setting.
 */
internal class BrandLogoIcon private constructor(
    private val logicalSize: Int,
    private val image: Image?,
) : Icon {
    override fun getIconWidth(): Int = JBUI.scale(logicalSize)

    override fun getIconHeight(): Int = JBUI.scale(logicalSize)

    override fun paintIcon(c: Component, g: Graphics, x: Int, y: Int) {
        val img = image ?: return
        val g2 = g.create() as? Graphics2D ?: return run { g.drawImage(img, x, y, iconWidth, iconHeight, null) }
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g2.drawImage(img, x, y, iconWidth, iconHeight, null)
        } finally {
            g2.dispose()
        }
    }

    companion object {
        private const val LOGO_PATH = "/icons/kilo-content.png"

        /** Logo size in logical pixels used by the empty-session panel. */
        const val PANEL_SIZE = 64

        fun at(logicalSize: Int): BrandLogoIcon = BrandLogoIcon(logicalSize, loadImage())

        private fun loadImage(): Image? {
            val image = runCatching {
                BrandLogoIcon::class.java.getResourceAsStream(LOGO_PATH)?.use { stream ->
                    ImageIO.read(stream)
                }
            }.getOrNull() ?: return null
            return image.takeIf { it.width > 0 && it.height > 0 }
        }
    }
}
