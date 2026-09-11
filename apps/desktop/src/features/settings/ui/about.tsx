// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import {
  Download,
  ExternalLink,
  GitBranch,
  LoaderCircle,
  RefreshCw,
  RotateCw,
  Scale,
  Tag,
} from "lucide-react";
import { useState, type ReactElement, type ReactNode } from "react";
import packageJson from "../../../../../../package.json";
import type { AppUpdaterController, AppUpdaterState } from "../application/useAppUpdater";
import type { SettingsDesktopPort } from "../application/ports";
import { Button } from "@/shared/ui/primitives";
import { SectionHeader } from "./shared";
import "./skills-about.css";

const GITHUB_REPOSITORY_URL = "https://github.com/kongweiguang/Ja";

/** 统一版本前缀，避免 Git tag 与 package version 的展示格式分叉。 */
function versionLabel(version: string): string {
  return version.startsWith("v") ? version : `v${version}`;
}

/** 把有限更新状态翻译为短文案，技术错误保持在 native/诊断边界之外。 */
function updateStatus(state: AppUpdaterState): { label: string; detail: string; tone: string } {
  switch (state.kind) {
    case "checking":
      return { label: "检查中", detail: "正在检查新版本…", tone: "neutral" };
    case "unavailable":
      return { label: "桌面版可用", detail: "更新检查仅在 Ja 桌面应用中可用。", tone: "neutral" };
    case "up-to-date":
      return {
        label: "已是最新",
        detail: `当前版本 ${versionLabel(packageJson.version)}`,
        tone: "success",
      };
    case "available":
      return {
        label: "有新版本",
        detail: `${versionLabel(state.currentVersion)} → ${versionLabel(state.version)}`,
        tone: "available",
      };
    case "installing":
      return {
        label: "正在更新",
        detail:
          state.percent === undefined
            ? `正在下载 ${versionLabel(state.version)}`
            : `正在下载 ${versionLabel(state.version)} · ${state.percent}%`,
        tone: "available",
      };
    case "restart-required":
      return {
        label: state.restartFailed ? "重启失败" : "等待重启",
        detail: state.restartFailed
          ? `${versionLabel(state.version)} 已安装，请重试或手动重新启动 Ja。`
          : `${versionLabel(state.version)} 已安装，重新启动后生效。`,
        tone: state.restartFailed ? "danger" : "success",
      };
    case "error":
      return {
        label: "未能完成",
        detail:
          state.operation === "check"
            ? "请检查网络连接后重试。"
            : "更新未安装，请稍后重新检查并重试。",
        tone: "danger",
      };
  }
}

/** 连续信息行共享稳定图标列与动作列，长 URL 在窄窗口换行而不挤压按钮。 */
function AboutRow({
  icon,
  label,
  value,
  action,
}: {
  icon: ReactNode;
  label: string;
  value: ReactNode;
  action?: ReactNode;
}): ReactElement {
  return (
    <div className="ja-settings-about-row">
      <span className="ja-settings-about-row-icon" aria-hidden="true">
        {icon}
      </span>
      <div className="ja-settings-about-row-copy">
        <strong>{label}</strong>
        <span>{value}</span>
      </div>
      {action === undefined ? null : <div className="ja-settings-about-row-action">{action}</div>}
    </div>
  );
}

/** 根据状态只呈现当前可完成的更新动作，不显示 disabled 占位入口。 */
function UpdateAction({
  updater,
  compact = false,
}: {
  updater: AppUpdaterController;
  compact?: boolean;
}): ReactElement | null {
  const { state } = updater;
  if (state.kind === "available") {
    return (
      <Button
        className={compact ? "ja-settings-update-button is-compact" : "ja-settings-update-button"}
        onClick={() => void updater.install()}
      >
        <Download aria-hidden="true" />
        {compact ? `更新到 ${versionLabel(state.version)}` : "更新"}
        {compact ? <span className="ja-settings-update-dot" aria-hidden="true" /> : null}
      </Button>
    );
  }
  if (state.kind === "installing") {
    return (
      <Button className="ja-settings-update-button" disabled>
        <LoaderCircle className="is-spinning" aria-hidden="true" />
        {state.percent === undefined ? "更新中" : `${state.percent}%`}
      </Button>
    );
  }
  if (state.kind === "restart-required") {
    return (
      <Button className="ja-settings-update-button" onClick={() => void updater.relaunch()}>
        <RotateCw aria-hidden="true" />
        重新启动
      </Button>
    );
  }
  return null;
}

/** 设置工具栏仅在存在可执行更新动作时出现控件，自动检查与最新状态不制造视觉噪声。 */
export function SettingsUpdateAction({
  updater,
}: {
  updater: AppUpdaterController;
}): ReactElement | null {
  return <UpdateAction updater={updater} compact />;
}

/**
 * 关于页只展示可验证的产品事实和直接动作；GitHub 交给受限 opener，更新复用页面级 controller，
 * 因而自动检查、手动重试和安装不会形成并发的第二份状态。
 */
export function AboutSection({
  updater,
  openExternalUrl,
}: {
  updater: AppUpdaterController;
  openExternalUrl: SettingsDesktopPort["openExternalUrl"];
}): ReactElement {
  const [githubError, setGithubError] = useState(false);
  const status = updateStatus(updater.state);

  /** 外链失败只给出恢复提示，不把 OS 路径或浏览器细节投影进设置页。 */
  const openGitHub = async (): Promise<void> => {
    setGithubError(false);
    try {
      await openExternalUrl(GITHUB_REPOSITORY_URL);
    } catch {
      setGithubError(true);
    }
  };

  const checking = updater.state.kind === "checking";
  const busy = updater.state.kind === "installing";
  return (
    <div
      className="ja-settings-section ja-settings-about"
      data-setting-id="about-product"
      data-setting-search="关于 Ja GitHub 版本 协议 更新"
      tabIndex={-1}
    >
      <SectionHeader title="关于" />
      <div className="ja-settings-about-identity ja-about-identity">
        <div className="ja-settings-about-mark" aria-hidden="true">
          <img src="/favicon.png" alt="" />
        </div>
        <div>
          <h3>Ja</h3>
          <p>驾驭 Agent Harness，由 Java 25 驱动核心 Harness。</p>
        </div>
      </div>
      <div className="ja-settings-about-list ja-about-list">
        <AboutRow icon={<Tag />} label="版本" value={versionLabel(packageJson.version)} />
        <AboutRow icon={<Scale />} label="开源协议" value="GPL-3.0-or-later" />
        <AboutRow
          icon={<GitBranch />}
          label="GitHub"
          value={
            <>
              github.com/kongweiguang/Ja
              {githubError ? <small role="alert">无法打开，请检查默认浏览器设置。</small> : null}
            </>
          }
          action={
            <Button variant="secondary" onClick={() => void openGitHub()}>
              打开
              <ExternalLink aria-hidden="true" />
            </Button>
          }
        />
        <AboutRow
          icon={<RefreshCw className={checking ? "is-spinning" : undefined} />}
          label="软件更新"
          value={
            <>
              <span className={`ja-settings-update-status is-${status.tone}`}>{status.label}</span>
              <span>{status.detail}</span>
            </>
          }
          action={
            <div className="ja-settings-about-update-actions">
              <Button
                variant="secondary"
                disabled={checking || busy}
                onClick={() => void updater.check()}
              >
                <RefreshCw className={checking ? "is-spinning" : undefined} aria-hidden="true" />
                {checking ? "检查中" : "检查更新"}
              </Button>
              <UpdateAction updater={updater} />
            </div>
          }
        />
      </div>
    </div>
  );
}
