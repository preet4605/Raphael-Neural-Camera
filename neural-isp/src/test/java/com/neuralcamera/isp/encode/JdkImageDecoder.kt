package com.neuralcamera.isp.encode

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Independent JPEG decoder for host unit tests: the JDK's ImageIO, reached by reflection.
 *
 * Android unit tests compile against android.jar, which has no javax.imageio or java.awt, but they run on a
 * full JDK where both exist. Reflection keeps those types out of the compile classpath.
 */
class JdkDecodedImage private constructor(private val image: Any) {
    val width: Int = bufferedImage.getMethod("getWidth").invoke(image) as Int
    val height: Int = bufferedImage.getMethod("getHeight").invoke(image) as Int
    private val raster: Any = bufferedImage.getMethod("getRaster").invoke(image)
    private val getSample = rasterClass.getMethod("getSample", Int::class.java, Int::class.java, Int::class.java)
    private val getRgb = bufferedImage.getMethod("getRGB", Int::class.java, Int::class.java)

    /** Raw raster sample of band [band], without any colour-space conversion. */
    fun sample(x: Int, y: Int, band: Int): Int = getSample.invoke(raster, x, y, band) as Int

    /** Packed 0xAARRGGBB in sRGB, as BufferedImage.getRGB returns it. */
    fun rgb(x: Int, y: Int): Int = getRgb.invoke(image, x, y) as Int

    companion object {
        private val imageIo = Class.forName("javax.imageio.ImageIO")
        private val bufferedImage = Class.forName("java.awt.image.BufferedImage")
        private val rasterClass = Class.forName("java.awt.image.Raster")

        /** Returns null when no installed ImageIO reader recognises the bytes. */
        fun read(bytes: ByteArray): JdkDecodedImage? =
            imageIo.getMethod("read", InputStream::class.java)
                .invoke(null, ByteArrayInputStream(bytes))
                ?.let { JdkDecodedImage(it) }
    }
}
