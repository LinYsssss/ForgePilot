export function canApprovePatch(patch) {
  return Boolean(patch && patch.applyStatus === 'SUCCEEDED' && patch.targetDisappeared && !patch.stale)
}
