// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Preview open、用户 navigation 与 redirect 共用唯一 URL policy。

use super::error::{PreviewError, PreviewErrorCode};
use super::model::{NavigationSource, PreviewLimits, PreviewUrl};
use url::Url;

/// 子 WebView 允许的 navigation decision。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PreviewNavigationDecision {
    Allow { url: PreviewUrl },
}

/// 校验外部 HTTP(S) 地址与受控本地文档地址，不引入 browser automation policy。
#[derive(Debug, Clone)]
pub struct PreviewPolicy {
    limits: PreviewLimits,
}

impl Default for PreviewPolicy {
    /// policy 仅限 UI 所需的 scheme/authority 边界，不扩张为浏览器策略。
    fn default() -> Self {
        Self {
            limits: PreviewLimits::default(),
        }
    }
}

impl PreviewPolicy {
    /// 校验全部 queue budget 后构造 policy。
    pub fn new() -> Result<Self, PreviewError> {
        Self::with_limits(PreviewLimits::default())
    }

    /// 允许测试与未来 host tuning 提供显式预算，但仍需经过同一校验。
    pub fn with_limits(limits: PreviewLimits) -> Result<Self, PreviewError> {
        Ok(Self {
            limits: limits.validate()?,
        })
    }

    /// 返回供 session registry 使用的已验证有界预算。
    pub(crate) fn limits(&self) -> PreviewLimits {
        self.limits
    }

    /// 创建 WebView 前拒绝除 HTTP(S) 外的全部 scheme。
    pub fn validate_url(&self, raw: &str) -> Result<PreviewUrl, PreviewError> {
        if raw.len() > self.limits.max_url_bytes {
            return Err(PreviewError::new(PreviewErrorCode::UrlTooLong));
        }
        if raw.is_empty() {
            return Err(PreviewError::new(PreviewErrorCode::UrlInvalid));
        }
        if raw
            .chars()
            .any(|character| character.is_control() || character.is_whitespace())
            || raw.contains('\\')
        {
            return Err(PreviewError::new(PreviewErrorCode::UrlControlCharacter));
        }
        let Some(prefix_len) = strict_http_prefix(raw) else {
            let parsed = Url::parse(raw);
            return if parsed
                .as_ref()
                .is_ok_and(|url| !matches!(url.scheme(), "http" | "https"))
            {
                Err(PreviewError::new(PreviewErrorCode::SchemeNotAllowed))
            } else {
                Err(PreviewError::new(PreviewErrorCode::UrlInvalid))
            };
        };
        if raw
            .as_bytes()
            .get(prefix_len)
            .is_some_and(|byte| *byte == b'/')
        {
            return Err(PreviewError::new(PreviewErrorCode::UrlInvalid));
        }
        validate_percent_escapes(raw)?;
        let parsed =
            Url::parse(raw).map_err(|_| PreviewError::new(PreviewErrorCode::UrlInvalid))?;
        if !matches!(parsed.scheme(), "http" | "https") {
            return Err(PreviewError::new(PreviewErrorCode::SchemeNotAllowed));
        }
        if parsed.host_str().is_none_or(str::is_empty) {
            return Err(PreviewError::new(PreviewErrorCode::HostMissing));
        }
        if !parsed.username().is_empty()
            || parsed.password().is_some()
            || authority_has_userinfo(raw)
        {
            return Err(PreviewError::new(PreviewErrorCode::UserInfoNotAllowed));
        }
        let normalized = parsed.as_str().to_owned();
        if normalized.len() > self.limits.max_url_bytes {
            return Err(PreviewError::new(PreviewErrorCode::UrlTooLong));
        }
        Ok(PreviewUrl::from_normalized(normalized))
    }

    /// 只为被点击的本地文件创建 child WebView；路径真实性与权限由 local-file resolver 再核实。
    pub(crate) fn validate_file_url(&self, raw: &str) -> Result<PreviewUrl, PreviewError> {
        self.validate_url_shape(raw)?;
        let parsed =
            Url::parse(raw).map_err(|_| PreviewError::new(PreviewErrorCode::UrlInvalid))?;
        if parsed.scheme() != "file" || parsed.to_file_path().is_err() {
            return Err(PreviewError::new(PreviewErrorCode::SchemeNotAllowed));
        }
        let normalized = parsed.to_string();
        if normalized.len() > self.limits.max_url_bytes {
            return Err(PreviewError::new(PreviewErrorCode::UrlTooLong));
        }
        Ok(PreviewUrl::from_normalized(normalized))
    }

    /// Snapshot/event 回读允许 HTTP(S)、已规整 file URL 和唯一空白页，但 command input 仍单独校验。
    pub(crate) fn validate_wire_url(&self, raw: &str) -> Result<PreviewUrl, PreviewError> {
        if raw == "about:blank" {
            return Ok(PreviewUrl::from_normalized(raw.to_owned()));
        }
        if Url::parse(raw).is_ok_and(|url| url.scheme() == "file") {
            return self.validate_file_url(raw);
        }
        self.validate_url(raw)
    }

    /// 使用同一个 URL parser 重新校验用户或 redirect callback。
    pub fn navigation(
        &self,
        _source: NavigationSource,
        raw_url: &str,
        allow_file: bool,
        allow_blank: bool,
    ) -> Result<PreviewNavigationDecision, PreviewError> {
        if raw_url == "about:blank" && allow_blank {
            return Ok(PreviewNavigationDecision::Allow {
                url: PreviewUrl::from_normalized(raw_url.to_owned()),
            });
        }
        if allow_file && Url::parse(raw_url).is_ok_and(|url| url.scheme() == "file") {
            return Ok(PreviewNavigationDecision::Allow {
                url: self.validate_file_url(raw_url)?,
            });
        }
        Ok(PreviewNavigationDecision::Allow {
            url: self.validate_url(raw_url)?,
        })
    }

    /// 把控制字符与长度限制复用到 file URL，避免平台 URL parser 对异常字符作宽松修复。
    fn validate_url_shape(&self, raw: &str) -> Result<(), PreviewError> {
        if raw.len() > self.limits.max_url_bytes {
            return Err(PreviewError::new(PreviewErrorCode::UrlTooLong));
        }
        if raw.is_empty() {
            return Err(PreviewError::new(PreviewErrorCode::UrlInvalid));
        }
        if raw
            .chars()
            .any(|character| character.is_control() || character.is_whitespace())
            || raw.contains('\\')
        {
            return Err(PreviewError::new(PreviewErrorCode::UrlControlCharacter));
        }
        Ok(())
    }
}

/// 拒绝畸形 percent escape 与编码后的控制字符或反斜杠 byte。
fn validate_percent_escapes(raw: &str) -> Result<(), PreviewError> {
    let bytes = raw.as_bytes();
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' {
            if index + 2 >= bytes.len() {
                return Err(PreviewError::new(PreviewErrorCode::PercentEscapeInvalid));
            }
            let high = hex_value(bytes[index + 1]);
            let low = hex_value(bytes[index + 2]);
            let Some(high) = high else {
                return Err(PreviewError::new(PreviewErrorCode::PercentEscapeInvalid));
            };
            let Some(low) = low else {
                return Err(PreviewError::new(PreviewErrorCode::PercentEscapeInvalid));
            };
            let decoded = (high << 4) | low;
            if decoded < 0x20 || decoded == 0x7f || decoded == b'\\' {
                return Err(PreviewError::new(PreviewErrorCode::UrlControlCharacter));
            }
            index += 3;
        } else {
            index += 1;
        }
    }
    Ok(())
}

/// 拒绝浏览器与 URL parser 可能显示不一致的 userinfo 形状。
fn authority_has_userinfo(raw: &str) -> bool {
    let Some(scheme_end) = raw.find("://") else {
        return false;
    };
    let start = scheme_end + 3;
    let end = raw[start..]
        .find(['/', '?', '#'])
        .map_or(raw.len(), |offset| start + offset);
    raw[start..end].contains('@')
}

/// authority 入口只接受 ASCII case-insensitive HTTP(S)。
fn strict_http_prefix(raw: &str) -> Option<usize> {
    let bytes = raw.as_bytes();
    if bytes.len() >= 7 && bytes[..7].eq_ignore_ascii_case(b"http://") {
        return Some(7);
    }
    if bytes.len() >= 8 && bytes[..8].eq_ignore_ascii_case(b"https://") {
        return Some(8);
    }
    None
}

/// 无分配解析一个十六进制 escape nibble。
fn hex_value(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}
