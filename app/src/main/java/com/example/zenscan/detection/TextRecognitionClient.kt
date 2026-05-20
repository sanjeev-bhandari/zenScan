package com.example.zenscan.detection

import android.graphics.Bitmap
import android.graphics.Rect
import com.example.zenscan.model.TextBlockBounds
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class TextRecognitionClient {

    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun recognizeText(bitmap: Bitmap, onSuccess: (List<TextBlockBounds>) -> Unit, onFailure: (Exception) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val blocks = visionText.textBlocks.flatMap { block ->
                    block.lines.flatMap { line ->
                        line.elements.map { element ->
                            TextBlockBounds(
                                text = element.text,
                                boundingBox = element.boundingBox ?: Rect(0, 0, 0, 0)
                            )
                        }
                    }
                }
                onSuccess(blocks)
            }
            .addOnFailureListener { e ->
                onFailure(e)
            }
    }

    fun close() {
        recognizer.close()
    }
}
