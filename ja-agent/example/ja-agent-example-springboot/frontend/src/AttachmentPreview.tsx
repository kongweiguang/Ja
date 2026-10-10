// @author kongweiguang
import { useEffect, useRef, useState } from "react";
import * as Dialog from "@radix-ui/react-dialog";
import { Download, LoaderCircle, RotateCcw, X } from "lucide-react";
import { readAttachmentContent } from "./api";
import type { AttachmentMetadata } from "./types";

interface AttachmentPreviewProps {
  threadId: string;
  attachment: AttachmentMetadata;
  returnFocusTo?: HTMLElement;
  onClose: () => void;
}

/** 在统一的受控预览中管理取消、Blob URL 与焦点，避免文件字节跨 Thread 留存。 */
export function AttachmentPreview({
  threadId,
  attachment,
  returnFocusTo,
  onClose,
}: AttachmentPreviewProps) {
  const [retry, setRetry] = useState(0);
  const [preview, setPreview] = useState<{ loading: boolean; url?: string; error?: string }>({
    loading: true,
  });
  const [downloadError, setDownloadError] = useState("");
  const [downloading, setDownloading] = useState(false);
  const downloadRequest = useRef<AbortController | null>(null);
  const urlRef = useRef<string | undefined>(undefined);
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    const controller = new AbortController();
    setPreview({ loading: true });
    void readAttachmentContent(threadId, attachment.id, false, controller.signal)
      .then((blob) => {
        if (controller.signal.aborted) return;
        const url = URL.createObjectURL(blob);
        urlRef.current = url;
        setPreview({ loading: false, url });
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) setPreview({ loading: false, error: errorMessage(error) });
      });
    return () => {
      controller.abort();
      if (urlRef.current) URL.revokeObjectURL(urlRef.current);
      urlRef.current = undefined;
    };
  }, [threadId, attachment.id, retry]);

  useEffect(() => {
    setDownloading(false);
    setDownloadError("");
    return () => {
      downloadRequest.current?.abort();
      downloadRequest.current = null;
    };
  }, [threadId, attachment.id]);

  /** 优先返回同 Thread 的可编辑输入框；只读时回来源控件，迟到关闭不抢占其它会话焦点。 */
  const restoreFocus = () => {
    requestAnimationFrame(() => {
      const composer = Array.from(
        document.querySelectorAll<HTMLTextAreaElement>(".composer textarea"),
      ).find((field) => field.dataset["threadId"] === threadId && !field.disabled);
      const target = composer ?? returnFocusTo;
      if (target?.isConnected) target.focus({ preventScroll: true });
    });
  };

  /** 同时只允许一个原始文件下载；关闭或切换资料会取消读取，迟到结果不再触发浏览器动作。 */
  const download = async () => {
    if (downloadRequest.current) return;
    const controller = new AbortController();
    downloadRequest.current = controller;
    setDownloading(true);
    setDownloadError("");
    try {
      const blob = await readAttachmentContent(threadId, attachment.id, true, controller.signal);
      if (controller.signal.aborted) return;
      const url = URL.createObjectURL(blob);
      try {
        const link = document.createElement("a");
        link.href = url;
        link.download = attachment.fileName;
        link.click();
      } finally {
        window.setTimeout(() => URL.revokeObjectURL(url), 0);
      }
    } catch (error) {
      if (!controller.signal.aborted) setDownloadError(`下载附件失败：${errorMessage(error)}`);
    } finally {
      if (downloadRequest.current === controller) {
        downloadRequest.current = null;
        setDownloading(false);
      }
    }
  };

  return (
    <Dialog.Root
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="attachment-preview-backdrop" />
        <Dialog.Content
          className="attachment-preview-dialog"
          aria-modal="true"
          aria-describedby={undefined}
          onOpenAutoFocus={(event) => {
            event.preventDefault();
            closeRef.current?.focus({ preventScroll: true });
          }}
          onCloseAutoFocus={(event) => {
            event.preventDefault();
            restoreFocus();
          }}
        >
          <header>
            <div>
              <Dialog.Title asChild>
                <strong>{attachment.fileName}</strong>
              </Dialog.Title>
              <small>{formatBytes(attachment.sizeBytes)}</small>
            </div>
            <div>
              <button
                className="icon-button"
                type="button"
                title="下载附件"
                aria-label="下载附件"
                aria-busy={downloading}
                disabled={downloading}
                onClick={() => void download()}
              >
                {downloading ? <LoaderCircle className="spin" /> : <Download />}
              </button>
              <Dialog.Close
                ref={closeRef}
                className="icon-button"
                type="button"
                title="关闭预览"
                aria-label="关闭预览"
              >
                <X />
              </Dialog.Close>
            </div>
          </header>
          <div className="attachment-preview-content">
            {downloadError && (
              <p className="attachment-preview-error" role="alert">
                {downloadError}
              </p>
            )}
            {preview.loading ? (
              <div role="status">
                <LoaderCircle className="spin" />
                正在读取资料
              </div>
            ) : preview.error ? (
              <div className="attachment-preview-error" role="alert">
                <p>{preview.error}</p>
                <button
                  className="text-button"
                  type="button"
                  onClick={() => setRetry((value) => value + 1)}
                >
                  <RotateCcw />
                  重试
                </button>
              </div>
            ) : preview.url && attachment.mediaType === "application/pdf" ? (
              <iframe title={`预览 ${attachment.fileName}`} src={preview.url} />
            ) : preview.url && attachment.mediaType.startsWith("image/") ? (
              <img src={preview.url} alt={attachment.fileName} />
            ) : (
              <p>此文件类型暂不支持预览，可下载原始文件。</p>
            )}
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

/** 展示与模型能力一致的十进制字节大小。 */
function formatBytes(value: number): string {
  if (!Number.isFinite(value) || value < 0) return "未知大小";
  return value < 1_000_000
    ? `${value.toLocaleString()} B`
    : `${(value / 1_000_000).toLocaleString(undefined, { maximumFractionDigits: 1 })} MB`;
}

/** 将异常转换成界面文本，不渲染任意对象或原始响应体。 */
function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "未知错误";
}
