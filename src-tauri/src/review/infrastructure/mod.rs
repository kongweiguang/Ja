// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review 原生适配层。

pub(crate) mod catalog;
pub(crate) mod git;
pub(crate) mod git_query;
pub(crate) mod mutation;
pub(crate) mod native;
pub(crate) mod parse;
pub(crate) mod snapshot_materializer;
pub(crate) mod transaction;
pub(crate) mod turn_change;

pub(crate) use native::NativeReviewAdapter;
pub(crate) use turn_change::TurnChangeBaseline;
