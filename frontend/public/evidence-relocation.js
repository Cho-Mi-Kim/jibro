/** Reconnect a stored reference only when its entire verified source part is unchanged. */
export async function relocateEvidence(file, originalSha, selector = {}) {
    const manifestsResponse = await fetch('/evidence-manifests.json', {
        cache: 'no-store',
    });
    if (!manifestsResponse.ok) return null;
    const manifest = (await manifestsResponse.json())[file];
    if (
        !manifest ||
        manifest.sha256 !== originalSha ||
        !Array.isArray(manifest.parts)
    )
        return null;
    const candidates = manifest.parts.filter((part) =>
        selector.location != null
            ? part.location === selector.location
            : selector.part != null
              ? part.part === selector.part
              : manifest.parts.length === 1,
    );
    if (candidates.length !== 1 || !/^[0-9a-f]{64}$/.test(candidates[0].digest))
        return null;
    const response = await fetch(
        '/api/v1/notices/official-pdf/' +
            file +
            '/relocate?baselineSha=' +
            originalSha +
            '&partDigest=' +
            candidates[0].digest,
        { cache: 'no-store' },
    );
    if (!response.ok) return null;
    const result = await response.json();
    if (
        !/^[0-9a-f]{64}$/.test(result.sha256 || '') ||
        result.sha256 === originalSha ||
        !Number.isInteger(result.part) ||
        result.part < 0 ||
        typeof result.location !== 'string'
    )
        return null;
    return result;
}
