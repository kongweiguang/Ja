// SPDX-License-Identifier: GPL-3.0-or-later
// @author kongweiguang

// Review 原生适配层。

pub(crate) mod catalog;
pub(crate) mod git;
pub(crate) mod git_query;
pub(crate) mod mutation;
pub(crate) mod native;
pub(crate) mod parse;
mod snapshot_cache;
pub(crate) mod snapshot_materializer;
pub(crate) mod transaction;

pub(crate) use native::NativeReviewAdapter;
