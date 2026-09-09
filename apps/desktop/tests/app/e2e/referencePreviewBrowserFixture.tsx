// @author kongweiguang
import { useState } from "react";
import { createRoot } from "react-dom/client";
import { Composer, type ConversationContextReference } from "@/features/conversation";
import { FileTree } from "@/features/workbench/files";
import "@/shared/styles/tokens.css";
import "@/shared/styles/primitives.css";
import "@/app/App.css";

/** 浏览器夹具使用真实 FileTree 与 Composer；仅记录预览意图，不伪造原生文件读取验收。 */
export function ReferencePreviewBrowserFixture() {
  const [references, setReferences] = useState<readonly ConversationContextReference[]>([]);
  const [text, setText] = useState("");
  const [opened, setOpened] = useState("");
  const [openCount, setOpenCount] = useState(0);
  const node = {
    id: "reference.txt",
    path: "reference.txt",
    name: "reference.txt",
    kind: "file" as const,
  };
  return (
    <main style={{ padding: 16, display: "grid", gap: 16 }}>
      <div style={{ height: 180 }}>
        <FileTree
          nodes={[node]}
          selectedPath={node.path}
          onAddToConversation={(file) =>
            setReferences([
              {
                type: "workspace_reference",
                workspaceId: "ws_fixture",
                relativePath: file.path,
                kind: "file",
              },
            ])
          }
        />
      </div>
      <Composer
        text={text}
        onTextChange={setText}
        contextReferences={references}
        onContextReferencesChange={setReferences}
        onSend={() => {
          throw new Error("No provider calls allowed");
        }}
        onOpenWorkspaceReference={(reference) => {
          setOpened(reference.relativePath);
          setOpenCount((count) => count + 1);
        }}
      />
      <output aria-label="预览意图">{opened}</output>
      <output aria-label="预览次数">{openCount}</output>
    </main>
  );
}

createRoot(document.getElementById("root")!).render(<ReferencePreviewBrowserFixture />);
