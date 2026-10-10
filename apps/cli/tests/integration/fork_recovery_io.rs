// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

#[allow(dead_code)]
mod controller {
    include!("../../src/controller/mod.rs");

    mod regression {
        use super::interactive::fork_recovery;
        use serde_json::{Value, json};

        /// @author kongweiguang
        /// 真实临时目录验证落盘仅含恢复身份，重启扫描按已知 child 找回而不复制历史正文。
        #[test]
        fn pending_fork_journal_restores_created_child_identity_from_disk() {
            let directory = std::env::temp_dir().join(format!(
                "ja-cli-fork-journal-{}",
                uuid::Uuid::new_v4().simple()
            ));
            let request = fork_recovery::PendingFork {
                operation_key: "op_0123456789abcdef0123456789abcdef".into(),
                source_thread_id: "thr_source".into(),
                source_item_id: "item_source".into(),
                expected_thread_revision: 27,
                child_thread_id: Some("thr_child".into()),
            };
            request.write_journal(&directory).expect("journal write");
            let path = fork_recovery::PendingFork::journal_path(&directory, &request.operation_key)
                .expect("operation journal path");
            let bytes = std::fs::read(&path).expect("journal read");
            let persisted: Value = serde_json::from_slice(&bytes).expect("journal JSON");
            assert_eq!(persisted.as_object().map(serde_json::Map::len), Some(5));
            assert_eq!(persisted["childThreadId"], "thr_child");
            assert!(persisted.get("text").is_none());

            let mut journals = fork_recovery::PendingFork::read_journals(&directory);
            assert!(journals.errors.is_empty());
            let restored =
                fork_recovery::PendingFork::take_for_thread(&mut journals.pending, "thr_child")
                    .expect("pending fork");
            assert_eq!(restored.params(), request.params());
            assert_eq!(restored.child_thread_id.as_deref(), Some("thr_child"));
            fork_recovery::PendingFork::clear_journal(&directory, &request.operation_key)
                .expect("journal cleanup");
            std::fs::remove_dir_all(directory).expect("temporary directory cleanup");
        }

        /// @author kongweiguang
        /// 两个 CLI operation 独立落盘和清理，重启按 source/child 精确恢复且互不覆盖。
        #[test]
        fn pending_fork_journals_are_isolated_across_sources_on_disk() {
            let directory = std::env::temp_dir().join(format!(
                "ja-cli-fork-journals-{}",
                uuid::Uuid::new_v4().simple()
            ));
            let first = fork_recovery::PendingFork {
                operation_key: "op_11111111111111111111111111111111".into(),
                source_thread_id: "thr_source_one".into(),
                source_item_id: "item_one".into(),
                expected_thread_revision: 13,
                child_thread_id: None,
            };
            let second = fork_recovery::PendingFork {
                operation_key: "op_22222222222222222222222222222222".into(),
                source_thread_id: "thr_source_two".into(),
                source_item_id: "item_two".into(),
                expected_thread_revision: 29,
                child_thread_id: Some("thr_child_two".into()),
            };
            first
                .write_journal(&directory)
                .expect("first journal write");
            second
                .write_journal(&directory)
                .expect("second journal write");

            let mut restarted = fork_recovery::PendingFork::read_journals(&directory);
            assert!(restarted.errors.is_empty());
            assert_eq!(restarted.pending.len(), 2);
            let restored_first = fork_recovery::PendingFork::take_for_thread(
                &mut restarted.pending,
                "thr_source_one",
            )
            .expect("source one journal after restart");
            assert_eq!(restored_first.params(), first.params());
            assert_eq!(restarted.pending.len(), 1);
            fork_recovery::PendingFork::clear_journal(&directory, &first.operation_key)
                .expect("clear first operation only");

            let mut after_first_clear = fork_recovery::PendingFork::read_journals(&directory);
            assert!(after_first_clear.errors.is_empty());
            let restored_second = fork_recovery::PendingFork::take_for_thread(
                &mut after_first_clear.pending,
                "thr_child_two",
            )
            .expect("child two journal survives first clear");
            assert_eq!(restored_second.params(), second.params());
            assert_eq!(after_first_clear.pending.len(), 0);
            fork_recovery::PendingFork::clear_journal(&directory, &second.operation_key)
                .expect("clear second operation");
            assert!(
                fork_recovery::PendingFork::read_journals(&directory)
                    .pending
                    .is_empty()
            );
            std::fs::remove_dir_all(directory).expect("temporary directory cleanup");
        }

        /// @author kongweiguang
        /// 文件名与记录身份错配时保留损坏证据，并拒绝可越出 journal 目录的 key。
        #[test]
        fn pending_fork_journal_rejects_mismatched_identity_and_unsafe_key() {
            let directory = std::env::temp_dir().join(format!(
                "ja-cli-fork-journal-invalid-{}",
                uuid::Uuid::new_v4().simple()
            ));
            std::fs::create_dir_all(&directory).expect("journal directory");
            let operation_key = "op_33333333333333333333333333333333";
            let path = directory.join(format!("{operation_key}.json"));
            std::fs::write(
                &path,
                json!({
                    "operationKey":"op_44444444444444444444444444444444",
                    "sourceThreadId":"thr_source",
                    "sourceItemId":"item_source",
                    "expectedThreadRevision":9,
                    "childThreadId":null
                })
                .to_string(),
            )
            .expect("damaged journal fixture");

            assert!(fork_recovery::PendingFork::journal_path(&directory, "op_../outside").is_err());
            assert!(
                fork_recovery::PendingFork::clear_journal(&directory, "op_../outside").is_err()
            );
            let scan = fork_recovery::PendingFork::read_journals(&directory);
            assert!(scan.pending.is_empty());
            assert_eq!(scan.errors.len(), 1);
            assert!(
                path.exists(),
                "invalid identity must not delete the evidence"
            );
            std::fs::remove_dir_all(directory).expect("temporary directory cleanup");
        }
    }
}
