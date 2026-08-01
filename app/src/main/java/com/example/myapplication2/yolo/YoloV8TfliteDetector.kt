package com.example.myapplication2.yolo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * 負責把相機畫面交給 YOLO 模型，並把模型結果整理成 App 看得懂的物件清單。
 *
 * 這個類別會重複使用暫存空間，所以同一時間只能讓一個背景工作使用它。
 */
class YoloV8TfliteDetector(
    private val context: Context,
    private val modelAssetName: String = "yolov8n_float16.tflite",
    private val modelType: ModelType = ModelType.COCO,
    private val confThreshold: Float = DEFAULT_CONF_THRESHOLD,
    private val inputSize: Int = 640,
    private val maxCandidatesBeforeNms: Int = DEFAULT_MAX_CANDIDATES_BEFORE_NMS,
) : AutoCloseable {
    private val interpreter: Interpreter
    private val isNhwc: Boolean
    private val inH: Int
    private val inW: Int
    private val d0: Int
    private val d1: Int
    private val inputBuffer: ByteBuffer
    private val outputBuffer: ByteBuffer
    private val outputFloats: FloatArray
    private var inputPixels: IntArray
    private val letterboxBitmap: Bitmap
    private val letterboxCanvas: android.graphics.Canvas

    init {
        val opts = Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        }
        interpreter = Interpreter(loadMappedFile(context, modelAssetName), opts)

        val inShape = interpreter.getInputTensor(0).shape()
        require(inShape.size == 4 && inShape[0] == 1) { "Unexpected input shape: ${inShape.contentToString()}" }
        isNhwc = inShape[3] == 3
        inH = if (isNhwc) inShape[1] else inShape[2]
        inW = if (isNhwc) inShape[2] else inShape[3]

        val outShape = interpreter.getOutputTensor(0).shape()
        require(outShape.size == 3 && outShape[0] == 1) { "Unexpected output shape: ${outShape.contentToString()}" }
        d0 = outShape[1]
        d1 = outShape[2]

        inputBuffer = ByteBuffer.allocateDirect(4 * 3 * inW * inH).order(ByteOrder.nativeOrder())
        outputFloats = FloatArray(d0 * d1)
        outputBuffer = ByteBuffer.allocateDirect(4 * outputFloats.size).order(ByteOrder.nativeOrder())
        inputPixels = IntArray(inW * inH)
        letterboxBitmap = Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888)
        letterboxCanvas = android.graphics.Canvas(letterboxBitmap)
    }

    fun detect(image: ImageProxy): List<Detection> {
        // 先把照片轉正，之後找到的方框才會和畫面方向一致。
        val rotated = imageProxyToRotatedBitmap(image)
        return try {
            detect(rotated)
        } finally {
            rotated.recycle()
        }
    }

    fun detect(rotatedBitmap: Bitmap): List<Detection> {
        // 模型只吃固定大小的圖片。這裡會等比例縮放並補黑邊，等等再把位置換回原圖。
        val letterbox = letterbox(rotatedBitmap, inW, inH)
        bitmapToInputBuffer(letterbox.bitmap, isNhwc)
        outputBuffer.rewind()

        interpreter.run(inputBuffer, outputBuffer)

        outputBuffer.rewind()
        outputBuffer.asFloatBuffer().get(outputFloats)

        val (numBoxes, numChannels, get) = if (d0 <= 200 && d1 >= 1000) {
            // [84, 8400]
            val channels = d0
            val boxes = d1
            Triple(boxes, channels) { c: Int, i: Int -> outputFloats[c * boxes + i] }
        } else {
            // [8400, 84]
            val boxes = d0
            val channels = d1
            Triple(boxes, channels) { c: Int, i: Int -> outputFloats[i * channels + c] }
        }

        val candidates = outputToCandidates(
            numBoxes = numBoxes,
            numChannels = numChannels,
            get = get,
            letterbox = letterbox,
            rotatedW = rotatedBitmap.width,
            rotatedH = rotatedBitmap.height
        )

        val preNms = if (candidates.size > maxCandidatesBeforeNms) {
            candidates.sortedByDescending { it.score }.take(maxCandidatesBeforeNms)
        } else {
            candidates
        }
        val picked = nms(preNms, DEFAULT_IOU_THRESHOLD)
        return picked.map { cand ->
            val className = classNameFor(cand.classId)
            val (group, labelZh) = mapToUiLabel(className)
            Detection(
                box = cand.box,
                score = cand.score,
                classId = cand.classId,
                className = className,
                group = group,
                labelZh = labelZh
            )
        }
    }

    override fun close() {
        interpreter.close()
        letterboxBitmap.recycle()
    }

    /** 模型暫時找到的方框；還沒有把重複的結果刪掉。 */
    private data class Candidate(
        val box: RectF,
        val score: Float,
        val classId: Int
    )

    /** 記下圖片縮放了多少、四周補了多少黑邊，方便把方框放回原來的位置。 */
    private data class LetterboxResult(
        val bitmap: Bitmap,
        val scale: Float,
        val padX: Float,
        val padY: Float,
        val inW: Int,
        val inH: Int,
        val outW: Int,
        val outH: Int
    ) {
        fun mapRectToOriginal(r: RectF): RectF {
            // 先扣掉黑邊，再依縮放比例換回原本照片的位置。
            val left = (r.left - padX) / scale
            val top = (r.top - padY) / scale
            val right = (r.right - padX) / scale
            val bottom = (r.bottom - padY) / scale
            return RectF(left, top, right, bottom)
        }
    }

    /** 等比例縮放 [src] 至模型輸入大小，並以黑邊補足剩餘區域。 */
    private fun letterbox(src: Bitmap, dstW: Int, dstH: Int): LetterboxResult {
        val inW = src.width
        val inH = src.height
        val scale = min(dstW.toFloat() / inW.toFloat(), dstH.toFloat() / inH.toFloat())
        val newW = (inW * scale).toInt()
        val newH = (inH * scale).toInt()
        val padX = (dstW - newW) / 2f
        val padY = (dstH - newH) / 2f

        // 不把圖片硬拉寬或拉高，空白的地方補黑色，才不會讓物件變形。
        letterboxCanvas.drawColor(android.graphics.Color.BLACK)
        letterboxCanvas.drawBitmap(
            src,
            null,
            android.graphics.RectF(padX, padY, padX + newW, padY + newH),
            null
        )
        return LetterboxResult(letterboxBitmap, scale, padX, padY, inW, inH, dstW, dstH)
    }

    /** 將 ARGB 像素正規化為 0～1 的浮點 RGB，依模型排列寫入輸入 buffer。 */
    private fun bitmapToInputBuffer(bmp: Bitmap, isNhwc: Boolean) {
        val w = bmp.width
        val h = bmp.height
        val count = w * h
        if (inputPixels.size < count) {
            inputPixels = IntArray(count)
        }
        bmp.getPixels(inputPixels, 0, w, 0, 0, w, h)

        val out = inputBuffer
        // 每次都從暫存區開頭寫，避免混到上一張圖片的資料。
        out.rewind()
        if (isNhwc) {
            // TensorFlow Lite YOLO models are commonly NHWC.
            for (i in 0 until count) {
                val p = inputPixels[i]
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                out.putFloat(r)
                out.putFloat(g)
                out.putFloat(b)
            }
        } else {
            // Fallback for NCHW exported models.
            for (i in 0 until count) {
                val p = inputPixels[i]
                val r = ((p shr 16) and 0xFF) / 255f
                out.putFloat(r)
            }
            for (i in 0 until count) {
                val p = inputPixels[i]
                val g = ((p shr 8) and 0xFF) / 255f
                out.putFloat(g)
            }
            for (i in 0 until count) {
                val p = inputPixels[i]
                val b = (p and 0xFF) / 255f
                out.putFloat(b)
            }
        }
        out.rewind()
    }

    /**
     * 將模型輸出的像素或比例座標還原至原始影像，最後轉為供 UI 使用的 0～1 方框。
     */
    private fun normalizeBox(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        letterbox: LetterboxResult,
        rotatedW: Int,
        rotatedH: Int
    ): RectF {
        // Some exports output boxes in input-pixel space, some in normalized [0,1].
        // We auto-detect and convert both.
        val looksNormalized = x1 in -0.1f..1.1f && y1 in -0.1f..1.1f && x2 in -0.1f..1.1f && y2 in -0.1f..1.1f
        val lx1 = if (looksNormalized) x1 * letterbox.outW else x1
        val ly1 = if (looksNormalized) y1 * letterbox.outH else y1
        val lx2 = if (looksNormalized) x2 * letterbox.outW else x2
        val ly2 = if (looksNormalized) y2 * letterbox.outH else y2
        val mapped = letterbox.mapRectToOriginal(RectF(lx1, ly1, lx2, ly2))
        // 夾在 0～1 範圍內，避免模型輸出的框略超出邊界時讓 Canvas 繪製異常。
        return RectF(
            (mapped.left / rotatedW).coerceIn(0f, 1f),
            (mapped.top / rotatedH).coerceIn(0f, 1f),
            (mapped.right / rotatedW).coerceIn(0f, 1f),
            (mapped.bottom / rotatedH).coerceIn(0f, 1f)
        )
    }

    /** 解碼 YOLOv8 格式 `[x, y, w, h, classes...]` 的輸出。 */
    private fun decodeCandidates(
        numBoxes: Int,
        numClasses: Int,
        get: (Int, Int) -> Float,
        letterbox: LetterboxResult,
        rotatedW: Int,
        rotatedH: Int
    ): List<Candidate> {
        val candidates = ArrayList<Candidate>(256)
        for (i in 0 until numBoxes) {
            val cx = get(0, i)
            val cy = get(1, i)
            val w = get(2, i)
            val h = get(3, i)

            var bestClass = -1
            var bestScore = 0f
            // YOLOv8 的每個候選框只保留最高分的類別，減少後續 NMS 的候選數量。
            for (c in 0 until numClasses) {
                val rawScore = get(4 + c, i)
                val s = if (rawScore < 0f || rawScore > 1f) sigmoid(rawScore) else rawScore
                if (s > bestScore) {
                    bestScore = s
                    bestClass = c
                }
            }
            if (bestScore < confThreshold) continue
            val x1 = cx - w / 2f
            val y1 = cy - h / 2f
            val x2 = cx + w / 2f
            val y2 = cy + h / 2f
            val norm = normalizeBox(x1, y1, x2, y2, letterbox, rotatedW, rotatedH)
            candidates.add(Candidate(norm, bestScore, bestClass))
        }
        return candidates
    }

    /** 解碼 YOLOv5 類格式 `[x, y, w, h, objectness, classes...]` 的輸出。 */
    private fun decodeCandidatesWithObj(
        numBoxes: Int,
        numClasses: Int,
        get: (Int, Int) -> Float,
        letterbox: LetterboxResult,
        rotatedW: Int,
        rotatedH: Int
    ): List<Candidate> {
        val candidates = ArrayList<Candidate>(256)
        for (i in 0 until numBoxes) {
            val cx = get(0, i)
            val cy = get(1, i)
            val w = get(2, i)
            val h = get(3, i)
            val rawObj = get(4, i)
            // YOLOv5 類輸出額外含 objectness，最終分數是 objectness × class score。
            val obj = if (rawObj < 0f || rawObj > 1f) sigmoid(rawObj) else rawObj

            var bestClass = -1
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val rawScore = get(5 + c, i)
                val cls = if (rawScore < 0f || rawScore > 1f) sigmoid(rawScore) else rawScore
                val s = obj * cls
                if (s > bestScore) {
                    bestScore = s
                    bestClass = c
                }
            }
            if (bestScore < confThreshold) continue
            val x1 = cx - w / 2f
            val y1 = cy - h / 2f
            val x2 = cx + w / 2f
            val y2 = cy + h / 2f
            val norm = normalizeBox(x1, y1, x2, y2, letterbox, rotatedW, rotatedH)
            candidates.add(Candidate(norm, bestScore, bestClass))
        }
        return candidates
    }

    /** 根據輸出 channel 數判斷模型格式，並轉換為候選偵測框。 */
    private fun outputToCandidates(
        numBoxes: Int,
        numChannels: Int,
        get: (Int, Int) -> Float,
        letterbox: LetterboxResult,
        rotatedW: Int,
        rotatedH: Int
    ): List<Candidate> {    
        // Support both:
        // - YOLOv8 style: [x,y,w,h,80cls] => 84 channels
        // - YOLOv5 style: [x,y,w,h,obj,80cls] => 85 channels
        return if (numChannels >= 85) {
            val numClasses = numChannels - 5
            decodeCandidatesWithObj(numBoxes, numClasses, get, letterbox, rotatedW, rotatedH)
        } else {
            val numClasses = numChannels - 4
            decodeCandidates(numBoxes, numClasses, get, letterbox, rotatedW, rotatedH)
        }
    }

    /** 同一個東西常會被模型框好幾次；這裡只留下分數最高的一個框。 */
    private fun nms(cands: List<Candidate>, iouThreshold: Float): List<Candidate> {
        // 只有同類型而且幾乎疊在一起的框才會刪掉，不影響不同類型的物件。
        val sorted = cands.sortedByDescending { it.score }
        val picked = ArrayList<Candidate>(sorted.size)
        val removed = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (removed[i]) continue
            val a = sorted[i]
            picked.add(a)
            for (j in i + 1 until sorted.size) {
                if (removed[j]) continue
                val b = sorted[j]
                if (a.classId != b.classId) continue
                if (iou(a.box, b.box) > iouThreshold) removed[j] = true
            }
        }
        return picked
    }

    /** 算兩個方框重疊得有多像；數字越大代表越接近。 */
    private fun iou(a: RectF, b: RectF): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interW = max(0f, interRight - interLeft)
        val interH = max(0f, interBottom - interTop)
        val interArea = interW * interH
        val areaA = max(0f, a.width()) * max(0f, a.height())
        val areaB = max(0f, b.width()) * max(0f, b.height())
        val union = areaA + areaB - interArea
        return if (union <= 0f) 0f else interArea / union
    }

    /** 把模型輸出的原始數字換成 0 到 1 的可信度。 */
    private fun sigmoid(x: Float): Float = (1f / (1f + exp(-x)))

    /** 依目前模型類型把類別索引轉為標準英文名稱。 */
    private fun classNameFor(classId: Int): String {
        val classes = when (modelType) {
            ModelType.COCO -> COCO80
            ModelType.TRAFFIC -> TRAFFIC6
        }
        return classes.getOrElse(classId) { "cls_$classId" }.lowercase()
    }

    /** 將模型英文類別映射為 UI 類群組與中文顯示名稱。 */
    private fun mapToUiLabel(className: String): Pair<Detection.Group, String> {
        return when (modelType) {
            ModelType.COCO -> {
                when (className) {
                    "person" -> Detection.Group.PERSON to "人"
                    "car", "bus", "truck", "motorcycle", "bicycle" -> Detection.Group.VEHICLE to "車"
                    else -> Detection.Group.OBSTACLE to "障礙物"
                }
            }

            ModelType.TRAFFIC -> {
                when (className) {
                    "red", "red_light", "redlight", "traffic_red", "red signal", "紅燈" ->
                        Detection.Group.TRAFFIC to "紅燈"
                    "yellow", "yellow_light", "yellowlight", "traffic_yellow", "yellow signal", "黃燈" ->
                        Detection.Group.TRAFFIC to "黃燈"
                    "green", "green_light", "greenlight", "traffic_green", "green signal", "綠燈" ->
                        Detection.Group.TRAFFIC to "綠燈"
                    "downstair", "downstairs", "down_stair", "down_stairs", "下樓梯" ->
                        Detection.Group.TRAFFIC to "下樓梯"
                    "upstair", "upstairs", "up_stair", "up_stairs", "上樓梯" ->
                        Detection.Group.TRAFFIC to "上樓梯"
                    "crosswalk", "sidewalk", "pedestrian_walkway", "人行道" ->
                        Detection.Group.TRAFFIC to "人行道"
                    else -> Detection.Group.TRAFFIC to className
                }
            }
        }
    }

    /** 從 App 內的 assets 讀取模型，直接交給模型工具使用，避免多複製一份到記憶體。 */
    private fun loadMappedFile(context: Context, assetName: String): ByteBuffer {
        // 使用 memory-mapped file 避免把整個模型複製到 Java heap，降低大型模型的記憶體壓力。
        context.assets.openFd(assetName).use { afd ->
            FileInputStream(afd.fileDescriptor).use { fis ->
                val channel = fis.channel
                return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            }
        }
    }

    companion object {
        private const val DEFAULT_CONF_THRESHOLD = 0.25f
        private const val DEFAULT_IOU_THRESHOLD = 0.45f
        private const val DEFAULT_MAX_CANDIDATES_BEFORE_NMS = 150

        private val COCO80 = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair",
            "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush"
        )

        // 須與訓練時 data.yaml 的 names 順序一致：下樓梯、上樓梯、綠燈、紅燈、黃燈、人行道
        private val TRAFFIC6 = listOf(
            "down_stairs",
            "up_stairs",
            "green_light",
            "red_light",
            "yellow_light",
            "crosswalk"
        )

        fun imageProxyToRotatedBitmap(image: ImageProxy): Bitmap {
            // 分析器要求 RGBA_8888；仍保留 stride 處理以相容不同裝置的 buffer 排列。
            val plane = image.planes[0]
            val buffer = plane.buffer
            buffer.rewind()

            val w = image.width
            val h = image.height
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            if (pixelStride == 4 && rowStride == w * 4) {
                out.copyPixelsFromBuffer(buffer)
            } else {
                val rgba = ByteArray(rowStride * h)
                buffer.get(rgba)
                val argb = IntArray(w * h)
                var dst = 0
                for (y in 0 until h) {
                    var src = y * rowStride
                    for (x in 0 until w) {
                        val r = rgba[src].toInt() and 0xFF
                        val g = rgba[src + 1].toInt() and 0xFF
                        val b = rgba[src + 2].toInt() and 0xFF
                        val a = rgba[src + 3].toInt() and 0xFF
                        argb[dst++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        src += pixelStride
                    }
                }
                out.setPixels(argb, 0, w, 0, 0, w, h)
            }

            val degrees = image.imageInfo.rotationDegrees
            if (degrees == 0) return out
            val m = android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(out, 0, 0, out.width, out.height, m, true)
            out.recycle()
            return rotated
        }
    }

    /** 可支援的模型標籤集，用於選擇類別名稱及 UI 映射規則。 */
    enum class ModelType {
        COCO,
        TRAFFIC
    }
}

