// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

export { usePreviewController } from "./usePreviewController";
export { usePreviewLifecycleController } from "./usePreviewLifecycleController";
export { useAttachmentPreviewController } from "./useAttachmentPreviewController";
export { MediaPreviewSessionHintStorage } from "./previewSessionHintStorage";
export type {
  PreviewSessionHintMedia,
  PreviewSessionHintStorage,
} from "./previewSessionHintStorage";
export type {
  AttachmentPreviewAuthorization,
  AttachmentPreviewOpenResult,
  AttachmentPreviewPort,
  AttachmentPreviewReadResult,
  AttachmentPreviewTarget,
  NativePreviewPort,
  PreviewEvent,
  PreviewPageProjection,
  PreviewPort,
  PreviewSessionSnapshot,
  PreviewViewport,
} from "./ports";
export type { PreviewFileResolution, PreviewTarget } from "../domain/previewModel";
export type { PreviewActions } from "./usePreviewController";
export type {
  AttachmentPreviewActions,
  AttachmentPreviewController,
  AttachmentPreviewControllerOptions,
} from "./useAttachmentPreviewController";
export type {
  PreviewLifecycleProjection,
  PreviewWorkspaceLifecycle,
} from "./usePreviewLifecycleController";
