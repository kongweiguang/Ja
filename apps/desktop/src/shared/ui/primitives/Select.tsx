// @author kongweiguang
// SPDX-License-Identifier: GPL-3.0-or-later

import * as SelectPrimitive from "@radix-ui/react-select";
import { Check, ChevronDown, ChevronUp } from "lucide-react";
import type { ComponentPropsWithoutRef, ReactElement, ReactNode } from "react";
import { cn } from "./cn";

interface SelectOption {
  readonly value: string;
  readonly label: ReactNode;
  readonly disabled?: boolean;
}

interface SelectOptionGroup {
  readonly label: ReactNode;
  readonly options: readonly SelectOption[];
}

interface SelectBaseProps {
  readonly value: string;
  readonly onValueChange: (value: string) => void;
  readonly placeholder?: string;
  readonly ariaLabel?: string;
  readonly ariaDescribedBy?: string;
  readonly ariaInvalid?: boolean;
  readonly className?: string;
  readonly contentClassName?: string;
  readonly disabled?: boolean;
  readonly autoFocus?: boolean;
  readonly id?: string;
  readonly name?: string;
  readonly required?: boolean;
  readonly size?: "compact" | "default";
}

export interface SelectProps extends SelectBaseProps {
  readonly options: readonly SelectOption[];
}

export interface GroupedSelectProps extends SelectBaseProps {
  readonly groups: readonly SelectOptionGroup[];
}

type SelectContentProps = ComponentPropsWithoutRef<typeof SelectPrimitive.Content>;

/**
 * 统一渲染 Select item，使所有 feature 共享同一选中语义、键盘导航和禁用状态，
 * 避免每个调用方重新组合 Radix 后产生不同的焦点与图标位置。
 */
function SelectItems({ options }: { options: readonly SelectOption[] }): ReactElement {
  return (
    <>
      {options.map((option) => (
        <SelectPrimitive.Item
          key={option.value}
          value={option.value}
          disabled={option.disabled}
          className="ja-select-item"
        >
          <SelectPrimitive.ItemText>{option.label}</SelectPrimitive.ItemText>
          <SelectPrimitive.ItemIndicator className="ja-select-item-indicator">
            <Check aria-hidden="true" size={14} />
          </SelectPrimitive.ItemIndicator>
        </SelectPrimitive.Item>
      ))}
    </>
  );
}

/**
 * Select 浮层固定通过 Portal、碰撞边界和共享 surface 呈现，保证它不会被 Terminal、
 * Diff 或 resizable panel 裁切，也让暗色、强制色与 reduced motion 只维护一份规则。
 */
function SelectContent({ className, children, ...props }: SelectContentProps): ReactElement {
  return (
    <SelectPrimitive.Portal>
      <SelectPrimitive.Content
        {...props}
        className={cn("ja-floating-surface ja-select-content", className)}
        position="popper"
        sideOffset={6}
        collisionPadding={8}
      >
        <SelectPrimitive.ScrollUpButton className="ja-select-scroll-button">
          <ChevronUp aria-hidden="true" size={14} />
        </SelectPrimitive.ScrollUpButton>
        <SelectPrimitive.Viewport className="ja-select-viewport">
          {children}
        </SelectPrimitive.Viewport>
        <SelectPrimitive.ScrollDownButton className="ja-select-scroll-button">
          <ChevronDown aria-hidden="true" size={14} />
        </SelectPrimitive.ScrollDownButton>
      </SelectPrimitive.Content>
    </SelectPrimitive.Portal>
  );
}

/**
 * Root 与 Trigger 的窄封装只拥有控件交互和视觉契约；value 的业务含义仍由 feature 持有，
 * 从而可以安全用于模型、审查来源、过滤器和原生 Shell profile 等不同闭集。
 */
function SelectFrame({
  value,
  onValueChange,
  placeholder,
  ariaLabel,
  ariaDescribedBy,
  ariaInvalid,
  className,
  contentClassName,
  disabled,
  autoFocus,
  id,
  name,
  required,
  size = "default",
  children,
}: SelectBaseProps & { children: ReactNode }): ReactElement {
  return (
    <SelectPrimitive.Root
      value={value}
      onValueChange={onValueChange}
      disabled={disabled}
      name={name}
      required={required}
    >
      <SelectPrimitive.Trigger
        id={id}
        className={cn("ja-select-trigger", `is-${size}`, className)}
        aria-label={ariaLabel}
        aria-describedby={ariaDescribedBy}
        aria-invalid={ariaInvalid}
        autoFocus={autoFocus}
      >
        <SelectPrimitive.Value placeholder={placeholder} />
        <SelectPrimitive.Icon className="ja-select-icon">
          <ChevronDown aria-hidden="true" size={14} />
        </SelectPrimitive.Icon>
      </SelectPrimitive.Trigger>
      <SelectContent className={contentClassName}>{children}</SelectContent>
    </SelectPrimitive.Root>
  );
}

/** 使用统一 Radix 组合渲染单层选项，替代浏览器差异明显的原生 select。 */
export function Select({ options, ...props }: SelectProps): ReactElement {
  return (
    <SelectFrame {...props}>
      <SelectItems options={options} />
    </SelectFrame>
  );
}

/**
 * 按稳定业务分组渲染选项；分组标题不参与选择，适合 Provider→models 等层级数据，
 * 同时保留 Radix 原生的方向键、类型搜索和屏幕阅读器语义。
 */
export function GroupedSelect({ groups, ...props }: GroupedSelectProps): ReactElement {
  return (
    <SelectFrame {...props}>
      {groups.map((group, index) => (
        <SelectPrimitive.Group key={index} className="ja-select-group">
          <SelectPrimitive.Label className="ja-select-group-label">
            {group.label}
          </SelectPrimitive.Label>
          <SelectItems options={group.options} />
          {index === groups.length - 1 ? null : (
            <SelectPrimitive.Separator className="ja-select-separator" />
          )}
        </SelectPrimitive.Group>
      ))}
    </SelectFrame>
  );
}
