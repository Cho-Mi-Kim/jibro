import test from 'node:test';
import assert from 'node:assert/strict';
import { relocateEvidence } from '../public/evidence-relocation.js';

const oldSha = 'a'.repeat(64), newSha = 'b'.repeat(64), partDigest = 'c'.repeat(64);
function response(json, ok = true) { return {ok, json: async () => json}; }
test('reconnects a moved verified part to the current digest', async () => {
 const oldFetch = globalThis.fetch;
 try {
  globalThis.fetch = async url => url === '/evidence-manifests.json'
   ? response({'123': {sha256: oldSha, parts: [{part: 2, location: '공고문 3쪽', digest: partDigest}]}})
   : response({sha256: newSha, part: 3, location: '공고문 4쪽'});
  assert.deepEqual(await relocateEvidence('123', oldSha, {location:'공고문 3쪽'}), {sha256:newSha,part:3,location:'공고문 4쪽'});
 } finally { globalThis.fetch = oldFetch; }
});
test('keeps the original reference unresolved when the part is missing or ambiguous', async () => {
 const oldFetch = globalThis.fetch;
 try {
  globalThis.fetch = async url => url === '/evidence-manifests.json'
   ? response({'123': {sha256: oldSha, parts: [{part: 2, location: '공고문 3쪽', digest: partDigest}]}})
   : response({}, false);
  assert.equal(await relocateEvidence('123', oldSha, {location:'공고문 3쪽'}), null);
  assert.equal(await relocateEvidence('123', oldSha, {location:'공고문 8쪽'}), null);
  assert.equal(await relocateEvidence('123', newSha, {location:'공고문 3쪽'}), null);
 } finally { globalThis.fetch = oldFetch; }
});
