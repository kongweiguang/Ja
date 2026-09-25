// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

//! 图片只在 blocking worker 中调用的受预算解码与 PNG 衍生管线。

use super::error::{AttachmentPreviewError, AttachmentPreviewErrorCode};
use super::model::{
    ATTACHMENT_PREVIEW_LONG_EDGE, ATTACHMENT_PREVIEW_MAX_DECODE_BYTES,
    ATTACHMENT_PREVIEW_MAX_PIXELS, ATTACHMENT_PREVIEW_THUMBNAIL_EDGE,
};
use image::codecs::png::{CompressionType, FilterType, PngEncoder};
use image::{DynamicImage, GenericImageView, ImageEncoder, ImageReader, Limits};
use std::io::Cursor;

/// 两个固定衍生物都编码为静态 PNG，因此 GIF 天然只消费解码器暴露的首帧。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AttachmentImageDerivatives {
    pub thumbnail_png: Vec<u8>,
    pub preview_png: Vec<u8>,
}

/// 先读尺寸并按 40MP 拒绝，再带 256MiB decoder allocation 预算解码；失败不改变附件本体状态。
pub fn derive_attachment_image(
    bytes: &[u8],
) -> Result<AttachmentImageDerivatives, AttachmentPreviewError> {
    let (width, height) = dimensions(bytes)?;
    let pixels = u64::from(width)
        .checked_mul(u64::from(height))
        .ok_or_else(image_budget_error)?;
    if pixels == 0 || pixels > ATTACHMENT_PREVIEW_MAX_PIXELS {
        return Err(image_budget_error());
    }
    let mut reader = guessed_reader(bytes)?;
    let mut limits = Limits::default();
    limits.max_image_width = Some(u32::try_from(ATTACHMENT_PREVIEW_MAX_PIXELS).unwrap_or(u32::MAX));
    limits.max_image_height =
        Some(u32::try_from(ATTACHMENT_PREVIEW_MAX_PIXELS).unwrap_or(u32::MAX));
    limits.max_alloc = Some(ATTACHMENT_PREVIEW_MAX_DECODE_BYTES);
    reader.limits(limits);
    let decoded = reader.decode().map_err(map_decode_error)?;
    let thumbnail = resize_long_edge(&decoded, ATTACHMENT_PREVIEW_THUMBNAIL_EDGE);
    let preview = resize_long_edge(&decoded, ATTACHMENT_PREVIEW_LONG_EDGE);
    Ok(AttachmentImageDerivatives {
        thumbnail_png: encode_png(&thumbnail)?,
        preview_png: encode_png(&preview)?,
    })
}

/// 使用独立 reader 读取 header，避免 dimension probe 改变后续 decoder 的 cursor 状态。
fn dimensions(bytes: &[u8]) -> Result<(u32, u32), AttachmentPreviewError> {
    guessed_reader(bytes)?
        .into_dimensions()
        .map_err(map_decode_error)
}

/// 仅按内容 sniff；原始文件名、路径和扩展名从未进入图片解析边界。
fn guessed_reader(bytes: &[u8]) -> Result<ImageReader<Cursor<&[u8]>>, AttachmentPreviewError> {
    ImageReader::new(Cursor::new(bytes))
        .with_guessed_format()
        .map_err(|_| AttachmentPreviewError::new(AttachmentPreviewErrorCode::InvalidImage))
}

/// 缩小使用 Lanczos3 保持文字与截图清晰；不放大可避免小图产生虚假的原始分辨率。
fn resize_long_edge(image: &DynamicImage, maximum: u32) -> DynamicImage {
    let (width, height) = image.dimensions();
    if width <= maximum && height <= maximum {
        return image.clone();
    }
    image.resize(maximum, maximum, image::imageops::FilterType::Lanczos3)
}

/// 统一输出 RGBA PNG，使 custom protocol 的 Content-Type 与实际字节始终一致。
fn encode_png(image: &DynamicImage) -> Result<Vec<u8>, AttachmentPreviewError> {
    let rgba = image.to_rgba8();
    let mut bytes = Vec::new();
    PngEncoder::new_with_quality(&mut bytes, CompressionType::Fast, FilterType::Adaptive)
        .write_image(
            rgba.as_raw(),
            rgba.width(),
            rgba.height(),
            image::ExtendedColorType::Rgba8,
        )
        .map_err(|_| AttachmentPreviewError::new(AttachmentPreviewErrorCode::InvalidImage))?;
    Ok(bytes)
}

/// 保留“预算超限”和“内容损坏”的恢复语义，同时丢弃 decoder 的内部路径与格式细节。
fn map_decode_error(error: image::ImageError) -> AttachmentPreviewError {
    if matches!(error, image::ImageError::Limits(_)) {
        image_budget_error()
    } else {
        AttachmentPreviewError::new(AttachmentPreviewErrorCode::InvalidImage)
    }
}

/// 图片预算错误统一不可重试；重新读取同一内容不会改变像素或解码内存需求。
const fn image_budget_error() -> AttachmentPreviewError {
    AttachmentPreviewError::new(AttachmentPreviewErrorCode::ImageBudgetExceeded)
}
