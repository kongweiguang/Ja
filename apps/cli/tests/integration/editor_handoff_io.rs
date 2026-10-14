// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#[allow(dead_code)]
mod controller {
    include!("../../src/controller/mod.rs");

    mod regression {
        use super::{external_editor, interactive};

        /// @author kongweiguang
        /// 挂起失败不得把唯一原文随 RAII 删除；真实文件回读归集成测试，确认退出后仍可取回原文。
        #[test]
        fn editor_suspend_failure_preserves_original_file_and_visible_error() {
            let original = "未发送原草稿：中文🙂";
            let temporary = external_editor::create_editor_draft(original).unwrap();
            let path = temporary.path.clone();
            let error = interactive::preserve_editor_handoff_failure(
                temporary,
                None,
                "终端未能交给编辑器，Ja 已退出：注入清理失败",
            );
            assert_eq!(error.exit_code, 4);
            assert!(error.message().contains("注入清理失败"));
            assert!(error.message().contains(path.to_str().unwrap()));
            assert_eq!(std::fs::read_to_string(&path).unwrap(), original);
            std::fs::remove_file(path).unwrap();
        }

        /// @author kongweiguang
        /// 恢复边界失败时另存原文，不覆盖已成功写回的编辑文件；两份文件只由用户或本测试显式回收。
        #[test]
        fn editor_resume_failure_preserves_original_and_edited_text_separately() {
            let original = "原文🙂";
            let edited = "编辑器新文\n中文";
            let temporary = external_editor::create_editor_draft(original).unwrap();
            let edited_path = temporary.path.clone();
            std::fs::write(&edited_path, edited).unwrap();
            let error = interactive::preserve_editor_handoff_failure(
                temporary,
                Some(original),
                "编辑器返回后终端恢复失败，Ja 已退出：输入隔离失败",
            );
            let original_path = std::path::PathBuf::from(
                error
                    .message()
                    .split("原草稿已保存在 ")
                    .nth(1)
                    .unwrap()
                    .split('；')
                    .next()
                    .unwrap(),
            );
            assert_eq!(error.exit_code, 4);
            assert_ne!(original_path, edited_path);
            assert_eq!(std::fs::read_to_string(&original_path).unwrap(), original);
            assert_eq!(std::fs::read_to_string(&edited_path).unwrap(), edited);
            std::fs::remove_file(original_path).unwrap();
            std::fs::remove_file(edited_path).unwrap();
        }
    }
}
