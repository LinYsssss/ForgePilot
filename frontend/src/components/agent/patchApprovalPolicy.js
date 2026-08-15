/**
 * 人工审批补丁的可用性判定。三个条件必须同时成立:
 *   - applyStatus === 'SUCCEEDED':补丁能干净地打上去;
 *   - targetDisappeared:**缺陷特征在打补丁后消失了**,即这个补丁确实修好了问题。
 *     这个名字极易被读反——它指的不是「目标文件不见了」,而是沙箱里 baseline 输出命中了
 *     缺陷特征、patched 输出不再命中(见 sandbox-runner PatchValidationExecutor:68-70);
 *   - !stale:补丁所基于的 head 仍是当前 head,没被新提交顶掉。
 *
 * 与后端 PatchCandidate.isApprovable() 同口径,前端不另立一套策略;这里只是把同一条规则前移,
 * 让按钮在提交前就处于禁用态。
 */
export function canApprovePatch(patch) {
  return Boolean(patch && patch.applyStatus === 'SUCCEEDED' && patch.targetDisappeared && !patch.stale)
}
