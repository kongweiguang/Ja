// @author kongweiguang
import assert from "node:assert/strict";
import test from "node:test";
import { isReleasedDraftThumbnail404 } from "./browser-enterprise-document.mjs";

const confirmedLateImage404 = {
  status: 404,
  resourceType: "image",
  attachmentId: "att_discarded",
  requestAttachmentId: "att_discarded",
  discardAttachmentId: "att_discarded",
  requestStartedBeforeDiscard: true,
  responseObservedAfterDiscardRequest: true,
  discardAcknowledged: true,
  draftDetached: true,
};

/** Keep identity, timing, and cleanup negatives explicit so genuine active-image 404s stay visible.
 * @author kongweiguang
 */
function verifiesDiscardedThumbnail404Boundary() {
  assert.equal(isReleasedDraftThumbnail404(confirmedLateImage404), true);

  const rejectedEvidence = [
    { name: "response before discard", responseObservedAfterDiscardRequest: false },
    { name: "request started after discard", requestStartedBeforeDiscard: false },
    { name: "discard was not acknowledged", discardAcknowledged: false },
    { name: "draft remains attached", draftDetached: false },
    { name: "another attachment", requestAttachmentId: "att_active" },
    { name: "non-image response", resourceType: "fetch" },
    { name: "non-404 response", status: 500 },
    { name: "missing attachment identity", attachmentId: "" },
  ];

  for (const { name, ...change } of rejectedEvidence) {
    assert.equal(isReleasedDraftThumbnail404({ ...confirmedLateImage404, ...change }), false, name);
  }
}

test(
  "only a late thumbnail 404 for a confirmed detached draft is released",
  verifiesDiscardedThumbnail404Boundary,
);
