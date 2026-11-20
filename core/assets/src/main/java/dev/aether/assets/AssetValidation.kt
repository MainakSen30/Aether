package dev.aether.assets

sealed interface AssetValidationError {
    val message: String

    data class InvalidAssetPath(val path: String) : AssetValidationError {
        override val message = "Invalid asset path: $path"
    }

    data class UnsupportedAssetType(val category: String, val extension: String) : AssetValidationError {
        override val message = "Unsupported asset type for $category: .$extension"
    }

    data class AssetTooLarge(val bytes: Long, val maxBytes: Long) : AssetValidationError {
        override val message = "Asset is too large: $bytes bytes (max $maxBytes)"
    }

    data object EmptyAsset : AssetValidationError {
        override val message = "Asset is empty"
    }

    data class SizeMismatch(val declaredBytes: Long, val actualBytes: Long) : AssetValidationError {
        override val message = "Declared size $declaredBytes does not match actual size $actualBytes"
    }

    data class InvalidFileSignature(val extension: String, val reason: String) : AssetValidationError {
        override val message = "Invalid .$extension file signature: $reason"
    }

    data class InvalidImageDimensions(val width: Int, val height: Int) : AssetValidationError {
        override val message = "Invalid image dimensions: ${width}x$height"
    }

    data class InvalidFont(val reason: String) : AssetValidationError {
        override val message = "Invalid font: $reason"
    }

    data object UnsafeSvg : AssetValidationError {
        override val message = "SVG assets are not accepted until a safe renderer/sanitizer exists"
    }

    data class ChecksumMismatch(val assetId: String) : AssetValidationError {
        override val message = "Checksum mismatch for $assetId"
    }

    data class AssetIntegrityFailure(val assetId: String, val reason: String) : AssetValidationError {
        override val message = "Asset integrity failure for $assetId: $reason"
    }

    data class InvalidMetadata(val reason: String) : AssetValidationError {
        override val message = "Invalid asset metadata: $reason"
    }

    data class InvalidText(val reason: String) : AssetValidationError {
        override val message = "Invalid text asset: $reason"
    }
}

class AssetValidationException(val error: AssetValidationError) : IllegalArgumentException(error.message)
