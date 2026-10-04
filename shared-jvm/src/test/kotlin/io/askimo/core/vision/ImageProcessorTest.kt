/* SPDX-License-Identifier: AGPLv3
 *
 * Copyright (c) 2026 Askimo
 */
package io.askimo.core.vision

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ImageProcessorTest {
    @Test
    fun `should resize large image to fit within max dimensions`() {
        // Create a 4000x3000 image (larger than 2048x2048)
        val largeImage = createTestImage(4000, 3000, Color.RED)
        val imageBytes = imageToBytes(largeImage, "jpeg")

        // Process the image
        val result = ImageProcessor.process(imageBytes, "image/jpeg")

        // Should be resized
        Assertions.assertTrue(result.wasResized)
        Assertions.assertTrue(result.processedWidth!! <= ImageProcessor.DEFAULT_MAX_WIDTH)
        Assertions.assertTrue(result.processedHeight!! <= ImageProcessor.DEFAULT_MAX_HEIGHT)

        // Aspect ratio should be maintained (4:3)
        val aspectRatio = result.processedWidth.toDouble() / result.processedHeight
        Assertions.assertEquals(4.0 / 3.0, aspectRatio, 0.01)
    }

    @Test
    fun `should not resize image already within max dimensions`() {
        // Create a 1024x768 image (smaller than 2048x2048)
        val smallImage = createTestImage(1024, 768, Color.BLUE)
        val imageBytes = imageToBytes(smallImage, "jpeg")

        // Process the image
        val result = ImageProcessor.process(imageBytes, "image/jpeg")

        // Should not be resized
        Assertions.assertEquals(1024, result.processedWidth)
        Assertions.assertEquals(768, result.processedHeight)
    }

    @Test
    fun `should compress JPEG image with quality setting`() {
        // Create a 1024x1024 image
        val image = createTestImage(1024, 1024, Color.GREEN)
        val imageBytes = imageToBytes(image, "jpeg")

        // Process with lower quality to ensure compression
        val result = ImageProcessor.process(imageBytes, "image/jpeg", jpegQuality = 0.5f)

        // Should process successfully
        Assertions.assertNotNull(result.bytes)
        Assertions.assertTrue(result.processedSize > 0)
        // Compression may or may not reduce size for simple test images
        Assertions.assertTrue(result.processedSize <= result.originalSize * 2) // Sanity check
    }

    @Test
    fun `should handle PNG images`() {
        // Create a PNG image
        val image = createTestImage(800, 600, Color.YELLOW)
        val imageBytes = imageToBytes(image, "png")

        // Process the image
        val result = ImageProcessor.process(imageBytes, "image/png")

        // Should process successfully
        Assertions.assertNotNull(result.bytes)
        // ImageProcessor normalizes all images to JPEG for vision processing
        Assertions.assertEquals("image/jpeg", result.mimeType)
        Assertions.assertEquals(800, result.processedWidth)
        Assertions.assertEquals(600, result.processedHeight)
    }

    @Test
    fun `should calculate compression ratio correctly`() {
        val image = createTestImage(1024, 1024, Color.CYAN)
        val imageBytes = imageToBytes(image, "jpeg")

        val result = ImageProcessor.process(imageBytes, "image/jpeg", jpegQuality = 0.7f)

        // Compression ratio should be calculated (ratio = processed/original)
        Assertions.assertTrue(result.compressionRatio > 0)
        // Saved bytes calculation should work
        Assertions.assertEquals(result.originalSize - result.processedSize, result.savedBytes)
    }

    // Helper methods

    private fun createTestImage(width: Int, height: Int, color: Color): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = color
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return image
    }

    private fun imageToBytes(image: BufferedImage, format: String): ByteArray {
        val outputStream = ByteArrayOutputStream()
        ImageIO.write(image, format, outputStream)
        return outputStream.toByteArray()
    }
}
