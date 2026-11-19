package com.example.model

enum class DriverProtocol(
    val displayName: String,
    val shortCode: String,
    val supportsColor: Boolean,
    val supportsRawPdf: Boolean
) {
    AUTO_DETECTED("Auto-Detected", "AUTO", true, false),
    IPP_USB_PWG_RASTER("IPP-USB PWG Raster (RaS2)", "IPP-PWG", true, false),
    IPP_USB_PDF("IPP-USB PDF-Direct", "IPP-PDF", true, true),
    PCL_5C_COLOR("PCL 5c (24-bit Color)", "PCL5c", true, false),
    PCL_5E_MONO("PCL 5e (Monochrome)", "PCL5e", false, false),
    PCL_6_XL("PCL 6 (PCL-XL)", "PCL6", true, false),
    PDF_DIRECT("PDF-Direct (Raw PJL)", "PDF", true, true),
    POSTSCRIPT("PostScript Level 2/3", "PS", true, false),
    SAMSUNG_QPDL_0X11("Samsung QPDL (0x11 Algo0x11)", "QPDL-11", false, false),
    SAMSUNG_QPDL_0X13_JBIG1("Samsung QPDL (0x13 JBIG1)", "QPDL-13", false, false),
    SAMSUNG_QPDL_0X15_COLOR("Samsung QPDL (0x15 Color)", "QPDL-15", true, false);

    companion object {
        val manualSelectableEntries: List<DriverProtocol> = entries.toList()
    }
}

enum class ColorOverrideMode(val displayName: String) {
    AUTO("Auto"),
    COLOR("Color"),
    MONOCHROME("Monochrome")
}

enum class OutputMode(val displayName: String) {
    AUTO("Auto"),
    COLOR("Color"),
    MONO("Mono")
}

enum class DitherMode(val displayName: String) {
    HALFTONE_FLOYD_STEINBERG("Halftone"),
    CRISP_TEXT("Crisp Text"),
    SOFT_TONE_BAYER_4X4("Soft Tone")
}

enum class PaperSize(
    val displayName: String,
    val widthMm: Float,
    val heightMm: Float,
    val qpdlCode: Int,
    val pclCode: Int,
    val pwgMediaName: String
) {
    A4("A4", 210f, 297f, 2, 26, "iso_a4_210x297mm"),
    LETTER("Letter", 215.9f, 279.4f, 0, 2, "na_letter_8.5x11in"),
    LEGAL("Legal", 215.9f, 355.6f, 1, 3, "na_legal_8.5x14in"),
    A5("A5", 148f, 210f, 16, 25, "iso_a5_148x210mm")
}

enum class PageOrientation(val displayName: String) {
    AUTO("Auto"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape")
}

enum class ScaleMode(val displayName: String) {
    SCALE_TO_FIT("Scale to Fit"),
    CROP_TO_FILL("Crop to Fill"),
    STRETCH("Stretch")
}

enum class PrintQuality(val displayName: String, val dpiX: Int, val dpiY: Int) {
    TONER_SAVE("Toner Save", 300, 300),
    STANDARD_600("600 DPI", 600, 600),
    HIGH_1200X600("1200×600", 1200, 600)
}

enum class PageRangeMode(val displayName: String) {
    ALL("All"),
    CURRENT("Current"),
    ODD("Odd"),
    EVEN("Even"),
    CUSTOM("Custom")
}

data class NormalizedCropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    val isFull: Boolean
        get() = left <= 0.001f && top <= 0.001f && right >= 0.999f && bottom >= 0.999f

    fun normalized(): NormalizedCropRect {
        val l = left.coerceIn(0f, 0.95f)
        val t = top.coerceIn(0f, 0.95f)
        val r = right.coerceIn(l + 0.05f, 1f)
        val b = bottom.coerceIn(t + 0.05f, 1f)
        return NormalizedCropRect(l, t, r, b)
    }
}

data class PrintTuningOptions(
    val outputMode: OutputMode = OutputMode.AUTO,
    val ditherMode: DitherMode = DitherMode.HALFTONE_FLOYD_STEINBERG,
    val brightness: Float = 0f, // -0.5f .. +0.5f
    val contrast: Float = 1f,   // 0.5f .. 2.0f
    val rotationDegrees: Int = 0, // 0, 90, 180, 270
    val cropRect: NormalizedCropRect = NormalizedCropRect()
)

data class PdfPrintOptions(
    val pageRangeMode: PageRangeMode = PageRangeMode.ALL,
    val customRangeText: String = "",
    val currentPageIndex: Int = 0,
    val copies: Int = 1,
    val paperSize: PaperSize = PaperSize.A4,
    val orientation: PageOrientation = PageOrientation.AUTO,
    val scaleMode: ScaleMode = ScaleMode.SCALE_TO_FIT,
    val quality: PrintQuality = PrintQuality.STANDARD_600
)

enum class ImagesPerPageOption(val displayName: String, val fixedCount: Int?) {
    ONE("1", 1),
    TWO("2", 2),
    FOUR("4 (2×2)", 4),
    SIX("6", 6),
    EIGHT("8", 8),
    NINE("9 (3×3)", 9),
    AUTO_GRID("Auto Grid", null)
}

enum class PhysicalPhotoSizePreset(
    val displayName: String,
    val widthMm: Float?,
    val heightMm: Float?
) {
    AUTO_FIT_CELL("Auto (Fit Cell)", null, null),
    ID_25X35("25×35 mm (ID)", 25f, 35f),
    PASSPORT_35X45("35×45 mm (Passport)", 35f, 45f),
    WALLET_5X7_CM("5×7 cm (Wallet)", 50f, 70f),
    PHOTO_10X15_CM("10×15 cm (4×6\")", 100f, 150f),
    PHOTO_13X18_CM("13×18 cm (5×7\")", 130f, 180f),
    PHOTO_15X20_CM("15×20 cm (6×8\")", 150f, 200f),
    FULL_20X30_CM("20×30 cm (Full)", 200f, 300f)
}

enum class GapPreset(val displayName: String, val mm: Float) {
    NONE("No gaps (0 mm)", 0f),
    COMPACT("Compact (2.5 mm)", 2.5f),
    NORMAL("Normal (6 mm)", 6f),
    WIDE("Wide (12 mm)", 12f)
}

enum class MarginPreset(val displayName: String, val mm: Float) {
    NONE("None (0 mm)", 0f),
    NARROW("Narrow (5 mm)", 5f),
    NORMAL("Normal (10 mm)", 10f),
    WIDE("Wide (20 mm)", 20f)
}

enum class AnchorPosition(val displayName: String, val row: Int, val col: Int) {
    TOP_LEFT("Top-Left", 0, 0),
    TOP_CENTER("Top-Center", 0, 1),
    TOP_RIGHT("Top-Right", 0, 2),
    CENTER_LEFT("Center-Left", 1, 0),
    CENTER("Center", 1, 1),
    CENTER_RIGHT("Center-Right", 1, 2),
    BOTTOM_LEFT("Bottom-Left", 2, 0),
    BOTTOM_CENTER("Bottom-Center", 2, 1),
    BOTTOM_RIGHT("Bottom-Right", 2, 2)
}

enum class CutGuideStyle(val displayName: String) {
    NONE("None"),
    THIN_FRAME("Thin Frame"),
    CUT_MARKS("Cut Marks")
}

data class PhotoLayoutOptions(
    val imagesPerPage: ImagesPerPageOption = ImagesPerPageOption.ONE,
    val physicalSizePreset: PhysicalPhotoSizePreset = PhysicalPhotoSizePreset.AUTO_FIT_CELL,
    val gapPreset: GapPreset = GapPreset.NORMAL,
    val marginPreset: MarginPreset = MarginPreset.NORMAL,
    val anchorPosition: AnchorPosition = AnchorPosition.CENTER,
    val cutGuideStyle: CutGuideStyle = CutGuideStyle.NONE,
    val repeatSinglePhoto: Boolean = false,
    val autoRotateToCell: Boolean = true,
    val paperSize: PaperSize = PaperSize.A4,
    val orientation: PageOrientation = PageOrientation.AUTO,
    val quality: PrintQuality = PrintQuality.STANDARD_600,
    val copies: Int = 1
)

data class PortStatusFlags(
    val rawByte: Int,
    val paperEmpty: Boolean,
    val selected: Boolean,
    val noError: Boolean
)

data class Ieee1284Info(
    val rawDeviceId: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val commandSetTokens: List<String> = emptyList(),
    val deviceClass: String = "",
    val portStatus: PortStatusFlags? = null
)

data class IppCapabilities(
    val makeAndModel: String = "",
    val colorSupported: Boolean? = null,
    val documentFormatsSupported: List<String> = emptyList(),
    val defaultResolutionDpi: Int? = null,
    val mediaSupported: List<String> = emptyList()
)

data class UsbDeviceInfo(
    val deviceId: Int,
    val vendorId: Int,
    val productId: Int,
    val deviceName: String,
    val productName: String?,
    val manufacturerName: String?,
    val hasIppInterface: Boolean,
    val hasBulkInterface: Boolean,
    val activeInterfaceProtocol: Int
) {
    val vidPidKey: String
        get() = "%04X:%04X".format(vendorId, productId)
}

data class ResolvedPrinterProfile(
    val makeAndModel: String,
    val effectiveProtocol: DriverProtocol,
    val effectiveColorSupported: Boolean,
    val isBestEffortFallback: Boolean,
    val overrideActive: Boolean,
    val activeInterfaceLabel: String,
    val resolutionReason: String,
    val unverifiedLanguageWarning: String? = null
)

sealed interface UsbPrinterState {
    data object Disconnected : UsbPrinterState
    data class PermissionRequired(val deviceInfo: UsbDeviceInfo) : UsbPrinterState
    data class Probing(val deviceInfo: UsbDeviceInfo) : UsbPrinterState
    data class Ready(
        val deviceInfo: UsbDeviceInfo,
        val ieee1284: Ieee1284Info,
        val ipp: IppCapabilities?,
        val profile: ResolvedPrinterProfile
    ) : UsbPrinterState
    data class Error(val message: String, val deviceInfo: UsbDeviceInfo? = null) : UsbPrinterState
}

enum class PrintJobStatus(val displayName: String) {
    COMPLETED("Completed"),
    CANCELLED("Cancelled"),
    FAILED("Failed")
}

data class PrintJobProgress(
    val isPrinting: Boolean = false,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val currentCopy: Int = 0,
    val totalCopies: Int = 0,
    val currentBand: Int = 0,
    val totalBands: Int = 0,
    val bytesSent: Long = 0L,
    val phaseLabel: String = ""
)
